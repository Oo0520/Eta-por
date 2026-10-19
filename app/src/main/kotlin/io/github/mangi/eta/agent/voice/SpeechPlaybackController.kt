package io.github.mangi.eta.agent.voice

import android.content.Context
import android.os.SystemClock
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.model.SpeechCredentials
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.model.TtsProvider
import io.github.mangi.eta.data.repository.SpeechSettingsRepository
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal data class SpeechPlaybackState(
    val messageId: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
    /** 正在朗读片段在原文中的位置；null 表示这一段没能定位回原文。 */
    val highlight: IntRange? = null,
    /** 已开始朗读的片段序号（从 1 开始）与总片段数。 */
    val spokenChunks: Int = 0,
    val totalChunks: Int = 0,
)

internal class SpeechPlaybackController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val beforePlayback: () -> Unit = {},
    private val interruptOnScreenOff: Boolean = true,
    private val afterPlayback: () -> Unit = {},
    private val synthesize: suspend (SpeechSettings, SpeechCredentials, String, (ByteArray) -> Unit) -> Unit = ::synthesizeSpeech,
    /** 整篇单会话流式合成；为 null 时只用逐段请求。 */
    private val streamSynthesize: (suspend (SpeechSettings, SpeechCredentials, List<String>, (ByteArray) -> Unit) -> Unit)? = ::streamSpeech,
    private val createOutput: () -> SpeechAudioOutput = ::SpeechPcmOutput,
) {
    private val mutableState = MutableStateFlow(SpeechPlaybackState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var generation = 0L
    private var output: SpeechAudioOutput? = null
    private var lease: SpeechAudioLease? = null
    private var playbackActive = false

    /** 每条消息已经读完（或跳过）到 readable 文本的第几个字，用于续读，避免从头重念。 */
    private var lastRead: Pair<String, Int>? = null

    fun speak(
        messageId: String,
        text: String,
        settings: SpeechSettings? = null,
        credentials: SpeechCredentials? = null,
        /**
         * 同一条消息再次触发时接着上次读完的位置往下读，而不是从头重读。
         * 自动朗读会在回答的每个收尾时刻触发一次，必须传 true，否则一次回答会被反复从头念。
         */
        resume: Boolean = false,
    ) {
        stop()
        val session = generation
        mutableState.value = SpeechPlaybackState(messageId, loading = true)
        job = scope.launch(Dispatchers.Main.immediate) {
            try {
                lease = SpeechAudioLease(context, interruptOnScreenOff) { stop() }.also { it.acquire(playback = false) }
                val config = settings ?: SpeechSettingsRepository.settings()
                val secrets = credentials ?: SpeechSettingsRepository.credentials(context)
                validateSpeechSettings(config, secrets, synthesis = true)
                val readable = withContext(Dispatchers.Default) { SpeechText.readable(text) }
                if (readable.isBlank()) throw SpeechFailure(SpeechErrorCode.NO_SPEECH, "这条消息没有可朗读的正文")
                if (readable.length > 30_000) throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "正文过长，请选择较短的回答朗读")
                val from = if (resume) {
                    lastRead?.takeIf { it.first == messageId }?.second?.coerceIn(0, readable.length) ?: 0
                } else {
                    0
                }
                if (from >= readable.length) {
                    // 没有新内容可读：静默收工，不报错也不从头再念一遍。
                    stop()
                    return@launch
                }
                val pending = readable.substring(from)
                // 系统引擎走的是「文件合成」通道，有 30 秒墙钟看门狗（见 speakSystemChunk），
                // 所以给它更短的片段；网络合成是流式的，沿用默认上限。
                val chunkLimit = if (config.tts == TtsProvider.SYSTEM) SYSTEM_TTS_CHUNK_LIMIT else SpeechText.DEFAULT_LIMIT
                val chunks = SpeechText.chunks(pending, chunkLimit)
                val offsets = withContext(Dispatchers.Default) { chunkOffsets(pending, chunks) }
                // 片段定位失败时只丢高亮，不影响朗读本身。
                val ranges = withContext(Dispatchers.Default) { SpeechSegmentLocator.locate(text, chunks) }
                fun markSpoken(index: Int) {
                    if (index !in chunks.indices) return
                    lastRead = messageId to (from + offsets[index] + chunks[index].length).coerceAtMost(readable.length)
                }
                fun announce(index: Int) {
                    if (index !in chunks.indices) return
                    scope.launch(Dispatchers.Main.immediate) {
                        if (session != generation) return@launch
                        mutableState.value = SpeechPlaybackState(
                            messageId = messageId,
                            highlight = ranges.getOrNull(index),
                            spokenChunks = index + 1,
                            totalChunks = chunks.size,
                        )
                    }
                }
                if (config.tts == TtsProvider.SYSTEM) {
                    lease?.requestPlaybackFocus { beforePlayback(); playbackActive = true }
                    // 系统语音没有分段进度回调：逐段朗读，读到哪一段亮哪一段。
                    var spoken = false
                    chunks.forEachIndexed { index, chunk ->
                        announce(index)
                        // 出过声就算数：整段成功、或者兜底重切后有一半读出来了，都记 spoken。
                        val failure = speakSystemChunk(chunk, config.speechRate, config.speechPitch) { spoken = true }
                        if (failure == null) markSpoken(index)
                        if (failure != null) {
                            // 一段都没读出来才叫播报失败，交给外层弹提示。
                            if (!spoken) throw failure
                            // 已经出过声：一段坏句子不该把整篇朗读打断，记原因后跳过继续读。
                            AndroidAgentLogger.warn(
                                "Eta speech chunk skipped: index=$index/${chunks.lastIndex} length=${chunk.length} " +
                                    "code=${failure.code} detail=${failure.detail}",
                            )
                            // 跳过也要记账，否则下次续读会把它当新内容再念一遍。
                            markSpoken(index)
                        }
                    }
                    if (session == generation) stop()
                    return@launch
                }
                lease?.requestPlaybackFocus { beforePlayback(); playbackActive = true }
                val player = createOutput()
                output = player
                playChunks(
                    config, secrets, chunks, player,
                    onChunk = { index -> announce(index) },
                    onChunkFinished = { index -> markSpoken(index) },
                ) {
                    scope.launch(Dispatchers.Main.immediate) {
                        if (session == generation) {
                            mutableState.value = SpeechPlaybackState(
                                messageId,
                                spokenChunks = chunks.size,
                                totalChunks = chunks.size,
                            )
                        }
                    }
                }
                player.finish()
                withTimeout(180_000) { while (!player.drained()) delay(20) }
                lastRead = messageId to readable.length
                if (session == generation) stop()
            } catch (error: Exception) {
                if (error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
                if (session == generation) {
                    val failure = error.speechFailure()
                    AndroidAgentLogger.warn("Eta speech playback failed: code=${failure.code} type=${error.javaClass.simpleName} detail=${failure.detail}")
                    stop()
                    mutableState.value = SpeechPlaybackState(error = failure.userMessage)
                }
            } finally {
                if (session == generation) stop()
            }
        }
    }

    /**
     * 合成按网络速度下载，播放按实时速度写入音轨，两者经队列解耦。若在合成回调里直接写音轨，
     * 读流会被播放节奏拖住，服务端也会长时间等待客户端读取。
     *
     * 优先把整篇放进一个流式合成会话，音频是一条连续的流，整体只在首尾淡入淡出。
     * 流式会话在出声前失败（例如账号未开通实时模型、自定义地址不支持 WebSocket）时，
     * 回落到逐段请求：最多领先播放 [PREFETCH_CHUNKS] 段，接缝处由 [SpeechSegmentFade] 淡入淡出。
     * 已经出声后再失败则直接报错，不从头重读。
     */
    private suspend fun playChunks(
        config: SpeechSettings,
        secrets: SpeechCredentials,
        chunks: List<String>,
        player: SpeechAudioOutput,
        onChunk: (Int) -> Unit,
        onChunkFinished: (Int) -> Unit = {},
        onFirstAudio: () -> Unit,
    ) = coroutineScope {
        val queue = Channel<SpeechQueueItem>(Channel.UNLIMITED)
        val ahead = Semaphore(PREFETCH_CHUNKS)
        launch {
            try {
                var streamed = false
                val stream = streamSynthesize
                if (stream != null) {
                    try {
                        ahead.acquire()
                        stream(config, secrets, chunks) { bytes ->
                            streamed = true
                            queue.trySend(SpeechQueueItem.Audio(bytes))
                        }
                        queue.send(SpeechQueueItem.ChunkEnd(chunks.lastIndex))
                        streamed = true
                    } catch (error: Exception) {
                        if (error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
                        if (streamed) throw error
                        AndroidAgentLogger.warn("Eta speech stream unavailable, using segments: code=${error.speechFailure().code}")
                        ahead.release()
                    }
                }
                if (!streamed) chunks.forEachIndexed { index, chunk ->
                    ahead.acquire()
                    // 合成端先亮起这一段：播放还在预取时，界面已经指到当前朗读位置。
                    onChunk(index)
                    synthesize(config, secrets, chunk) { bytes -> queue.trySend(SpeechQueueItem.Audio(bytes)) }
                    queue.send(SpeechQueueItem.ChunkEnd(index))
                }
                queue.close()
            } catch (error: Throwable) {
                // 合成超时是 CancellationException，子协程以它结束不会取消父作用域，必须经队列交给播放端。
                queue.close(error)
            }
        }
        withContext(Dispatchers.IO) {
            var announced = false
            val fade = SpeechSegmentFade()
            // 音轨写满时 write 会休眠重试；可中断才能让宿主取消立即结束这里，随后关闭音轨。
            suspend fun play(bytes: ByteArray) {
                if (bytes.isNotEmpty()) runInterruptible { player.write(bytes) }
            }
            for (item in queue) when (item) {
                is SpeechQueueItem.Audio -> {
                    if (!announced) { announced = true; onFirstAudio() }
                    play(fade.write(item.bytes))
                }
                is SpeechQueueItem.ChunkEnd -> {
                    play(fade.end())
                    ahead.release()
                    // 本段读完，高亮交给下一段；最后一段越界时保持不动，直到朗读结束。
                    onChunk(item.index + 1)
                    onChunkFinished(item.index)
                }
            }
        }
    }

    /**
     * 系统引擎单段朗读，失败时自救。
     *
     * ColorOS 无障碍朗读引擎对「非 Talkback 应用」走专用「文件合成」通道，这条通道按墙钟 30 秒判超时
     * （实测 487 字需要 31.5 秒合成，整 30 秒被杀，音频轨停了时已播 29.6 秒）。引擎只会回一个错误码，
     * 所以这里先重建引擎、再把这一段按句子边界对半重切：半段合成时间约为整段一半，通常就能过；
     * 两半都失败才把失败抛出去（由调用方决定是跳过还是报错）。
     */
    private suspend fun speakSystemChunk(
        text: String,
        rate: Float,
        pitch: Float,
        depth: Int = 0,
        onSpoke: () -> Unit = {},
    ): SpeechFailure? {
        val startedAt = SystemClock.elapsedRealtime()
        return try {
            SystemTtsSpeaker.speak(context, text, rate, pitch)
            onSpoke()
            null
        } catch (error: Exception) {
            if (error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
            val failure = error.speechFailure()
            if (depth >= SYSTEM_SPLIT_DEPTH) return failure
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            val parts = retryParts(text, elapsedMs)
            if (parts.isEmpty()) return failure
            SystemTtsSpeaker.release()
            var last: SpeechFailure? = null
            for (part in parts) {
                val partFailure = speakSystemChunk(part, rate, pitch, depth + 1, onSpoke)
                if (partFailure != null) last = partFailure
            }
            last
        }
    }

    /**
     * 失败后该从哪儿接着读。
     *
     * 引擎出声速度接近实时，所以"已经念了多久"能反推出"已经念到第几个字"：被 30 秒看门狗掐掉的那一段，
     * 直接从估出的位置往下读，听过的部分不再重念；只有引擎秒错（几乎没出声）时才退回按句子边界对半切。
     */
    private fun retryParts(text: String, elapsedMs: Long): List<String> {
        val played = ((elapsedMs / 1000.0) * SYSTEM_TTS_CHARS_PER_SECOND).toInt()
        if (played < SYSTEM_RESUME_MIN_CHARS) return splitForRetry(text)
        val estimated = played.coerceIn(1, text.length)
        val boundary = text.lastIndexOfAny(SPEECH_BOUNDARY_CHARS.toCharArray(), estimated - 1)
        val cut = if (boundary > 0 && boundary >= estimated - SYSTEM_RESUME_LOOKBACK) boundary + 1 else estimated
        val tail = text.substring(cut.coerceIn(0, text.length)).trim()
        return listOf(tail).filter { it.isNotEmpty() }
    }

    /** 按句子边界对半切开；切不动时退回字符中点，保证每兜一次底都确实变小。 */
    private fun splitForRetry(text: String): List<String> {
        val mid = text.length / 2
        if (mid <= 0) return emptyList()
        val window = text.take((mid + SPLIT_LOOKAHEAD).coerceAtMost(text.length))
        val boundary = window.indexOfLast { it in SPEECH_BOUNDARY_CHARS }
        val cut = if (boundary >= mid / 2) boundary else mid - 1
        return listOf(text.substring(0, cut + 1).trim(), text.substring(cut + 1).trim())
            .filter { it.isNotEmpty() }
    }

    /** 每段在 readable 文本里的起点，用来记录"读到哪儿了"。 */
    private fun chunkOffsets(text: String, chunks: List<String>): List<Int> {
        val offsets = ArrayList<Int>(chunks.size)
        var cursor = 0
        for (chunk in chunks) {
            val found = text.indexOf(chunk, cursor)
            val start = if (found >= 0) found else cursor
            offsets += start
            cursor = start + chunk.length
        }
        return offsets
    }

    fun stop() {
        generation++
        output?.close(); output = null
        job?.cancel(); job = null
        lease?.close(); lease = null
        if (playbackActive) afterPlayback()
        playbackActive = false
        mutableState.value = SpeechPlaybackState()
    }
}

private sealed interface SpeechQueueItem {
    class Audio(val bytes: ByteArray) : SpeechQueueItem
    data class ChunkEnd(val index: Int) : SpeechQueueItem
}

private const val PREFETCH_CHUNKS = 2

/**
 * 系统引擎的片段上限。
 *
 * ColorOS 引擎的「文件合成」通道按墙钟 30 秒判超时，而它出声的速度接近实时——
 * 实测 5.1~5.6 字/秒（257 字要 29.8 秒、146 字要 28.7 秒、111 字要 21.1 秒），
 * 也就是说单段上限其实只有 160 字左右。这里取 100 字（约 19 秒），留出四成余量。
 */
private const val SYSTEM_TTS_CHUNK_LIMIT = 100

/** 实测出声速度，用来在超时后反推"已经念到第几个字"。 */
private const val SYSTEM_TTS_CHARS_PER_SECOND = 5.3

/** 出声时间短于这个时长，就认为引擎根本没念几句，改走对半切。 */
private const val SYSTEM_RESUME_MIN_CHARS = 20

/** 续读位置往前找句读的最大距离，避免从半个句子里接上。 */
private const val SYSTEM_RESUME_LOOKBACK = 24

/** 兜底重切的最大层数：100 → 50 → 25，够用了。 */
private const val SYSTEM_SPLIT_DEPTH = 2

/** 已经切到这么短还失败，就认定这一段真的读不出来。 */
private const val SYSTEM_MIN_CHUNK_LENGTH = 60

private const val SPLIT_LOOKAHEAD = 24
private const val SPEECH_BOUNDARY_CHARS = "。！？.!?；;，,\n"

internal interface SpeechAudioOutput : Closeable {
    fun write(bytes: ByteArray)
    fun finish()
    fun drained(): Boolean
}
