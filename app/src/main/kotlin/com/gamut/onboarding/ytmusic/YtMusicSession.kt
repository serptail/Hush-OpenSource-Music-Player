package com.gamut.onboarding.ytmusic

import java.security.MessageDigest

/**
 * What the YouTube Music login page handed over: the session cookie plus the identity the page itself was using.
 * It is a full credential for the account, so it only ever lives in memory for the length of one import.
 */
class YtSession(
    val cookie: String,
    val dataSyncId: String?,
    val pageId: String?,
    val authUser: String?,
    val visitorData: String?,
    val clientVersion: String?,
) {
    /** Never printed, even by accident. */
    override fun toString() = "YtSession(redacted)"

    companion object {
        /** No account at all: enough to read a public or unlisted playlist, as a signed-out visitor would. */
        fun anonymous() = YtSession(cookie = "", dataSyncId = null, pageId = null, authUser = null, visitorData = null, clientVersion = null)
    }
}

object YtSigning {
    private val SAPISID_NAMES = listOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID")

    /**
     * Whether a cookie header carries a secret requests can be signed with. Matched on the exact cookie name:
     * "__Secure-3PAPISID" merely contains "SAPISID", and a login holding only the __Secure- forms must still count.
     */
    fun hasApiSid(cookieHeader: String): Boolean = sapisidFrom(cookieHeader) != null

    fun sapisidFrom(cookieHeader: String): String? {
        val jar = cookieHeader.split(';').mapNotNull { entry ->
            val name = entry.substringBefore('=').trim()
            val value = entry.substringAfter('=', "").trim()
            if (name.isEmpty() || value.isEmpty()) null else name to value
        }.toMap()
        return SAPISID_NAMES.firstNotNullOfOrNull { jar[it] }
    }

    /**
     * YouTube exposes DATASYNC_ID as "account||delegated": the second half is the identity actually in use, and a
     * plain account leaves it empty. Requests want that active half.
     */
    fun normalizeDataSyncId(raw: String?): String? {
        val value = raw?.takeIf { it.isNotBlank() } ?: return null
        if (!value.contains("||")) return value
        return value.substringAfter("||").takeIf { it.isNotBlank() } ?: value.substringBefore("||").takeIf { it.isNotBlank() }
    }

    /** Google's SAPISIDHASH scheme: SHA-1 of "timestamp sapisid origin", sent as the Authorization header. */
    fun sapisidHash(sapisid: String, origin: String, nowSeconds: Long = System.currentTimeMillis() / 1000): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("$nowSeconds $sapisid $origin".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${nowSeconds}_$digest"
    }
}