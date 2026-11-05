// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.ref.WeakReference
import java.util.PriorityQueue
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/** Page-owned artwork requests. Public methods are called on the UI thread; decoding stays on workers. */
class ArtworkLoader(context: Context) {
    companion object {
        const val PRIORITY_FOCUSED = 0
        const val PRIORITY_VISIBLE = 1
        const val PRIORITY_PREFETCH = 2
        private const val MAX_PENDING = 24
        private const val MAX_BYTES = 4 * 1024 * 1024
    }

    private data class Key(val url: String, val width: Int, val height: Int)
    private class Binding(view: ImageView, val owner: Long, val url: String, var priority: Int,
                          val width: Int, val height: Int) {
        val view = WeakReference(view)
        var key: Key? = null
        var applied: Key? = null
        var failed: Key? = null
        lateinit var layout: View.OnLayoutChangeListener
        lateinit var attach: View.OnAttachStateChangeListener
    }
    private class Job(val key: Key, val owner: Long, var priority: Int, val order: Long) {
        val listeners = linkedSetOf<Binding>()
        @Volatile var cancelled = false
        @Volatile var call: Call? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private val lowRam = context.applicationContext.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true
    private val cacheBytes = (if (lowRam) 4 else 8) * 1024 * 1024
    private val cache = object : LruCache<Key, Bitmap>(cacheBytes) {
        override fun sizeOf(key: Key, value: Bitmap) = value.allocationByteCount
    }
    // v2：代取链路现在落盘的是转码后的 JPEG（v1 缓存里可能存着手机解不了的 HEIC 原始字节），升版本作废旧缓存
    private val disk = ArtworkCache(File(context.applicationContext.cacheDir, "artwork-v2"))
    // This client owns its dispatcher and pool; cancelling artwork never cancels video or catalog calls.
    private val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build()
    private val workers = Executors.newFixedThreadPool(if (lowRam) 1 else 2) { runnable ->
        Thread(runnable, "hongguotv-artwork").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val signal = Object()
    private val pending = PriorityQueue<Job>(compareBy<Job> { it.priority }.thenBy { it.order })
    private val jobs = mutableMapOf<Key, Job>()
    private val bindings = WeakHashMap<ImageView, Binding>()
    private var owner = 0L
    private var order = 0L
    @Volatile private var closed = false

    init { repeat(if (lowRam) 1 else 2) { workers.execute(::work) } }

    /** Size defaults to the laid-out content bounds, including the detail page's portrait artwork. */
    fun load(view: ImageView, url: String, priority: Int = PRIORITY_VISIBLE, widthPx: Int = 0, heightPx: Int = 0) {
        if (closed) return
        val previous = bindings[view]
        if (previous != null && previous.owner == owner && previous.url == url && previous.width == widthPx && previous.height == heightPx) {
            previous.priority = priority.coerceIn(PRIORITY_FOCUSED, PRIORITY_PREFETCH)
            previous.failed = null
            request(previous)
            return
        }
        if (previous != null) unbind(previous)
        view.setImageDrawable(null)
        if (url.isBlank()) return
        val binding = Binding(view, owner, url, priority.coerceIn(PRIORITY_FOCUSED, PRIORITY_PREFETCH), widthPx, heightPx)
        binding.layout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> request(binding) }
        binding.attach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { request(binding) }
            override fun onViewDetachedFromWindow(v: View) { unbind(binding) }
        }
        bindings[view] = binding
        view.addOnLayoutChangeListener(binding.layout)
        view.addOnAttachStateChangeListener(binding.attach)
        request(binding)
    }

    private fun request(binding: Binding) {
        val view = binding.view.get() ?: return
        if (closed || binding.owner != owner || bindings[view] !== binding) return
        val width = if (binding.width > 0) binding.width else view.width - view.paddingLeft - view.paddingRight
        val height = if (binding.height > 0) binding.height else view.height - view.paddingTop - view.paddingBottom
        if (width <= 0 || height <= 0) return
        // A malformed layout cannot ask an old TV to allocate a full-screen, multi-megapixel thumbnail.
        val scale = min(1.0, 1024.0 / max(width, height))
        val key = Key(binding.url, max(1, (width * scale).toInt()), max(1, (height * scale).toInt()))
        if (binding.key != key) { detachJob(binding); binding.key = key; binding.failed = null }
        if (binding.applied == key || binding.failed == key) return
        cache.get(key)?.let { bitmap -> view.setImageBitmap(bitmap); binding.applied = key; return }
        var cancelled: Job? = null
        synchronized(signal) {
            jobs[key]?.let { job ->
                job.listeners += binding
                if (binding.priority < job.priority) {
                    val queued = pending.remove(job)
                    job.priority = binding.priority
                    if (queued) pending.add(job)
                }
                return
            }
            if (pending.size >= MAX_PENDING) {
                val worst = pending.maxWithOrNull(compareBy<Job> { it.priority }.thenBy { -it.order })!!
                if (worst.priority < binding.priority) return
                cancelLocked(worst)
                cancelled = worst
            }
            val job = Job(key, owner, binding.priority, ++order)
            job.listeners += binding
            jobs[key] = job
            pending += job
            signal.notifyAll()
        }
        cancelled?.call?.cancel()
    }

    private fun cancelLocked(job: Job) {
        job.cancelled = true
        pending.remove(job)
        if (jobs[job.key] === job) jobs.remove(job.key)
        job.listeners.clear()
    }

    private fun detachJob(binding: Binding) {
        val key = binding.key ?: return
        val cancelled = synchronized(signal) {
            jobs[key]?.takeIf { it.owner == binding.owner }?.let { job ->
                job.listeners.remove(binding)
                if (job.listeners.isEmpty()) { cancelLocked(job); job } else null
            }
        }
        cancelled?.call?.cancel()
    }

    private fun unbind(binding: Binding) {
        binding.view.get()?.let { view ->
            view.removeOnLayoutChangeListener(binding.layout)
            view.removeOnAttachStateChangeListener(binding.attach)
            if (bindings[view] === binding) bindings.remove(view)
        }
        detachJob(binding)
        binding.view.clear()
    }

    /** Release every old-page listener and bitmap binding, including queued and in-flight requests. */
    fun cancelPage(clearMemory: Boolean = false) {
        owner++
        val old = bindings.values.toList()
        old.forEach { binding -> binding.view.get()?.setImageDrawable(null); unbind(binding) }
        val cancelled = synchronized(signal) {
            jobs.values.toList().also { list -> list.forEach(::cancelLocked); signal.notifyAll() }
        }
        cancelled.forEach { it.call?.cancel() }
        if (clearMemory) cache.evictAll()
    }

    fun cancelAll(clearMemory: Boolean = false) = cancelPage(clearMemory)

    @Suppress("DEPRECATION") // Android 8 devices still report the running-memory pressure levels.
    fun trimMemory(level: Int) {
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> cache.evictAll()
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> cache.trimToSize(cacheBytes / 2)
        }
    }

    fun close() {
        if (closed) return
        closed = true
        cancelPage(clearMemory = true)
        synchronized(signal) { signal.notifyAll() }
        workers.shutdownNow()
        // TLS socket pool disposal can block and must not happen on the UI thread.
        Thread({
            http.dispatcher.cancelAll()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }, "hongguotv-artwork-cleanup").start()
    }

    private fun work() {
        while (!closed) {
            val job = try {
                synchronized(signal) {
                    while (pending.isEmpty() && !closed) signal.wait()
                    if (closed) null else pending.poll()
                }
            } catch (_: InterruptedException) { return } ?: return
            val bitmap = try {
                if (job.cancelled) null else cache.get(job.key) ?: fetch(job)
            } catch (_: Exception) { null } catch (_: OutOfMemoryError) { cache.evictAll(); null }
            val listeners = synchronized(signal) {
                if (jobs[job.key] === job) jobs.remove(job.key)
                if (!job.cancelled && !closed && bitmap != null) cache.put(job.key, bitmap)
                job.listeners.toList().also { job.listeners.clear() }
            }
            if (!job.cancelled && !closed) main.post {
                if (job.owner == owner && !closed) {
                    listeners.forEach { binding ->
                        val view = binding.view.get()
                        if (view != null && bindings[view] === binding && binding.key == job.key) {
                            if (bitmap != null) { view.setImageBitmap(bitmap); binding.applied = job.key }
                            else binding.failed = job.key
                        }
                    }
                    refillVisible()
                }
            }
        }
    }

    // Dropped preloads must not leave a visible card blank when a queue slot becomes free.
    private fun refillVisible() {
        val visible = Rect()
        bindings.values.toList().filter { binding ->
            binding.applied != binding.key && binding.failed != binding.key && binding.view.get()?.getGlobalVisibleRect(visible) == true
        }.sortedBy { it.priority }.take(MAX_PENDING).forEach(::request)
    }

    /**
     * 给手机遥控代取封面。手机那头过不了部分 CDN 的 Referer/UA 校验，也不该自己去猜地址，
     * 所以统一由电视转发：磁盘里已经有就直接回，没有才同步下一次网并回填缓存。
     * 会阻塞，只在 Hub 的请求线程里调用。
     */
    fun bytes(url: String): ByteArray? {
        disk.get(url)?.let { return it }
        // 上限 1024 与 Hub 的 /cover 路由一致：云端签名封面地址偏长，卡太紧会被整段拒掉
        if(url.length > 1024 || !(url.startsWith("http://") || url.startsWith("https://"))) return null
        return runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                val body = response.body ?: return null
                if(!response.isSuccessful || body.contentLength() > MAX_BYTES) return null
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                body.byteStream().use { input ->
                    while(true) {
                        val count = input.read(buffer)
                        if(count < 0) break
                        if(out.size() + count > MAX_BYTES) return null
                        out.write(buffer, 0, count)
                    }
                }
                val data = toBrowserFormat(out.toByteArray().takeIf { it.isNotEmpty() } ?: return null)
                disk.put(url, data)
                data
            }
        }.getOrNull()
    }

    /** 浏览器 <img> 能直出的格式魔数：JPEG / PNG / GIF / WebP。 */
    private fun browserSafe(b: ByteArray): Boolean = when {
        b.size >= 3 && b[0]==0xFF.toByte() && b[1]==0xD8.toByte() && b[2]==0xFF.toByte() -> true
        b.size >= 4 && b[0]==0x89.toByte() && b[1]==0x50.toByte() && b[2]==0x4E.toByte() && b[3]==0x47.toByte() -> true
        b.size >= 3 && b[0]==0x47.toByte() && b[1]==0x49.toByte() && b[2]==0x46.toByte() -> true
        b.size >= 12 && b[0]==0x52.toByte() && b[1]==0x49.toByte() && b[2]==0x46.toByte() &&
            b[8]==0x57.toByte() && b[9]==0x45.toByte() && b[10]==0x42.toByte() && b[11]==0x50.toByte() -> true
        else -> false
    }

    /**
     * 红果云端的封面 CDN 返回 HEIC（`~tplv-noop.heic`），换扩展名要 JPEG 会被签名 403——
     * 而手机浏览器（Chrome/安卓 WebView）解不了 HEIC，海报必然全挂。安卓本机（API 28+）
     * 自带 HEIF 解码，电视侧显示一直正常；这里在代取链路上把非浏览器格式转码成 JPEG，
     * 磁盘缓存里也就直接存 JPEG，后续请求零开销。解码失败就原样返回，不比现在更差。
     */
    private fun toBrowserFormat(bytes: ByteArray): ByteArray {
        if(browserSafe(bytes)) return bytes
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
        bitmap.recycle()
        return out.toByteArray().takeIf { it.isNotEmpty() } ?: bytes
    }

    private fun fetch(job: Job): Bitmap? {
        val bytes = disk.get(job.key.url) ?: run {
            if (job.cancelled) return null
            val call = http.newCall(Request.Builder().url(job.key.url).build())
            job.call = call
            if (job.cancelled) { call.cancel(); return null }
            call.execute().use { response ->
                val body = response.body ?: return null
                if (!response.isSuccessful || body.contentLength() > MAX_BYTES) return null
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                body.byteStream().use { input ->
                    while (!job.cancelled) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (out.size() + count > MAX_BYTES) return null
                        out.write(buffer, 0, count)
                    }
                }
                if (job.cancelled) return null
                out.toByteArray().also { disk.put(job.key.url, it) }
            }
        }
        if (job.cancelled) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..32768 || bounds.outHeight !in 1..32768) return null
        // Fit the entire source into the measured target. Cropping at decode time cannot be
        // undone by ImageView.FIT_CENTER and used to remove most of a portrait poster.
        val fit = min(1.0, min(job.key.width.toDouble() / bounds.outWidth, job.key.height.toDouble() / bounds.outHeight))
        val width = max(1, (bounds.outWidth * fit).toInt())
        val height = max(1, (bounds.outHeight * fit).toInt())
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) sample *= 2
        if ((bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > (if (lowRam) 2 else 4) * 1024 * 1024) return null
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        if (job.cancelled) { decoded.recycle(); return null }
        val result = try { Bitmap.createScaledBitmap(decoded, width, height, true) }
        catch (problem: Throwable) { decoded.recycle(); throw problem }
        if (result !== decoded) decoded.recycle()
        if (job.cancelled) { result.recycle(); return null }
        return result
    }
}
