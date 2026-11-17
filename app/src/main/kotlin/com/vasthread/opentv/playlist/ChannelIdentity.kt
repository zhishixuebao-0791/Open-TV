package com.vasthread.webviewtv.playlist

import java.util.Locale

/** Stable identity shared by parsing, selection and preference migration. */
object ChannelIdentity {
    fun normalizeName(name: String): String {
        var value = name.trim().uppercase(Locale.ROOT)
            .replace('＋', '+')
            .replace(Regex("[\\s\\-_/\\\\|()（）\\[\\]]"), "")
        // CCTV4K and CCTV8K are channel identities, not quality suffixes.
        if (!Regex("^CCTV[48]K$").matches(value)) {
            val suffix = Regex("(4K|8K|UHD|FHD|HD|HDR|超高清|高清|超清|标清)$")
            while (suffix.containsMatchIn(value) && !Regex("^CCTV[48]K$").matches(value)) value = value.replace(suffix, "")
        }
        val numbered = Regex("^CCTV0*(\\d+)(\\+|PLUS)?$").matchEntire(value)
        if (numbered != null) {
            return "CCTV${numbered.groupValues[1].toIntOrNull() ?: numbered.groupValues[1]}" +
                if (numbered.groupValues[2].isNotEmpty()) "PLUS" else ""
        }
        if (value.startsWith("CCTV") && value.drop(4).any { it.code > 127 }) {
            value = value.drop(4)
        }
        return value
    }

    fun key(group: String, name: String): String =
        "${group.trim().ifEmpty { "默认" }}::${normalizeName(name)}"
}
