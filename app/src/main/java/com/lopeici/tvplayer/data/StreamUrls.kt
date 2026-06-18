package com.lopeici.tvplayer.data

/**
 * Best-effort transform of a stream URL to its HLS (`.m3u8`) variant — useful for Xtream Codes
 * channels, whose default mpegts/`.ts` form can't be cast to a stock Chromecast receiver but whose
 * `.m3u8` form can. URLs that are already `.m3u8` or have an unrecognized shape are returned
 * unchanged. The query string (if any) is preserved.
 */
fun hlsVariant(url: String): String {
    val qIdx = url.indexOf('?')
    val base = if (qIdx >= 0) url.substring(0, qIdx) else url
    val query = if (qIdx >= 0) url.substring(qIdx) else ""
    val slash = base.lastIndexOf('/')
    val seg = if (slash >= 0) base.substring(slash + 1) else base
    val dot = seg.lastIndexOf('.')
    val ext = if (dot >= 0) seg.substring(dot + 1).lowercase() else ""
    val newSeg = when (ext) {
        "m3u8" -> seg
        "ts", "mpegts", "" -> (if (dot >= 0) seg.substring(0, dot) else seg) + ".m3u8"
        else -> seg
    }
    val newBase = if (slash >= 0) base.substring(0, slash + 1) + newSeg else newSeg
    return newBase + query
}
