package com.andaxin.data.capture

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Parses a QR payload but never forwards its caller-controlled URL or extra query parameters. */
internal class FifQrTarget private constructor(private val token: String) {
    fun officialUrl(
        jwt: String,
        userCode: String,
        userName: String,
        userType: String,
        appId: String,
    ): HttpUrl? {
        if (jwt.isBlank() || userCode.isBlank() || appId.isBlank()) return null
        return OFFICIAL_URL.newBuilder()
            .addQueryParameter("token", token)
            .addQueryParameter("openTheWay", "2")
            .addQueryParameter("access_token", jwt)
            .addQueryParameter("_userCode", userCode)
            .addQueryParameter("code", userCode)
            .addQueryParameter("userCode", userCode)
            .apply {
                if (userName.isNotBlank()) addQueryParameter("_userName", userName)
                if (userType.isNotBlank()) addQueryParameter("_userType", userType)
            }
            .addQueryParameter("appId", appId)
            .addQueryParameter("returnFromIscToAppFunc", "ReturnDefault")
            .build()
    }

    companion object {
        const val OFFICIAL_HOST = "sttp.fifedu.com"
        const val OFFICIAL_PATH = "/coursecenter-interaction/qrcodeV2/qrcodeHandler"
        private val tokenPattern = Regex("^[A-Za-z0-9._~+/-]{1,512}={0,2}$")
        private val bareTokenPattern = Regex("^[A-Za-z0-9_-]{20,512}$")
        private val approvedQueryNames = setOf(
            "token", "openTheWay", "access_token", "_userCode", "code", "userCode",
            "_userName", "_userType", "appId", "returnFromIscToAppFunc",
        )
        private val OFFICIAL_URL = "https://$OFFICIAL_HOST$OFFICIAL_PATH".toHttpUrl()

        fun parse(raw: String): FifQrTarget? {
            val input = raw.trim()
            if (input.isEmpty() || input.length > 4096) return null
            val supplied = input.toHttpUrlOrNull()
            val token = if (supplied == null) {
                input.takeIf(bareTokenPattern::matches)
            } else {
                if (!isOfficialHandler(supplied)) return null
                val tokenValues = supplied.queryParameterValues("token")
                val wayValues = supplied.queryParameterValues("openTheWay")
                if (tokenValues.size != 1 || (wayValues.isNotEmpty() && wayValues != listOf("2"))) return null
                tokenValues.singleOrNull()?.takeIf(tokenPattern::matches)
            } ?: return null
            return FifQrTarget(token)
        }

        /** Only accept the official HTTPS handler and its documented identity parameters. */
        internal fun isOfficialHandler(url: HttpUrl): Boolean =
            url.isHttps &&
                url.host == OFFICIAL_HOST &&
                url.port == 443 &&
                url.username.isEmpty() && url.password.isEmpty() &&
                url.encodedPath == OFFICIAL_PATH &&
                url.fragment == null &&
                url.queryParameterNames.all { it in approvedQueryNames }
    }
}
