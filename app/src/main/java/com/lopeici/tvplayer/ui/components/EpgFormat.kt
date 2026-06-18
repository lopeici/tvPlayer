package com.lopeici.tvplayer.ui.components

import com.lopeici.tvplayer.data.Programme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val clockFormat = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

fun formatClock(epochMs: Long): String = clockFormat.format(Instant.ofEpochMilli(epochMs))

/** "14:00 – 14:30" */
fun Programme.timeRange(): String = "${formatClock(start)} – ${formatClock(stop)}"

/** Fraction elapsed [0f, 1f] of this programme at [now]. */
fun Programme.progress(now: Long): Float {
    if (stop <= start) return 0f
    return ((now - start).toFloat() / (stop - start)).coerceIn(0f, 1f)
}
