package io.github.mangi.eta.agent.display

internal data class VirtualDisplayInfo(
    val sessionId: String,
    val owner: String,
    val displayId: Int,
    /** 灵屏的坐标系尺寸（与主屏一致，例如 1440x3168）。触摸注入一律按它换算。 */
    val width: Int,
    val height: Int,
    val manualInputGeneration: Long = 0,
    val density: Int = 0,
    val focusedPackage: String = "",
    /** 采集/传输尺寸（长边 ≤1760）。只用于画面等比显示，不参与坐标换算。 */
    val captureWidth: Int = width,
    val captureHeight: Int = height,
)

internal data class VirtualScreenGesture(
    val id: Long,
    val sessionId: String,
    val action: String,
    val x: Int,
    val y: Int,
    val endX: Int = x,
    val endY: Int = y,
    val durationMs: Int = 500,
    val startedAt: Long,
)

internal data class VirtualScreenViewerState(
    val display: VirtualDisplayInfo? = null,
    val gesture: VirtualScreenGesture? = null,
    val lastAction: String = "",
    val taskPhase: VirtualScreenTaskPhase = VirtualScreenTaskPhase.IDLE,
    val activeRunId: String? = null,
    val operations: List<VirtualScreenOperation> = emptyList(),
) {
    val isAgentControlling: Boolean get() = display != null && taskPhase == VirtualScreenTaskPhase.RUNNING

    fun beginRun(runId: String) = copy(taskPhase = VirtualScreenTaskPhase.RUNNING, activeRunId = runId)

    fun finishRun(runId: String, success: Boolean, cancelled: Boolean): VirtualScreenViewerState =
        if (activeRunId != runId) this else copy(
            activeRunId = null,
            taskPhase = when { success -> VirtualScreenTaskPhase.COMPLETED
                cancelled -> VirtualScreenTaskPhase.STOPPED
                else -> VirtualScreenTaskPhase.FAILED },
        )

    fun record(operation: VirtualScreenOperation) = copy(operations = (operations + operation).takeLast(100))
}

internal enum class VirtualScreenTaskPhase { IDLE, RUNNING, COMPLETED, FAILED, STOPPED }

internal data class VirtualScreenOperation(
    val id: Long,
    val name: String,
    val timestamp: Long,
    val success: Boolean,
    val manual: Boolean,
)
