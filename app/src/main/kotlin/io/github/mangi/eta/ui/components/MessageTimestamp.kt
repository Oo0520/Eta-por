package io.github.mangi.eta.ui.components

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 消息卡片末尾的时间戳。
 *
 * 规则（与常见 IM 一致）：今天只给 `HH:mm`，昨天补「昨天」，同一年给「M月d日 HH:mm」，
 * 跨年带年份。`millis <= 0` 表示这条消息来自没有时间列的旧版本 —— 直接返回 null，
 * 界面不显示时间，也不编造一个近似值。
 */
internal fun formatMessageTimestamp(millis: Long, now: Long = System.currentTimeMillis()): String? {
    if (millis <= 0L) return null
    val target = Calendar.getInstance().apply { timeInMillis = millis }
    val today = Calendar.getInstance().apply { timeInMillis = now }
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
    val sameYear = target.get(Calendar.YEAR) == today.get(Calendar.YEAR)
    val dayDiff = if (sameYear) today.get(Calendar.DAY_OF_YEAR) - target.get(Calendar.DAY_OF_YEAR) else -1
    return when {
        sameYear && dayDiff == 0 -> time
        sameYear && dayDiff == 1 -> "昨天 $time"
        sameYear -> "${target.get(Calendar.MONTH) + 1}月${target.get(Calendar.DAY_OF_MONTH)}日 $time"
        else -> "${target.get(Calendar.YEAR)}年${target.get(Calendar.MONTH) + 1}月" +
            "${target.get(Calendar.DAY_OF_MONTH)}日 $time"
    }
}

