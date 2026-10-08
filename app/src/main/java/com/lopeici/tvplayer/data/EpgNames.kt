package com.lopeici.tvplayer.data

/**
 * Name-based EPG matching for channels without a `tvg-id`: playlist names and XMLTV
 * `<display-name>`s are reduced to a comparable form, so "UK: BBC One HD" matches "BBC One".
 */
object EpgNames {

    private val countryPrefix = Regex("""^[a-z]{2,3}\s*[:|]\s*""")
    private val bracketed = Regex("""\([^)]*\)|\[[^]]*]""")
    private val qualitySuffix = Regex("""(uhd|fhd|hd|sd|4k|hevc)$""")

    /** Lowercase letters/digits only, without a country prefix, bracketed tags or a quality suffix. */
    fun normalize(name: String): String {
        val lower = name.lowercase().trim()
        val stripped = bracketed.replace(countryPrefix.replace(lower, ""), " ")
        return qualitySuffix.replace(stripped.filter { it.isLetterOrDigit() }, "")
    }

    /** EPG map key for a channel matched by [name]; null when nothing comparable is left. */
    fun key(name: String): String? = normalize(name).ifEmpty { null }?.let(::keyOfNormalized)

    fun keyOfNormalized(normalized: String): String = "name:$normalized"
}
