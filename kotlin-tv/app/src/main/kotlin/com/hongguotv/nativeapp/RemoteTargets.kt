package com.hongguotv.nativeapp

import com.hongguotv.core.EpisodeGroupDetail
import com.hongguotv.core.PlaybackSource
import com.hongguotv.core.RemoteCommand
import com.hongguotv.core.RemoteTarget

/**
 * 官方与三方两个播放器的统一入口。
 *
 * 用函数类型接住 Activity 的私有状态，而不是把 `player` / `detail` 开放成 public：
 * 遥控只需要「执行一条命令」「返回剧集列表」这两件事，其余一概不暴露。
 *
 * 官方播放器是一维 `episodeIndex`，三方是二维 `playGroup/playEpisode`。
 * 这里统一按二维上报——官方那一路就是一个只有「正片」的组，
 * 于是遥控代码里不会出现任何「是不是三方」的分支。
 */
class LambdaTarget(
    override val source: PlaybackSource,
    private val handler: (RemoteCommand) -> Boolean,
    private val episodesProvider: () -> List<EpisodeGroupDetail>
) : RemoteTarget {
    override fun apply(cmd: RemoteCommand): Boolean = handler(cmd)
    override fun episodes(): List<EpisodeGroupDetail> = episodesProvider()
}
