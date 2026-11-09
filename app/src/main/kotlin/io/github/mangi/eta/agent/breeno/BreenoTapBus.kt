package io.github.mangi.eta.agent.breeno

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** 小布旁路回包：来自小布进程内 hook 的一次完整回答。 */
internal data class BreenoTapEvent(
    val query: String,
    val roomId: String,
    val text: String,
    /** 产生时刻；调用方用来过滤「上一次」的旧回包。 */
    val at: Long,
)

/**
 * 小布进程 → Eta 主进程的单向事件总线。
 *
 * 回包量小（一次对话一条），用 replay 兜住「订阅晚于回包」的竞态；
 * 真正的大文本仍然只存在内存里，不落盘。
 */
internal object BreenoTapBus {
    // 不能 replay：调用方每次都按「本次提问时刻」过滤，重放旧事件会让每轮拿到同一条回答。
    private val mutableEvents = MutableSharedFlow<BreenoTapEvent>(
        replay = 0,
        extraBufferCapacity = 16,
    )

    val events: SharedFlow<BreenoTapEvent> = mutableEvents

    fun publish(event: BreenoTapEvent) {
        mutableEvents.tryEmit(event)
    }
}
