package com.andaxin.data.capture

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FifQrTargetTest {
    private val token = "SyntheticUnitTestValue001"
    private val endpoint = "https://${FifQrTarget.OFFICIAL_HOST}${FifQrTarget.OFFICIAL_PATH}"

    @Test fun rebuildsBareTokenOnOfficialEndpoint() {
        val target = requireNotNull(FifQrTarget.parse(token))
        assertNull(target.officialUrl("", "", "", "", "11111111111111111111111111111111"))
        val url = requireNotNull(target.officialUrl("jwt", "id", "name", "student", "11111111111111111111111111111111"))
        assertTrue(FifQrTarget.isOfficialHandler(url))
        assertEquals(endpoint, url.newBuilder().query(null).build().toString())
        assertEquals(
            setOf("token", "openTheWay", "access_token", "_userCode", "code", "userCode",
                "_userName", "_userType", "appId", "returnFromIscToAppFunc"),
            url.queryParameterNames,
        )
        assertEquals(token, url.queryParameter("token"))
        assertEquals("2", url.queryParameter("openTheWay"))
    }

    @Test fun discardsScannedIdentityAndUsesOnlyLocalIdentity() {
        val parsed = requireNotNull(FifQrTarget.parse("$endpoint?token=$token&_userCode=scan-value"))
        val url = requireNotNull(parsed.officialUrl("test-session", "local-user", "本地 测试", "student", "11111111111111111111111111111111"))
        assertTrue(FifQrTarget.isOfficialHandler(url))
        assertEquals("test-session", url.queryParameter("access_token"))
        for (name in listOf("_userCode", "code", "userCode")) assertEquals("local-user", url.queryParameter(name))
        assertEquals("本地 测试", url.queryParameter("_userName"))
        assertEquals("student", url.queryParameter("_userType"))
        assertEquals("11111111111111111111111111111111", url.queryParameter("appId"))
        assertEquals("ReturnDefault", url.queryParameter("returnFromIscToAppFunc"))
    }

    @Test fun acceptsStandardEscapedTokenCharacters() {
        val value = "sample+token/8xy="
        val url = endpoint.toHttpUrl().newBuilder().addQueryParameter("token", value).build()
        val rebuilt = requireNotNull(FifQrTarget.parse(url.toString()))
            .officialUrl("jwt", "id", "name", "student", "11111111111111111111111111111111")
        assertEquals(value, rebuilt?.queryParameter("token"))
        assertNotNull(FifQrTarget.parse(" $token "))
    }

    @Test fun localCredentialsAreRequiredButDisplayFieldsAreOptional() {
        val target = requireNotNull(FifQrTarget.parse(token))
        assertNull(target.officialUrl("", "id", "", "", "app"))
        assertNull(target.officialUrl("jwt", "", "", "", "app"))
        assertNull(target.officialUrl("jwt", "id", "", "", ""))
        val url = requireNotNull(target.officialUrl("jwt", "id", "", "", "app"))
        assertNull(url.queryParameter("_userName"))
        assertNull(url.queryParameter("_userType"))
    }

    @Test fun rejectsNonApprovedDestinations() {
        val official = endpoint.toHttpUrl()
        val wrongOrigin = official.newBuilder().host("other.invalid").build()
        for (url in listOf(wrongOrigin, official.newBuilder().scheme("http").build(),
            official.newBuilder().port(8443).build(), official.newBuilder().encodedPath("/other").build())) {
            assertFalse(FifQrTarget.isOfficialHandler(url))
            assertNull(FifQrTarget.parse(url.newBuilder().addQueryParameter("token", token).build().toString()))
        }
    }

    @Test fun rejectsMissingAmbiguousOrUnsupportedFields() {
        for (value in listOf("", "$endpoint?token=$token&token=$token",
            "$endpoint?token=$token&openTheWay=1", "$endpoint?token=$token&unknown=1",
            "$endpoint?token=$token#fragment", "a".repeat(4097))) {
            assertNull(FifQrTarget.parse(value))
        }
        assertNull(FifQrTarget.parse(endpoint))
    }
}
