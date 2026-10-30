package io.github.mangi.eta.agent.phone

import android.content.Context
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.device.DeviceToolContract
import org.json.JSONObject

internal class PhoneAppTools(
    private val context: Context,
    private val root: BoundedRootCommandExecutor,
    private val rootAvailable: () -> Boolean,
    private val colorOs: () -> Boolean,
) {
    fun execute(tool: String, arguments: JSONObject): JSONObject? {
        if (tool !in PhoneOperation.tools) return null
        val calendar = tool.contains("calendar")
        val system =
            tool in NativePersonalQueries.sources.keys ||
                tool in
                    setOf(
                        "get_night_light",
                        "get_mobile_data",
                        "set_night_light",
                        "set_mobile_data",
                        "get_hotspot",
                        "set_hotspot",
                        "set_sound_mode",
                    )
        if (tool == "set_sound_mode" && !rootAvailable())
            return PhoneOperations.execute(context, tool, arguments)
        if (!calendar && !system && !colorOs())
            return if (tool in setOf("set_alarm", "list_alarms")) null
            else PhoneOperation.failure("DEVICE_UNSUPPORTED", "当前设备没有兼容的一方应用接口")
        // 闹钟优先走厂商直写接口：全程不发 Intent，不会拉起时钟界面。
        // 有 Root 走 root 子进程；没有 Root 时也先让应用自身试一次，失败再回退标准 Intent。
        if (tool == "set_alarm") {
            if (rootAvailable()) return executeRoot(tool, arguments)
            // 只要厂商接口给出了结果（含「已创建但未能回读确认」）就采信，避免回退 Intent 重复创建；
            // 只有抛异常（接口不可用 / 被拒）才回退。
            val viaProvider = runCatching { PhoneOperations.execute(context, tool, arguments) }.getOrNull()
            if (viaProvider != null) return viaProvider
            return null
        }
        if (calendar && hasCalendarAccess(tool in PhoneOperation.writes))
            return PhoneOperations.execute(context, tool, arguments)
        if (!rootAvailable())
            return PhoneOperation.failure(
                if (calendar) "CALENDAR_PERMISSION_REQUIRED" else "ROOT_REQUIRED",
                if (calendar) "请在权限健康页授予日历读取和写入权限" else "此一方应用接口需要 Root",
            )
        return executeRoot(tool, arguments)
    }

    fun executeVivoPersonal(tool: String, arguments: JSONObject): JSONObject {
        if (!rootAvailable()) return PhoneOperation.failure("ROOT_REQUIRED", "vivo 个人数据接口需要 Root")
        return executeRoot(tool, arguments, "vivo_personal")
    }

    private fun executeRoot(tool: String, arguments: JSONObject, backend: String? = null): JSONObject {
        val request =
            JSONObject()
                .put("version", 1)
                .put("tool", tool)
                .put("user_id", DeviceToolContract.appUserId(context))
                .put("arguments", arguments)
        backend?.let { request.put("backend", it) }
        val command =
            "CLASSPATH=${DeviceToolContract.quote(context.applicationInfo.sourceDir)} app_process /system/bin io.github.mangi.eta.agent.phone.PhoneCommandMain"
        val result =
            root.execute(
                command,
                timeoutMillis = 25000,
                maxOutputBytes = 768 * 1024,
                input = request.toString().toByteArray(Charsets.UTF_8),
            )
        if (!result.ok || result.truncated)
            return PhoneOperation.failure(
                    result.errorCode.ifBlank { "PHONE_OPERATION_UNCONFIRMED" },
                    "原生操作通道未返回完整结果，请先核对状态",
                )
                .put("status", if (tool in PhoneOperation.writes) "unconfirmed" else "failed")
                .put("verified", false)
        val payload =
            result.stdout
                .lineSequence()
                .singleOrNull { it.startsWith(PhoneCommandMain.MARKER) }
                ?.removePrefix(PhoneCommandMain.MARKER)
                ?: return PhoneOperation.failure("PHONE_OPERATION_UNCONFIRMED", "未收到可验证的操作结果")
                    .put("status", "unconfirmed")
        return try {
            JSONObject(payload)
        } catch (_: org.json.JSONException) {
            PhoneOperation.failure("PHONE_OPERATION_UNCONFIRMED", "操作结果格式不完整")
                .put("status", "unconfirmed")
        }
    }

    private fun hasCalendarAccess(write: Boolean) =
        io.github.mangi.eta.agent.device.CalendarPermissions.granted(context, write)
}
