package com.gamut.onboarding.ytmusic

import java.security.MessageDigest

class YtSession(
    val cookie: String,
    val dataSyncId: String?,
    val pageId: String?,
    val authUser: String?,
    val visitorData: String?,
    val clientVersion: String?,
) {
    override fun toString() = "YtSession(redacted)"

    companion object {
        fun anonymous() = YtSession(cookie = "", dataSyncId = null, pageId = null, authUser = null, visitorData = null, clientVersion = null)
    }
}

object YtSigning {
    private val SAPISID_NAMES = listOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID")

    fun hasApiSid(cookieHeader: String): Boolean = sapisidFrom(cookieHeader) != null

    fun sapisidFrom(cookieHeader: String): String? {
        val jar = cookieHeader.split(';').mapNotNull { entry ->
            val name = entry.substringBefore('=').trim()
            val value = entry.substringAfter('=', "").trim()
            if (name.isEmpty() || value.isEmpty()) null else name to value
        }.toMap()
        return SAPISID_NAMES.firstNotNullOfOrNull { jar[it] }
    }

    fun normalizeDataSyncId(raw: String?): String? {
        val value = raw?.takeIf { it.isNotBlank() } ?: return null
        if (!value.contains("||")) return value
        return value.substringAfter("||").takeIf { it.isNotBlank() } ?: value.substringBefore("||").takeIf { it.isNotBlank() }
    }

    fun sapisidHash(sapisid: String, origin: String, nowSeconds: Long = System.currentTimeMillis() / 1000): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("$nowSeconds $sapisid $origin".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${nowSeconds}_$digest"
    }
}
