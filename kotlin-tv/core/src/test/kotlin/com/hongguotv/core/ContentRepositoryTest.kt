package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException

class ContentRepositoryTest {
    // 冷却状态是进程级共享的：一个用例的空响应会毒到下一个，必须逐例清空。
    @Before fun clearPlaybackCooldown() = ContentRepository.resetPlaybackCooldown()
    /** 解出模型请求的 gzip JSON body（兼容重取请求在 biz_param 里多 `force_codec` 字段，靠它区分）。 */
    private fun gzBody(request: Request): String = runCatching {
        val buffer=okio.Buffer()
        request.body!!.writeTo(buffer)
        java.util.zip.GZIPInputStream(buffer.readByteArray().inputStream()).readBytes().decodeToString()
    }.getOrDefault("")
    private fun isCompatRequest(request: Request) =
        request.url.queryParameter("force_codec")=="h264" || gzBody(request).contains("\"force_codec\":\"h264\"")
    private fun streamRepository(vararg variants: Triple<Int,String,String>) = repository { request ->
        // 兼容重取请求（fallback URL 带 force_codec / 模型请求 biz_param 带 force_codec）一律拒绝：
        // 让不涉及兼容逻辑的用例保持旧行为，避免路径 A/B 抢跑。
        if(isCompatRequest(request)) """{"code":100103,"message":"compat rejected"}"""
        else if(request.url.host=="example.com") {
            val rows=JSONObject()
            variants.forEachIndexed { index,(height,codec,url) -> rows.put("video_$index",JSONObject().put("vheight",height).put("codec_type",codec).put("main_url",url)) }
            JSONObject().put("video_info",JSONObject().put("data",JSONObject().put("video_list",rows))).toString()
        } else """{"code":0,"data":{"123":{"video_model":{"fallback_api":"https://example.com/stream"}}}}"""
    }

    @Test fun incompatibleByteVc2DoesNotBecomeAudioOnlyPlayback() {
        val repo=streamRepository(Triple(720,"bytevc2","https://example.com/unsupported"),Triple(1080,"bytevc1","https://example.com/hevc"))
        val result=repo.stream("123",720)
        assertEquals("https://example.com/hevc",result.url)
        assertEquals("1080P（兼容资源）",result.quality)
    }

    @Test fun compatibleLowerQualityWinsBeforeHigherResolutionFallback() {
        val repo=streamRepository(Triple(720,"bytevc2","https://example.com/unsupported"),Triple(480,"h264","https://example.com/avc"),Triple(1080,"bytevc1","https://example.com/hevc"))
        assertEquals("480P",repo.stream("123",720).quality)
    }

    @Test fun entirelyIncompatibleSourceFailsBeforeStartingAudio() {
        val repo=streamRepository(Triple(720,"ByteVC2","https://example.com/unsupported"),Triple(480,"bvc2","https://example.com/unsupported2"))
        assertThrows(UnplayableVideoException::class.java) { repo.stream("123",720) }
    }

    @Test fun incompatibleSourceDiagnosticCarriesRouteAndEveryVideoListEntry() {
        // 模拟站方降级模型：全部条目 main_url 都是加密 blob（非 http 开头），应逐条出现在诊断里且带路由。
        // tab 接口响应缺 code:0 被 tabVideoModel 拒绝，模型实际来自第一条旧 POST 路由。
        val repo=repository { request ->
            if(request.url.host=="example.com") {
                val rows=JSONObject()
                    .put("video_1",JSONObject().put("vheight",1080).put("codec_type","bytevc2").put("main_url","qAABAGXlCAY==").put("backup_url_1","qAABAFoQKf1K=="))
                    .put("video_5",JSONObject().put("vheight",720).put("codec_type","bytevc2").put("main_url","qAABAAAA=="))
                JSONObject().put("video_info",JSONObject().put("data",JSONObject().put("video_list",rows))).toString()
            } else """{"code":0,"data":{"123":{"video_model":{"fallback_api":"https://example.com/stream"}}}}"""
        }
        val error=assertThrows(UnplayableVideoException::class.java) { repo.stream("123",1080) }
        // 2026-11-23 起取址首选 v5 player/v1，诊断里的路由标记随之变成 v5。
        assertTrue(error.diagnostic.startsWith("route=v5|"))
        assertTrue(error.diagnostic.contains("video_list=video_1:bytevc2:1080:enc/enc;video_5:bytevc2:720:enc/empty"))
    }

    @Test fun tabRouteTagRidesAlongInIncompatibleDiagnostic() {
        val repo=repository { request ->
            if(request.url.host=="example.com") {
                val rows=JSONObject().put("video_0",JSONObject().put("vheight",720).put("codec_type","bytevc2").put("main_url","qAABAAAA=="))
                JSONObject().put("video_info",JSONObject().put("data",JSONObject().put("video_list",rows))).toString()
            } else if(request.url.encodedPath==v5Path)
                // v5 用「错误码」这种非空失败，才允许走 video_tab 兜底（空响应会直接触发冷却）。
                """{"code":100103,"message":"invalid param"}"""
            else if(request.url.encodedPath==tabPath)
                """{"code":0,"data":{"video_model_data":{"123":{"video_model":{"fallback_api":"https://example.com/stream"}}}}}"""
            // 平铺 /v1 也给错误码：只有「非空失败」才允许继续往 tab 兜底，空响应会直接判额度用尽。
            else if(request.url.encodedPath==flatPath)
                """{"code":100103,"message":"invalid param"}"""
            else ""
        }
        val error=assertThrows(UnplayableVideoException::class.java) { repo.stream("123",1080) }
        assertTrue(error.diagnostic.startsWith("route=video_tab|"))
    }

    @Test fun degradedModelIsEvictedAndRefetchedWithRenewedIdentity() {
        var streamFetches=0
        val repo=repository { request ->
            // 兼容重取给错误码（换不出档位）：本用例验证的是降级模型的退避重取链路，别让兼容重取抢跑。
            // 注意路径 A 也打 example.com，必须拦在 host 判断之前。
            if(isCompatRequest(request)) """{"code":100103,"message":"invalid param"}"""
            else if(request.url.host=="example.com") {
                streamFetches++
                val rows=JSONObject()
                if(streamFetches==1) rows.put("video_0",JSONObject().put("vheight",720).put("codec_type","bytevc2").put("main_url","https://example.com/unsupported"))
                else rows.put("video_0",JSONObject().put("vheight",1080).put("codec_type","h264").put("main_url","https://example.com/recovered"))
                JSONObject().put("video_info",JSONObject().put("data",JSONObject().put("video_list",rows))).toString()
            }
            else """{"code":0,"data":{"123":{"video_model":{"fallback_api":"https://example.com/stream"}}}}"""
        }
        // 第一次取到降级模型，剔缓存换身份重取后拿到正常片源，而不是直接报不兼容。
        assertEquals("https://example.com/recovered",repo.stream("123",1080).url)
        assertEquals(2,streamFetches)
    }

    @Test fun repeatedlyDegradedModelsKeepRetryingBeforeReportingIncompatible() {
        // 站方对同一集间歇性下发降级模型（全清晰度 bytevc2），按请求随机出现：
        // 连续两轮降级后第三轮拿到正常模型，应播放成功而不是提前报不兼容。
        var streamFetches=0
        val repo=repository { request ->
            // 兼容重取给错误码：本用例验证的是连续降级模型的三轮重取，别让兼容重取提前救场。
            if(isCompatRequest(request)) """{"code":100103,"message":"invalid param"}"""
            else if(request.url.host=="example.com") {
                streamFetches++
                val rows=JSONObject()
                if(streamFetches<3) rows.put("video_0",JSONObject().put("vheight",1080).put("codec_type","bytevc2").put("main_url","qAABAAAA=="))
                else rows.put("video_0",JSONObject().put("vheight",1080).put("codec_type","h264").put("main_url","https://example.com/recovered"))
                JSONObject().put("video_info",JSONObject().put("data",JSONObject().put("video_list",rows))).toString()
            }
            else """{"code":0,"data":{"123":{"video_model":{"fallback_api":"https://example.com/stream"}}}}"""
        }
        assertEquals("https://example.com/recovered",repo.stream("123",1080).url)
        assertEquals(3,streamFetches)
    }

    @Test fun legacyMissingCodecLabelKeepsQualityLimit() {
        val repo=streamRepository(Triple(720,"","https://example.com/legacy"),Triple(1080,"bytevc1","https://example.com/hevc"))
        assertEquals("720P",repo.stream("123",720).quality)
    }

    // ---- 兼容重取（2026-11-25，全档 bytevc2 时以 codec_type=0 + force_codec=h264 换出可硬解档）----
    // 路径 A（上游实测形态）：fallback_api URL 附加 query 参数重读；路径 B（实验）：模型请求带兼容参数。

    @Test fun compatModelRescueReplacesAllByteVc2SourceWithPlayableTrack() {
        var compatFetches=0
        val repo=repository { request ->
            when {
                // 路径 A：fallback URL 附加 codec_type=0 & force_codec=h264 → 换出 h264 档。
                request.url.queryParameter("force_codec")=="h264" -> {
                    compatFetches++
                    JSONObject().put("video_info",JSONObject().put("data",JSONObject().put("video_list",
                        JSONObject().put("video_0",JSONObject().put("vheight",720).put("codec_type","h264").put("main_url","https://example.com/h264-rescued"))))).toString()
                }
                request.url.host=="example.com" ->
                    JSONObject().put("video_info",JSONObject().put("data",JSONObject().put("video_list",
                        JSONObject().put("video_0",JSONObject().put("vheight",1080).put("codec_type","bytevc2").put("main_url","https://example.com/bvc2-only"))))).toString()
                else -> """{"code":0,"data":{"123":{"video_model":{"fallback_api":"https://example.com/stream"}}}}"""
            }
        }
        val stream=repo.stream("123")
        assertEquals("https://example.com/h264-rescued",stream.url)
        // 只重取一次：成功后进缓存，失败也不循环。
        assertEquals(1,compatFetches)
    }

    @Test fun compatRescueFailureKeepsBvc2StreamForSoftDecoder() {
        val repo=repository { request ->
            when {
                // 路径 A 换出的还是 bytevc2；路径 B（模型请求级）给错误码。
                request.url.queryParameter("force_codec")=="h264" ->
                    JSONObject().put("video_info",JSONObject().put("data",JSONObject().put("video_list",
                        JSONObject().put("video_0",JSONObject().put("vheight",720).put("codec_type","bytevc2").put("main_url","https://example.com/bvc2-720"))))).toString()
                isCompatRequest(request) -> """{"code":100103,"message":"compat rejected"}"""
                request.url.host=="example.com" ->
                    JSONObject().put("video_info",JSONObject().put("data",JSONObject().put("video_list",
                        JSONObject().put("video_0",JSONObject().put("vheight",720).put("codec_type","bytevc2").put("main_url","https://example.com/bvc2-720"))))).toString()
                else -> """{"code":0,"data":{"123":{"video_model":{"fallback_api":"https://example.com/stream"}}}}"""
            }
        }
        // 兼容重取换不出可播档 → 回落原 bvc2 直链交给 vc2 软解，不能就此报死。
        val error=assertThrows(UnplayableVideoException::class.java) { repo.stream("123") }
        assertNotNull(error.bvc2Stream)
        assertEquals("https://example.com/bvc2-720",error.bvc2Stream!!.url)
    }

    @Test fun compatBlankResponseDoesNotTripPlaybackCooldown() {
        // 兼容重取两条路径都吃空响应必须静默：无效参数请求不能把主播放打进「官方接口限流中」。
        val repo=repository { request ->
            when {
                request.url.host=="example.com" ->
                    if(request.url.encodedPath.contains("stream123"))
                        JSONObject().put("video_info",JSONObject().put("data",JSONObject().put("video_list",
                            JSONObject().put("video_0",JSONObject().put("vheight",720).put("codec_type","bytevc2").put("main_url","qAABAAAA=="))))).toString()
                    else
                        JSONObject().put("video_info",JSONObject().put("data",JSONObject().put("video_list",
                            JSONObject().put("video_0",JSONObject().put("vheight",1080).put("codec_type","h264").put("main_url","https://example.com/ok-456"))))).toString()
                request.url.queryParameter("force_codec")=="h264" -> ""   // 路径 A / video_tab 兼容请求：空响应
                gzBody(request).contains("\"force_codec\":\"h264\"") -> "" // 路径 B preload：空响应
                else -> {
                    val ep=request.url.queryParameter("video_id") ?: if(gzBody(request).contains("\"123\"")) "123" else "456"
                    """{"code":0,"data":{"$ep":{"video_model":{"fallback_api":"https://example.com/stream$ep"}}}}"""
                }
            }
        }
        // 全档 bytevc2 + 兼容重取空响应：报「片源不兼容」而不是「限流中」。
        assertThrows(UnplayableVideoException::class.java) { repo.stream("123") }
        // 冷却没被触发：换一集照常取址成功。
        assertEquals("https://example.com/ok-456",repo.stream("456").url)
    }

    @Test fun inlineAllByteVc2ArrayIsRescuedByCompatModel() {
        val expire=System.currentTimeMillis()/1000+3600
        val repo=repository { request ->
            when {
                request.url.host=="example.com" ->
                    JSONObject().put("video_info",JSONObject().put("data",JSONObject().put("video_list",
                        JSONObject().put("video_0",JSONObject().put("vheight",720).put("codec_type","h264").put("main_url","https://example.com/h264-rescued"))))).toString()
                isCompatRequest(request) ->
                    """{"code":0,"data":{"123":{"video_model":{"url_expire":$expire,"fallback_api":"https://example.com/stream"}}}}"""
                else ->
                    """{"code":0,"data":{"123":{"video_model":{"url_expire":$expire,"video_list":""" +
                        """[{"main_url":"https://example.com/bvc2.mp4","video_meta":{"codec_type":"bytevc2","vheight":1080,"definition":"1080p"}}]}}}}"""
            }
        }
        // 7.4.5.32 内联数组形态全档 bytevc2：同样走兼容重取，不再掉进「播放地址格式异常」。
        assertEquals("https://example.com/h264-rescued",repo.stream("123").url)
    }

    private fun videoInfo() =
        """{"video_info":{"data":{"video_list":{"video_0":{"vheight":1080,"codec_type":"h264","main_url":"https://example.com/a.mp4"}}}}}"""
    private val tabPath="/reading/bookapi/video_tab/multi_video_model/v1/"
    // 2026-11-25 对齐上游 0.1.40：单集 video_model/v1 是官方 7.4.5.32 现行端点（11-24 放行），首选。
    private val currentPath="/novel/player/video_model/v1/"
    // 现行批量协议（上游 0.16.14）：reading.snssdk.com + preload/v1 + F21=312，退居第二形态。
    private val preloadPath="/novel/player/multi_video_model/preload/v1"
    // 老的 7.4.3.32 形态：现行协议给了非空失败才回退到它。
    private val v5Path="/novel/player/multi_video_model/player/v1"
    private val flatPath="/novel/player/multi_video_model/v1"

    @Test fun currentModelRouteSuppliesPlaybackModelWithASingleRequest() {
        val paths=mutableListOf<String>()
        val repo=repository { request ->
            if(request.url.host=="example.com") videoInfo()
            else {
                paths += request.url.encodedPath
                """{"code":0,"data":{"123":{"video_model":{"fallback_api":"https://example.com/stream"}}}}"""
            }
        }
        assertEquals("1080P",repo.stream("123").quality)
        // 一次就够：现行单集接口命中后不再打 preload / video_tab，也不回退老路径。
        assertEquals(listOf(currentPath),paths)
    }

    @Test fun nonEmptyFailureFallsBackToLegacyPostRoute() {
        val paths=mutableListOf<String>()
        val repo=repository { request ->
            when {
                request.url.host=="example.com" -> videoInfo()
                request.url.encodedPath in listOf(currentPath,preloadPath,v5Path) -> {
                    paths += request.url.encodedPath
                    // 现行协议非空失败（有错误码、没有模型）：不算额度用尽，允许回退老形态一发。
                    """{"code":100001}"""
                }
                request.url.encodedPath==v5Path -> {
                    paths += v5Path
                    """{"code":100001}"""
                }
                request.url.encodedPath==tabPath -> {
                    paths += tabPath
                    """{"code":0,"data":{"123":{"video_model":{"fallback_api":"https://example.com/stream"}}}}"""
                }
                else -> ""
            }
        }
        assertEquals("1080P",repo.stream("123").quality)
        // 单集错误码 → preload 错误码 → 老 player/v1 一发 → 仍无模型 → video_tab 兜底命中。
        assertEquals(listOf(currentPath,preloadPath,v5Path,tabPath),paths)
    }

    @Test fun inlineVideoListArrayServesStreamWithoutFallbackRequest() {
        // 7.4.5.32 现行协议把全部档位直链内联在模型的 video_list 数组里（带 video_meta），
        // 起播只需取址那一发，不再打 fallback_api 第二发。
        val paths=mutableListOf<String>()
        val expire=System.currentTimeMillis()/1000+3600
        val repo=repository { request ->
            paths += request.url.encodedPath
            """{"code":0,"data":{"123":{"video_model":{"url_expire":$expire,"video_list":[""" +
                """{"main_url":"https://example.com/720.mp4","video_meta":{"codec_type":"bytevc1","vheight":720,"definition":"720p"}},""" +
                """{"main_url":"https://example.com/1080.mp4","video_meta":{"codec_type":"bytevc1","vheight":1280,"definition":"1080p"}}""" +
                """]}}}}"""
        }
        val stream=repo.stream("123")
        assertEquals("1080P",stream.quality)
        assertTrue(stream.url.endsWith("1080.mp4"))
        assertEquals(listOf(currentPath),paths)
        // 切清晰度走缓存阶梯（升序，低 → 高），0 请求。
        val tiers=repo.ladder("123")
        assertEquals(listOf("720P","1080P"),tiers.map{it.quality})
        assertEquals(listOf(currentPath),paths)
    }

    @Test fun videoModelCanBeStringEncodedJson() {
        val repo=repository { request ->
            if(request.url.host=="example.com") videoInfo()
            else """{"code":0,"data":{"123":{"video_model":"{\"fallback_api\":\"https://example.com/stream\"}"}}}"""
        }
        assertEquals("1080P",repo.stream("123").quality)
    }

    @Test fun warmFillsAWholeWindowInOneBatchAndSubsequentLookupHitsCache() {
        val modelRequests=mutableListOf<String>()
        val repo=repository { request ->
            when {
                request.url.host=="example.com" -> videoInfo()
                request.url.encodedPath.startsWith("/novel/player/multi_video_model/") -> {
                    modelRequests += request.url.encodedPath
                    val rows=JSONObject()
                    listOf("1","2","3").forEach { id ->
                        rows.put(id,JSONObject().put("video_model",JSONObject().put("fallback_api","https://example.com/stream")))
                    }
                    JSONObject().put("code",0).put("data",rows).toString()
                }
                else -> ""
            }
        }
        val report=repo.warm(listOf("1","2","3"))
        assertEquals(3,report.cached)
        assertEquals(1,modelRequests.size)
        assertEquals("1080P",repo.stream("3").quality)
        // 批量取过的集不再单独问一次模型接口。
        assertEquals(1,modelRequests.size)
    }

    @Test fun blankResponseRotatesIdentityThenCoolsDown() {
        val paths=mutableListOf<String>()
        val repo=repository { request ->
            paths += request.url.encodedPath
            if(request.url.host=="example.com") videoInfo() else ""
        }
        val first=assertThrows(PlaybackThrottledException::class.java) { repo.stream("123") }
        assertTrue(first.remainingMs>0)
        // 空响应后 video_tab（v5 签名）是备用通道：每台身份「单集 + preload + tab」3 发，
        // 2 台身份共 6 发（仍远低于压缩前的 12 发）。
        val expected=listOf(currentPath,preloadPath,tabPath,currentPath,preloadPath,tabPath)
        assertEquals(expected,paths)
        // 冷却期内换一集：一个请求都不发，立刻失败。
        assertThrows(PlaybackThrottledException::class.java) { repo.stream("456") }
        assertEquals(expected,paths)
    }

    @Test fun nonEmptyFailureIsCappedAtSevenRequests() {
        // 非空失败（服务端回错误码）才允许换路径：首台身份单集 → preload → 老 player/v1 → video_tab 共 4 发，
        // 第二台身份「单集 + preload + tab」3 发（不再扫老路径），合计封顶 7 发（旧链路是 12 发）。
        val paths=mutableListOf<String>()
        val repo=repository { request ->
            paths += request.url.encodedPath
            if(request.url.host=="example.com") videoInfo() else """{"code":100103,"message":"invalid param"}"""
        }
        assertThrows(PlaybackThrottledException::class.java) { repo.stream("123") }
        assertEquals(listOf(currentPath,preloadPath,v5Path,tabPath,currentPath,preloadPath,tabPath),paths)
    }

    @Test fun blankResponseSucceedsOnVideoTabWithoutRotatingIdentity() {
        // 现行协议空响应时，同一身份的 video_tab（v5 签名）能直接拿到模型。
        // 命中后不再换设备——这是「少发请求」与「能播」同时成立的唯一组合。
        val requests=mutableListOf<Request>()
        val repo=repository { request ->
            requests += request
            when {
                request.url.host=="example.com" -> videoInfo()
                request.url.encodedPath in listOf(currentPath,preloadPath) -> ""
                else -> """{"code":0,"data":{"video_model_data":{"123":{"video_model":{"fallback_api":"https://example.com/stream"}}}}}"""
            }
        }
        val stream=repo.stream("123")
        assertTrue(stream.url.isNotBlank())
        assertEquals(listOf(currentPath,preloadPath,tabPath),requests.map{it.url.encodedPath}.take(3))
        val tab=requests[2]
        // GET + v5 三头；空字节只是签名输入，不该出现 POST 专用的 stub / Content-Encoding。
        assertEquals("GET",tab.method)
        assertTrue(tab.header("x-khronos").orEmpty().isNotBlank())
        assertTrue(tab.header("x-helios").orEmpty().isNotBlank())
        assertTrue(tab.header("x-medusa").orEmpty().isNotBlank())
        assertNull(tab.header("x-ss-stub"))
        assertNull(tab.header("Content-Encoding"))
        assertNull(tab.header("Content-Type"))
        assertNull(tab.header("Cookie"))
        assertEquals("1",tab.url.queryParameter("device_level"))
        assertEquals("123",tab.url.queryParameter("video_id"))
    }

    @Test fun blankResponseSucceedsOnSecondIdentity() {
        var calls=0
        val paths=mutableListOf<String>()
        val repo=repository { request ->
            calls++
            paths += request.url.encodedPath
            when {
                calls<=3 -> ""            // 首台设备：单集/preload/tab 全空 = 额度用尽
                request.url.host=="example.com" -> videoInfo()
                else -> """{"code":0,"data":{"123":{"video_model":{"fallback_api":"https://example.com/stream"}}}}"""
            }
        }
        val stream=repo.stream("123")
        assertTrue(stream.url.isNotBlank())
        // 换身份后仍在同一条首选路由（单集 video_model/v1）上重试，不是改打别的路由。
        // （末尾的 /stream 是命中后 fallback_api 的取流请求，不属取址预算）
        assertEquals(listOf(currentPath,preloadPath,tabPath,currentPath),paths.take(4))
    }

    private fun repository(clock: () -> Long = System::currentTimeMillis, respond: (Request) -> String) = ContentRepository(
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(respond(chain.request()).toResponseBody()).build()
        }.build(),clock,
        // 播放身份固定，测试不真的去注册设备。
        PlaybackDeviceSession { _, _ -> DeviceRegister.Registered("1905892595382586","1905892595378490") }
    )

    private fun rankPayload(page: Int = 1, totalPages: Int = 2) = JSONObject()
        .put("isSuccess",true)
        .put("rankList",JSONArray().put(JSONObject().put("seriesId","123").put("title","测试漫剧")
            .put("cover","https:\\u002F\\u002Fexample.com\\u002Fcover.jpg")
            .put("episodeCount",12).put("tags",JSONArray().put("玄幻"))))
        .put("pagination",JSONObject().put("pageNum",page).put("totalPages",totalPages))

    private fun script(name: String, args: JSONArray) =
        "<script data-fn-name=\"$name\" data-fn-args=\"${args.toString().replace("&","&amp;").replace("\"","&quot;")}\"></script>"

    private fun currentRank(payload: JSONObject) = script("mergeLoaderData",JSONArray()
        .put("rank_hot-comic-drama/page").put(JSONArray().put(JSONObject().put("key","content")
            .put("routerDataFnName","p").put("routerDataFnArgs",JSONArray().put(payload.toString())))))

    @Test fun comicRankingPreservesSourceRankHeatAndUpdateRatherThanRenumbering() {
        val payload=rankPayload(2,5)
        payload.getJSONArray("rankList").getJSONObject(0).put("rank",23).put("heatText","5746万热度")
        val repo=repository { request ->
            assertEquals("/rank/hot-comic-drama",request.url.encodedPath)
            assertEquals("2",request.url.queryParameter("page"))
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2,"updatedText":"9月22日已更新"}}};</script>"""+currentRank(payload)
        }
        val result=repo.comicRanking(2)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(23,"5746万热度"),result.ranking!!.positions["123"])
        assertEquals(5,result.ranking.totalPages)
        assertEquals("9月22日已更新",result.ranking.updatedText)
        assertTrue(result.hasMore)
        assertFalse(result.items.single().toJson().has("rank"))
    }

    @Test fun missingRankIsDerivedFromOrderAndLastPageStops() {
        val result=repository { currentRank(rankPayload(5,5)) }.comicRanking(5)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(5,""),result.ranking!!.positions["123"])
        assertFalse(result.hasMore)
    }

    @Test fun renderedRankingPreservesPageTwoRankAndHeat() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2,"updatedText":"今日已更新"}}};</script>
                <article aria-labelledby="rank-title-123"><span class="pc-badge-number-example">21</span>
                <h2 id="rank-title-123">漫剧</h2><p class="pc-metrics-example"><span>5746万热度</span></p></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-comic-drama">1</a><span aria-current="page">2</span><a href="/rank/hot-comic-drama?page=5">5</a></nav>"""
        }
        val result=repo.comicRanking(2)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(21,"5746万热度"),result.ranking!!.positions["123"])
        assertEquals(5,result.ranking.totalPages)
        assertEquals("今日已更新",result.ranking.updatedText)
    }

    @Test fun rankingRejectsUnexpectedPageInsteadOfShowingWrongRanks() {
        val repo=repository { currentRank(rankPayload(1,5)) }
        assertThrows(IOException::class.java) { repo.comicRanking(2) }
        assertThrows(IllegalArgumentException::class.java) { repo.comicRanking(0) }
    }

    @Test fun renderedLastPageCountsCurrentPageWithoutAnAnchor() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":5}}};</script>
                <article aria-labelledby="rank-title-123"><span class="pc-badge-number-example">81</span><h2 id="rank-title-123">漫剧</h2></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-comic-drama?page=4">4</a><span aria-current="page">5</span></nav>"""
        }
        val result=repo.comicRanking(5)
        assertEquals(5,result.ranking!!.totalPages)
        assertFalse(result.hasMore)
    }

    @Test fun comicHomeUsesDedicatedRankAndRealPagination() {
        val repo=repository { request ->
            assertEquals("/rank/hot-comic-drama",request.url.encodedPath)
            assertEquals("2",request.url.queryParameter("page"))
            currentRank(rankPayload(2,2))
        }
        val page=repo.home(2,ContentType.COMIC)
        assertEquals("测试漫剧",page.items.single().title)
        assertEquals("https://example.com/cover.jpg",page.items.single().cover)
        assertEquals("全 12 集",page.items.single().badge)
        assertFalse(page.hasMore)
    }

    @Test fun legacyRankIsStillSupported() {
        val repo=repository { script("r",JSONArray().put("rank_hot-comic-drama/page").put("content").put(rankPayload())) }
        assertTrue(repo.home(1,ContentType.COMIC).hasMore)
    }

    @Test fun comicHomeDoesNotUseUnrelatedRankData() {
        val repo=repository { script("r",JSONArray().put("rank_hot-real-drama/page").put("content").put(rankPayload())) }
        assertThrows(IOException::class.java) { repo.home(1,ContentType.COMIC) }
    }

    @Test fun rejectedComicRankRemainsAnError() {
        val repo=repository { currentRank(rankPayload().put("isSuccess",false)) }
        assertThrows(IOException::class.java) { repo.home(1,ContentType.COMIC) }
    }

    @Test fun renderedComicRankWorksWhenStreamedPayloadIsMissing() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2}}};</script>
                <article aria-labelledby="rank-title-123"><a href="/detail?series_id=123"><img src="https://example.com/a.jpg"></a>
                <h2 id="rank-title-123">测试 &amp; 漫剧</h2><p class="pc-description-example">简介</p><p class="pc-metrics-example">123万热度</p></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-comic-drama?page=1">1</a><span aria-current="page">2</span><a href="/rank/hot-comic-drama?page=3">3</a></nav>"""
        }
        val page=repo.home(2,ContentType.COMIC)
        assertEquals("测试 & 漫剧",page.items.single().title)
        assertEquals("https://example.com/a.jpg",page.items.single().cover)
        assertTrue(page.hasMore)
    }

    @Test fun comicSearchCarriesCursorAndKeepsTypesAndQueriesSeparate() {
        val requests=mutableListOf<Request>()
        val repo=repository { request ->
            requests+=request
            if(request.url.host.endsWith("fqnovel.com")) JSONObject().put("code",0).put("search_tabs",JSONArray().put(searchTab(19,"999")
                .put("search_id","comic-session").put("passback","comic-cursor").put("next_offset",25))).toString()
            else """{"searchList":[{"video_data":{"series_id":"333","series_name":"漫画网站结果","genre":"205"}}]}"""
        }
        repo.search("修仙",1,ContentType.COMIC)
        repo.search("修仙",2,ContentType.COMIC)
        assertEquals("comic-session",requests[1].url.queryParameter("search_id"))
        assertEquals("comic-cursor",requests[1].url.queryParameter("passback"))
        assertEquals("25",requests[1].url.queryParameter("offset"))
        repo.search("重生",2,ContentType.COMIC)
        assertEquals("2",requests[2].url.queryParameter("page"))
        assertEquals(3,requests.size)
    }

    @Test fun expiredCursorRequestsFreshSearchInsteadOfDuplicateOffsetPage() {
        var now=0L
        var requests=0
        val repo=repository(clock={ now }) { request ->
            requests++
            if(request.url.host.endsWith("fqnovel.com")) JSONObject().put("code",0).put("search_tabs",JSONArray().put(searchTab(19,"999")
                .put("search_id","session").put("passback","cursor"))).toString()
            else """{"searchList":[{"video_data":{"series_id":"444","series_name":"漫画兜底","genre":"205"}}]}"""
        }
        repo.search("修仙",1,ContentType.COMIC)
        now=300001L
        assertEquals("444",repo.search("修仙",2,ContentType.COMIC).items.single().id)
        assertEquals(2,requests)
        repo.search("修仙",1,ContentType.COMIC)
        repo.search("修仙",2,ContentType.COMIC)
        assertEquals(4,requests)
    }

    @Test fun comicSearchFallsBackToWebsiteAndKeepsOnlyComicGenreRows() {
        val repo=repository { request ->
            if(request.url.host.endsWith("fqnovel.com")) ""
            else """{"searchList":[{"video_data":{"series_id":"111","series_name":"短剧测试","genre":"203"}},{"video_data":{"series_id":"222","series_name":"漫画测试","genre":"205"}}]}"""
        }
        val result=repo.search("修仙",1,ContentType.COMIC)
        assertEquals(listOf("222"),result.items.map { it.id })
        // 站点没有 hasMore 时不再提示翻页
        assertFalse(result.hasMore)
    }

    @Test fun multiSeasonSearchFillsMissingSeasonsFromSuggestions() {
        val repo=repository { request ->
            val url=request.url
            val keyword=url.queryParameter("query").orEmpty()
            when {
                url.host.endsWith("fqnovel.com") && url.encodedPath.contains("suggest") ->
                    JSONObject().put("code",0).put("data",JSONObject().put("query_result",JSONArray().put("测试剧第二季"))).toString()
                url.host.endsWith("fqnovel.com") -> ""
                keyword.contains("第二季") ->
                    """{"searchList":[{"video_data":{"series_id":"222","series_name":"测试剧第二季","genre":"205"}}]}"""
                else ->
                    """{"searchList":[{"video_data":{"series_id":"111","series_name":"测试剧第一季","genre":"205"}},{"video_data":{"series_id":"113","series_name":"测试剧第三季","genre":"205"}}]}"""
            }
        }
        val result=repo.search("测试剧",1,ContentType.COMIC)
        assertEquals(listOf("111","222","113"),result.items.map { it.id })
    }

    @Test fun shortSearchFallsBackToWebsiteAndKeepsOnlyShortGenreRows() {
        val repo=repository { request ->
            if(request.url.host.endsWith("fqnovel.com")) ""
            else """{"searchList":[{"video_data":{"series_id":"111","series_name":"短剧测试","genre":"203"}},{"video_data":{"series_id":"222","series_name":"漫画测试","genre":"205"}}]}"""
        }
        val result=repo.search("总裁",1,ContentType.SHORT)
        assertEquals(listOf("111"),result.items.map { it.id })
    }

    @Test fun websiteSearchPaginatesWithSitePageAndHasMore() {
        val pages=mutableListOf<String>()
        val repo=repository { request ->
            if(request.url.host.endsWith("fqnovel.com")) ""
            else {
                assertEquals("/api/search/result/",request.url.encodedPath)
                assertEquals("修仙",request.url.queryParameter("query"))
                val page=request.url.queryParameter("page").orEmpty()
                pages+=page
                """{"searchList":[{"video_data":{"series_id":"90$page","series_name":"网站结果第${page}页","genre":"205"}}],"totalPages":2,"hasMore":${page=="1"}}"""
            }
        }
        val first=repo.search("修仙",1,ContentType.COMIC)
        assertEquals(listOf("901"),first.items.map { it.id })
        assertTrue(first.hasMore)
        val second=repo.search("修仙",2,ContentType.COMIC)
        assertEquals(listOf("902"),second.items.map { it.id })
        assertFalse(second.hasMore)
        assertEquals(listOf("1","2"),pages)
    }

    @Test fun shortHomeStillUsesCategoryAndStopsOnLastPage() {
        val repo=repository { request ->
            assertEquals("1",request.url.queryParameter("tab"))
            """<script>_ROUTER_DATA={"loaderData":{"category_$":{"recommendList":[{"series_id":"456","series_name":"测试短剧"}],"pagination":{"totalPages":1}}}};</script>"""
        }
        val result=repo.home(1,ContentType.SHORT)
        assertEquals("456",result.items.single().id)
        assertFalse(result.hasMore)
    }

    private fun searchTab(type: Int,id: String) = JSONObject().put("tab_type",type).put("has_more",true)
        .put("data",JSONArray().put(JSONObject().put("video_data",JSONArray().put(JSONObject()
            .put("series_id",id).put("title","测试剧集").put("episode_cnt",8)))))

    @Test fun comicSearchRequestsAndSelectsOnlyComicTab() {
        var requests=0
        val repo=repository { request ->
            requests++
            assertEquals("19",request.url.queryParameter("tab_type"))
            assertEquals(if(requests==1) "0" else "20",request.url.queryParameter("offset"))
            assertEquals("修仙",request.url.queryParameter("query"))
            JSONObject().put("code",0).put("search_tabs",JSONArray()
                .put(searchTab(11,"111")).put(searchTab(19,"999"))).toString()
        }
        repo.search("修仙",1,ContentType.COMIC)
        assertEquals(listOf("999"),repo.search("修仙",2,ContentType.COMIC).items.map { it.id })
    }

    @Test fun emptyComicSearchDoesNotFallBackToShortResults() {
        var requests=0
        val repo=repository { requests++; """{"code":0,"search_tabs":[{"tab_type":19,"data":[],"has_more":false}]}""" }
        assertTrue(repo.search("无结果",1,ContentType.COMIC).items.isEmpty())
        assertEquals(1,requests)
    }

    @Test fun missingComicTabFallsBackToWebsiteWithoutShortResults() {
        var requests=0
        val repo=repository { request ->
            requests++
            if(request.url.host.endsWith("fqnovel.com")) """{"code":0,"search_tabs":[{"tab_type":11,"data":[]}]}"""
            else """{"searchList":[{"video_data":{"series_id":"111","series_name":"短剧测试","genre":"203"}}]}"""
        }
        assertTrue(repo.search("修仙",1,ContentType.COMIC).items.isEmpty())
        assertEquals(2,requests)
    }

    @Test fun shortSearchKeepsWebsiteFallback() {
        val paths=mutableListOf<String>()
        val repo=repository { request ->
            paths+=request.url.encodedPath
            if(paths.size<=2) throw IOException("offline")
            """{"searchList":[{"video_data":{"series_id":"456","series_name":"测试短剧","genre":"203"}}]}"""
        }
        assertEquals("456",repo.search("短剧",1,ContentType.SHORT).items.single().id)
        assertEquals(3,paths.size)
    }

    private fun relatedItem(id: String,name: String)=JSONObject().put("series_id",id).put("series_name",name)
        .put("series_cover","https://example.com/$id.jpg").put("episode_cnt",10).put("episode_right_text","全 10 集")
    private fun detailHtml(series: JSONObject,recommended: JSONArray): String {
        val recommendations=JSONObject().put("videoList",JSONArray())
            .put("recommendationGroups",JSONObject().put("related_list",recommended))
        val args=JSONArray().put("detail_page").put(JSONArray().put(JSONObject().put("key","recommendations")
            .put("routerDataFnName","p").put("routerDataFnArgs",JSONArray().put(recommendations.toString()))))
        val attribute=args.toString().replace("&","&amp;").replace("\"","&quot;")
        return """<script>_ROUTER_DATA={"loaderData":{"detail_page":{"seriesDetail":$series}}};</script><script data-fn-args="$attribute"></script>"""
    }

    @Test fun detailRelatedHoistsSameNameSeasonsAndSkipsCurrentSeries() {
        val series=JSONObject().put("series_id","123").put("series_name","测试剧").put("vid_list",JSONArray().put("900").put("901"))
        val html=detailHtml(series,JSONArray()
            .put(relatedItem("111","别人的剧"))
            .put(relatedItem("222","测试剧"))
            .put(relatedItem("333","测试剧 第二季"))
            .put(relatedItem("123","测试剧")))
        val detail=repository { html }.detail("123")
        assertEquals(listOf("900","901"),detail.episodes)
        assertEquals(listOf("222","333","111"),detail.related.map { it.id })
    }

    @Test fun detailWithoutRecommendationPayloadStillLoadsEpisodes() {
        val html="""<script>_ROUTER_DATA={"loaderData":{"detail_page":{"seriesDetail":{"series_id":"123","series_name":"测试剧","vid_list":["900","901"]}}}};</script>"""
        val detail=repository { html }.detail("123")
        assertEquals(listOf("900","901"),detail.episodes)
        assertTrue(detail.related.isEmpty())
    }

    @Test fun detailCollectsSameNameSeasonsFromWebsiteSearchOrderedBySeason() {
        val series=JSONObject().put("series_id","123").put("series_name","测试剧 第二季").put("vid_list",JSONArray().put("900"))
        val repo=repository { request ->
            if(request.url.encodedPath=="/detail") detailHtml(series,JSONArray())
            else """{"searchList":[
                {"video_data":{"series_id":"222","series_name":"测试剧 第三季","genre":"203"}},
                {"video_data":{"series_id":"111","series_name":"别人的剧","genre":"203"}},
                {"video_data":{"series_id":"333","series_name":"测试剧 第一季","genre":"203"}},
                {"video_data":{"series_id":"444","series_name":"测试剧 第十季","genre":"203"}},
                {"video_data":{"series_id":"123","series_name":"测试剧 第二季","genre":"203"}}]}"""
        }
        val detail=repo.detail("123")
        assertEquals(listOf("333","222","444"),detail.sameName.map { it.id })
    }

    @Test fun detailFillsMissingSeasonsFromSuggestionsLikeSearch() {
        val series=JSONObject().put("series_id","123").put("series_name","测试剧 第一季").put("vid_list",JSONArray().put("900"))
        val repo=repository { request ->
            when {
                request.url.encodedPath=="/detail" -> detailHtml(series,JSONArray())
                request.url.host.endsWith("fqnovel.com") -> JSONObject().put("code",0)
                    .put("data",JSONObject().put("query_result",JSONArray().put("测试剧 第二季").put("测试剧 第三季"))).toString()
                else -> {
                    val keyword=request.url.queryParameter("query").orEmpty()
                    val row=when(keyword) {
                        "测试剧" -> """{"video_data":{"series_id":"123","series_name":"测试剧 第一季","genre":"203"}}"""
                        "测试剧 第二季" -> """{"video_data":{"series_id":"222","series_name":"测试剧 第二季","genre":"203"}}"""
                        "测试剧 第三季" -> """{"video_data":{"series_id":"333","series_name":"测试剧 第三季","genre":"203"}}"""
                        else -> ""
                    }
                    if(row.isEmpty()) """{"searchList":[]}""" else """{"searchList":[${row}]}"""
                }
            }
        }
        // 网站只返回季 1，联想给出的季 2、季 3 需要回查补齐
        assertEquals(listOf("222","333"),repo.detail("123").sameName.map { it.id })
    }

    @Test fun seasonFillCacheMissesRecomputeWhenAnotherSeasonPageKnowsFewerSeasons() {
        // 2026-11-08 用户实测：第 1 季详情页 related 聚合了全季，补齐管线算出「无缺季」的空结果被缓存；
        // 跳到其它季页（related 只带部分季）后复用这条空缓存，缺的季不再补齐。
        // 修复：空结果只在「已知季数一致」时才可信，规模变化触发重算。
        val s1=JSONObject().put("series_id","1").put("series_name","测试剧 第一季").put("vid_list",JSONArray().put("900"))
        val s2=JSONObject().put("series_id","2").put("series_name","测试剧 第二季").put("vid_list",JSONArray().put("901"))
        val repo=repository { request ->
            when {
                request.url.encodedPath=="/detail" ->
                    if(request.url.queryParameter("series_id")=="1")
                        detailHtml(s1,JSONArray().put(relatedItem("2","测试剧 第二季")).put(relatedItem("3","测试剧 第三季")))
                    else detailHtml(s2,JSONArray().put(relatedItem("1","测试剧 第一季")))
                request.url.encodedPath=="/api/search/result/" ->
                    """{"searchList":[{"video_data":{"series_id":"3","series_name":"测试剧 第三季","genre":"203"}}]}"""
                else -> """{"code":0,"data":{"query_result":[]}}"""
            }
        }
        // 第 1 季页：related 自带 2、3 季，搜索结果里的第 3 季在已知集合内被过滤，补齐结果为空并被缓存。
        assertEquals(listOf("2","3"),repo.detail("1").sameName.map { it.id })
        // 第 2 季页：related 只带第 1 季，已知季数从 3 变 2，空缓存不可信，重算补出第 3 季。
        assertEquals(listOf("1","3"),repo.detail("2").sameName.map { it.id })
    }

    @Test fun seasonListKeepsSeasonsDiscoveredOnAnotherSeasonPage() {
        // 2026-11-08 用户实测（谁让这个文盲修仙的 第六季）：站方 related 已不再聚合同系列季，
        // 「系列 N 季」完全由搜索补齐管线构建。第 6 季页补齐出 1-5 季入缓存后，
        // 点进第 5 季页时已知集合只有第 5 季，若缓存只存差集，过滤后只剩 1-4 季，第 6 季消失。
        // 修复：缓存存「已知季并集」，任何季页减去自己已有的季即可拿回全部。
        fun season(id: String,title: String)=JSONObject().put("series_id",id).put("series_name",title).put("vid_list",JSONArray().put("90$id"))
        val page=mapOf(
            "6" to detailHtml(season("6","测试剧第六季"),JSONArray()),
            "5" to detailHtml(season("5","测试剧第五季"),JSONArray()),
        )
        val repo=repository { request ->
            when {
                request.url.encodedPath=="/detail" -> page[request.url.queryParameter("series_id")].orEmpty()
                request.url.host.endsWith("fqnovel.com") -> JSONObject().put("code",0).put("data",JSONObject()
                    .put("query_result",JSONArray().put("测试剧第二季").put("测试剧第三季")
                        .put("测试剧第四季").put("测试剧第五季"))).toString()
                else -> {
                    val keyword=request.url.queryParameter("query").orEmpty()
                    val row=when(keyword) {
                        "测试剧" -> """{"video_data":{"series_id":"1","series_name":"测试剧第一季","genre":"203"}}"""
                        "测试剧第二季" -> """{"video_data":{"series_id":"2","series_name":"测试剧第二季","genre":"203"}}"""
                        "测试剧第三季" -> """{"video_data":{"series_id":"3","series_name":"测试剧第三季","genre":"203"}}"""
                        "测试剧第四季" -> """{"video_data":{"series_id":"4","series_name":"测试剧第四季","genre":"203"}}"""
                        "测试剧第五季" -> """{"video_data":{"series_id":"5","series_name":"测试剧第五季","genre":"203"}}"""
                        else -> ""
                    }
                    if(row.isEmpty()) """{"searchList":[]}""" else """{"searchList":[${row}]}"""
                }
            }
        }
        // 第 6 季页：补齐出 1-5 季，共 6 季。
        assertEquals(listOf("1","2","3","4","5"),repo.detail("6").sameName.map { it.id })
        // 第 5 季页：related 为空、已知只有自己，仍应拿回第 6 季而不是把缓存里减没了。
        assertEquals(listOf("1","2","3","4","6"),repo.detail("5").sameName.map { it.id })
    }

    private fun currentRealRank(payload: JSONObject) = script("mergeLoaderData",JSONArray()
        .put("rank_hot-real-drama/page").put(JSONArray().put(JSONObject().put("key","content")
            .put("routerDataFnName","p").put("routerDataFnArgs",JSONArray().put(payload.toString())))))

    @Test fun realRankingParsesTheShortDramaBoard() {
        val payload=rankPayload(1,5)
        payload.getJSONArray("rankList").getJSONObject(0).put("rank",1).put("heatText","9999万热度")
        val repo=repository { request ->
            assertEquals("/rank/hot-real-drama",request.url.encodedPath)
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-real-drama/page":{"rankKey":"real","pageNum":1,"updatedText":"今日已更新"}}};</script>"""+currentRealRank(payload)
        }
        val result=repo.realRanking(1)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(1,"9999万热度"),result.ranking!!.positions["123"])
        assertEquals("今日已更新",result.ranking!!.updatedText)
        assertTrue(result.hasMore)
    }

    @Test fun realRankingRejectsTheComicBoardPayload() {
        val repo=repository { currentRank(rankPayload()) }
        assertThrows(IOException::class.java) { repo.realRanking(1) }
    }

    @Test fun renderedRealRankingWorksWithoutStreamedPayload() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-real-drama/page":{"rankKey":"real","pageNum":2,"updatedText":"今日已更新"}}};</script>
                <article aria-labelledby="rank-title-456"><span class="pc-badge-number-example">22</span>
                <h2 id="rank-title-456">短剧</h2><p class="pc-metrics-example"><span>100万热度</span></p></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-real-drama">1</a><span aria-current="page">2</span><a href="/rank/hot-real-drama?page=5">5</a></nav>"""
        }
        val result=repo.realRanking(2)
        assertEquals(RankPosition(22,"100万热度"),result.ranking!!.positions["456"])
        assertEquals(5,result.ranking!!.totalPages)
        assertEquals("今日已更新",result.ranking!!.updatedText)
    }

    private fun selector(slug: String,name: String,vararg ids: String)=JSONObject().put("selector_item_id",slug).put("show_name",name)
        .put("category_json_ids",JSONArray().apply { ids.forEach { put(it) } })

    private fun categoryHtml(recommendList: JSONArray = JSONArray(),selectorList: JSONArray? = null,apiIds: JSONArray? = null,totalPages: Int = 1): String {
        val section=JSONObject().put("recommendList",recommendList).put("pagination",JSONObject().put("totalPages",totalPages))
        if(selectorList!=null) section.put("selectorList",selectorList)
        if(apiIds!=null) section.put("apiQuery",JSONObject().put("category_json_ids",apiIds))
        return """<script>_ROUTER_DATA={"loaderData":{"category_$":${section}}};</script>"""
    }

    @Test fun genresComeFromTheCategorySelectorAndAreCached() {
        var requests=0
        val repo=repository { request ->
            requests++
            assertEquals("/category/real-drama",request.url.encodedPath)
            categoryHtml(selectorList=JSONArray().put(JSONObject().put("row_name","全部分类").put("items",JSONArray()
                .put(selector("romance","爱情","5000")).put(selector("costume","古装","5007")))))
        }
        val genres=repo.genres(ContentType.SHORT)
        assertEquals(listOf("romance","costume"),genres.map { it.slug })
        assertEquals(listOf("爱情","古装"),genres.map { it.name })
        assertEquals(listOf("5000"),genres.first().ids)
        assertEquals(genres,repo.genres(ContentType.SHORT))
        assertEquals(1,requests)
    }

    @Test fun genreHomeUsesTheSlugPathAndValidatesAppliedIds() {
        val rows=JSONArray().put(JSONObject().put("series_id","456").put("series_name","爱情短剧"))
        val repo=repository { request ->
            assertEquals("/category/real-drama/romance",request.url.encodedPath)
            assertEquals("2",request.url.queryParameter("page"))
            categoryHtml(rows,apiIds=JSONArray().put("5000"),totalPages=9)
        }
        val page=repo.home(2,ContentType.SHORT,Genre("romance","爱情",listOf("5000")))
        assertEquals("456",page.items.single().id)
        assertTrue(page.hasMore)
    }

    @Test fun genreHomeFailsWhenTheSiteIgnoresTheFilter() {
        val repo=repository { categoryHtml(apiIds=JSONArray()) }
        assertThrows(IOException::class.java) { repo.home(1,ContentType.SHORT,Genre("romance","爱情",listOf("5000"))) }
    }

    @Test fun comicGenresUseTheComicCategoryPath() {
        val rows=JSONArray().put(JSONObject().put("series_id","789").put("series_name","脑洞漫剧"))
        val repo=repository { request ->
            assertEquals("/category/comic-drama/creative",request.url.encodedPath)
            categoryHtml(rows,apiIds=JSONArray().put("755"))
        }
        assertEquals("789",repo.home(1,ContentType.COMIC,Genre("creative","脑洞",listOf("755"))).items.single().id)
    }

    @Test fun suggestParsesQueryResultWordsAndDeduplicates() {
        val repo=repository { request ->
            assertEquals("/reading/bookapi/search/suggest/v",request.url.encodedPath)
            assertEquals("jywn",request.url.queryParameter("query"))
            """{"code":0,"data":{"query_key":"jywn","query_result":["家有五女"," 今夜为你无眠 ","家有五女",""]}}"""
        }
        assertEquals(listOf("家有五女","今夜为你无眠"),repo.suggest(" JYWN "))
    }

    @Test fun suggestFallsBackToEmptyOnBlankBodyErrorCodeOrNetworkFailure() {
        var requests=0
        val blank=repository { requests++; "" }
        assertTrue(blank.suggest("jywn").isEmpty())
        val rejected=repository { requests++; """{"code":-1}""" }
        assertTrue(rejected.suggest("jywn").isEmpty())
        val offline=repository { requests++; throw IOException("offline") }
        assertTrue(offline.suggest("jywn").isEmpty())
        assertEquals(3,requests)
    }

    @Test fun suggestSkipsNonAlphabetInputWithoutARequest() {
        var requests=0
        val repo=repository { requests++; """{"code":0,"data":{"query_result":["a"]}}""" }
        assertTrue(repo.suggest("").isEmpty())
        assertTrue(repo.suggest("家有").isEmpty())
        assertTrue(repo.suggest("a".repeat(21)).isEmpty())
        assertEquals(0,requests)
    }

    @Test fun seriesTitleKeepsOnlyTheName() {
        // 实测：详情接口把简介拼进了 series_name，标题栏只有一行高，第二行被裁成半截字
        assertEquals("《黄石第五季》",seriesTitle("《黄石第五季》\n蒙大拿荒野深处，达顿家族世代守护的黄石牧场迎来终极考验"))
        assertEquals("狂飙",seriesTitle("狂飙"))
        assertEquals("狂飙",seriesTitle("  狂飙  "))
        // 首行是空的时候往下一行找，不能整个丢空
        assertEquals("狂飙",seriesTitle("\n\n狂飙\n第二行"))
        // 行内多余空白压成一个空格，免得标题栏里出现大段空隙
        assertEquals("The Good Doctor",seriesTitle("The  Good\tDoctor"))
        assertEquals("",seriesTitle(""))
        // 超长标题截断，避免撑出标题栏
        assertEquals(48,seriesTitle("剧".repeat(120)).length)
    }
}
