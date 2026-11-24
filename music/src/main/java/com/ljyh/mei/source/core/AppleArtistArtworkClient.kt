package com.ljyh.mei.source.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.ljyh.mei.source.api.SourceErrorCode
import com.ljyh.mei.source.api.SourceException
import com.ljyh.mei.source.script.ScriptNetworkPolicy
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

private const val MAX_ARTWORK_EDGE = 8192
private const val LOGO_SIZE_BUCKET_PX = 128
private const val LARGE_LOGO_PIXELS = 3_000_000
private val APPLE_ARTIST_KEY_COLOR = Regex("[0-9a-fA-F]{6}")
private val APPLE_LOGO_SIZE = Regex("([0-9]+)x([0-9]+)([a-z]{2})\\.png")

internal fun appleArtistKeyColorRgb(value: String?): Int? =
    value?.takeIf(APPLE_ARTIST_KEY_COLOR::matches)?.toInt(16)

internal fun validatedAppleArtistLogo(url: String, width: Int, height: Int): AppleArtistLogo? {
    if (width !in 1..MAX_ARTWORK_EDGE || height !in 1..MAX_ARTWORK_EDGE || '{' in url || '}' in url) return null
    val artwork = url.toHttpUrlOrNull()?.takeIf {
        it.scheme == "https" && it.port == 443 && it.host.endsWith(".mzstatic.com") &&
            it.username.isEmpty() && it.password.isEmpty() && it.fragment == null &&
            it.encodedPath.startsWith("/image/thumb/") && it.pathSegments.last().endsWith(".png")
    } ?: return null
    return AppleArtistLogo(url = artwork.toString(), width = width, height = height)
}

internal fun AppleArtistLogo.displayUrl(targetEdgePx: Int): String {
    val original = validatedAppleArtistLogo(url, width, height) ?: return url
    if (width * height <= LARGE_LOGO_PIXELS) return url
    val originalEdge = maxOf(width, height)
    val edge = targetEdgePx.coerceIn(1, originalEdge)
    val bucket = ((edge + LOGO_SIZE_BUCKET_PX - 1) / LOGO_SIZE_BUCKET_PX) * LOGO_SIZE_BUCKET_PX
    if (bucket >= originalEdge) return url
    val artwork = original.url.toHttpUrl()
    val size = APPLE_LOGO_SIZE.matchEntire(artwork.pathSegments.last()) ?: return url
    if (size.groupValues[1].toIntOrNull() != width || size.groupValues[2].toIntOrNull() != height) return url
    val crop = size.groupValues[3]
    val scaledWidth = (width * bucket + originalEdge - 1) / originalEdge
    val scaledHeight = (height * bucket + originalEdge - 1) / originalEdge
    return artwork.newBuilder()
        .setPathSegment(artwork.pathSegments.lastIndex, "${scaledWidth}x${scaledHeight}$crop.png")
        .build().toString()
}

@Singleton
internal class AppleArtistArtworkClient @Inject constructor() : AppleArtistCatalog {
    private val policy = ScriptNetworkPolicy(listOf(ITUNES_HOST, MUSIC_HOST))
    private val client = policy.client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    override suspend fun searchArtists(name: String, storefront: String): List<AppleArtistCandidate> {
        val term = name.trim().takeIf { it.isNotEmpty() && it.length <= 256 } ?: return emptyList()
        val url = itunesUrl("search", storefront)
            .addQueryParameter("term", term)
            .addQueryParameter("media", "music")
            .addQueryParameter("entity", "musicArtist")
            .addQueryParameter("attribute", "artistTerm")
            .addQueryParameter("limit", SEARCH_LIMIT.toString())
            .build()
        return itunesResults(url).mapNotNull { row ->
            if (row.text("wrapperType") != "artist") return@mapNotNull null
            val artistId = row.text("artistId")?.takeIf(ARTIST_ID::matches) ?: return@mapNotNull null
            val artistName = row.text("artistName")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val pageUrl = canonicalArtistPage(row.text("artistLinkUrl"), artistId, storefront)
            AppleArtistCandidate(id = artistId, name = artistName, pageUrl = pageUrl?.toString())
        }.distinctBy(AppleArtistCandidate::id)
    }

    override suspend fun artistSongs(artistIds: Set<String>, storefront: String): Map<String, List<AppleArtistSong>> {
        require(artistIds.size in 1..MAX_CANDIDATES && artistIds.all(ARTIST_ID::matches))
        val url = itunesUrl("lookup", storefront)
            .addQueryParameter("id", artistIds.joinToString(","))
            .addQueryParameter("entity", "song")
            .addQueryParameter("limit", SONG_LIMIT.toString())
            .build()
        val songs = mutableMapOf<String, MutableList<AppleArtistSong>>()
        for (row in itunesResults(url, SONG_LIMIT_BYTES)) {
            if (row.text("kind") != "song") continue
            val artistId = row.text("artistId")?.takeIf { it in artistIds } ?: continue
            val title = row.text("trackName")?.takeIf(String::isNotBlank) ?: continue
            songs.getOrPut(artistId) { mutableListOf() }.add(AppleArtistSong(
                title = title,
                album = row.text("collectionName")?.takeIf(String::isNotBlank),
                durationMs = row.text("trackTimeMillis")?.toLongOrNull()?.takeIf { it > 0L },
                isrc = row.text("isrc"),
            ))
        }
        return songs.mapValues { (_, recordings) -> recordings.distinct() }
    }

    override suspend fun artistLogo(artistId: String, storefront: String, pageUrl: String?): AppleArtistLogo? {
        require(ARTIST_ID.matches(artistId) && STOREFRONT.matches(storefront))
        val canonical = canonicalArtistPage(pageUrl, artistId, storefront)
        if (canonical != null) {
            try {
                return readArtistLogo(canonical, artistId, storefront)
            } catch (error: SourceException) {
                if (error.code != SourceErrorCode.NOT_FOUND) throw error
                // A stale artist slug can be resolved again from the catalog ID.
            }
        }
        val url = "https://$MUSIC_HOST/$storefront/artist/$artistId".toHttpUrl()
        return try {
            readArtistLogo(url, artistId, storefront)
        } catch (error: SourceException) {
            if (error.code != SourceErrorCode.NOT_FOUND) throw error
            null
        }
    }

    private suspend fun readArtistLogo(url: HttpUrl, artistId: String, storefront: String): AppleArtistLogo? {
        val first = fetch(url, PAGE_LIMIT_BYTES)
        val html = if (first.location == null) first.body else {
            val canonical = url.resolve(first.location)
                ?.takeIf { it.isArtistPage(artistId, storefront) } ?: invalidArtistPage()
            val response = fetch(canonical, PAGE_LIMIT_BYTES)
            if (response.location != null) invalidArtistPage()
            response.body
        }
        val pageScript = ARTIST_SCRIPT.find(html)?.groupValues?.get(1) ?: invalidArtistPage()
        val page = jsonObject(pageScript).objects("data").orEmpty().singleOrNull { block ->
            val intent = block.obj("intent") ?: return@singleOrNull false
            val descriptor = intent.obj("contentDescriptor") ?: return@singleOrNull false
            intent.text("\$kind") == "ArtistDetailPageIntent" &&
                descriptor.matchesArtist(artistId) &&
                descriptor.obj("locale")?.text("storefront")?.let { it == storefront } != false
        }?.obj("data") ?: invalidArtistPage()
        val header = page.objects("sections").orEmpty()
            .filter { it.text("itemKind") == "artistDetailHeader" }
            .flatMap { it.objects("items").orEmpty() }
            .singleOrNull { it.text("id") == artistId && it.obj("contentDescriptor")?.matchesArtist(artistId) == true }
            ?: invalidArtistPage()
        val logo = header.get("artistLogo")?.takeUnless(JsonElement::isJsonNull)?.let {
            it.objectOrNull() ?: invalidArtistPage()
        } ?: return null
        val image = logo.obj("dictionary") ?: invalidArtistPage()
        val width = image.text("width")?.toIntOrNull() ?: invalidArtistPage()
        val height = image.text("height")?.toIntOrNull() ?: invalidArtistPage()
        val template = image.text("url") ?: invalidArtistPage()
        val cropStyle = logo.text("cropStyle")?.takeIf { it.matches(CROP_STYLE) } ?: invalidArtistPage()
        val original = template.replace("{w}", width.toString()).replace("{h}", height.toString())
            .replace("{c}", cropStyle).replace("{f}", "png")
        val artwork = validatedAppleArtistLogo(original, width, height) ?: invalidArtistPage()
        val keyColor = header.get("keyColor")?.takeIf(JsonElement::isJsonPrimitive)
            ?.asJsonPrimitive?.takeIf { it.isString }?.asString
        return artwork.copy(keyColorRgb = appleArtistKeyColorRgb(keyColor))
    }

    private fun itunesUrl(path: String, storefront: String): HttpUrl.Builder {
        require(STOREFRONT.matches(storefront))
        return "https://$ITUNES_HOST/$path".toHttpUrl().newBuilder()
            .addQueryParameter("country", storefront)
    }

    private suspend fun itunesResults(url: HttpUrl, byteLimit: Long = SEARCH_LIMIT_BYTES): List<JsonObject> {
        val response = fetch(url, byteLimit)
        if (response.location != null) throw IOException("Unexpected Apple Search API redirect")
        return jsonObject(response.body).objects("results") ?: throw IOException("Invalid Apple Search API response")
    }

    private data class AppleResponse(val body: String = "", val location: String? = null)

    private suspend fun fetch(url: HttpUrl, byteLimit: Long): AppleResponse =
        policy.exchange(client.newCall(Request.Builder().url(url).build()), {}) { response, _ ->
            if (response.isRedirect) {
                val location = response.header("Location") ?: throw IOException("Invalid Apple redirect")
                return@exchange AppleResponse(location = location)
            }
            if (!response.isSuccessful) {
                val code = when (response.code) {
                    404, 410 -> SourceErrorCode.NOT_FOUND
                    else -> SourceErrorCode.NETWORK
                }
                throw SourceException(code, "Apple request failed (${response.code})")
            }
            val body = response.body
            val source = body.source()
            if (body.contentLength() > byteLimit || source.request(byteLimit + 1L)) {
                throw IOException("Apple response exceeded its size limit")
            }
            AppleResponse(body = source.readUtf8())
        }

    private fun canonicalArtistPage(value: String?, artistId: String, storefront: String): HttpUrl? =
        value?.toHttpUrlOrNull()
            ?.takeIf { it.pathSegments.size == 4 && it.isArtistPage(artistId, storefront) }
            ?.newBuilder()?.query(null)?.build()

    private fun HttpUrl.isArtistPage(artistId: String, storefront: String): Boolean =
        scheme == "https" && host == MUSIC_HOST && port == 443 && username.isEmpty() && password.isEmpty() &&
            fragment == null && pathSegments.size in 3..4 && pathSegments[0] == storefront &&
            pathSegments[1] == "artist" && pathSegments.last() == artistId

    private fun JsonObject.matchesArtist(artistId: String): Boolean =
        text("kind") == "artist" && obj("identifiers")?.text("storeAdamID") == artistId

    private fun jsonObject(value: String): JsonObject = try {
        JsonParser.parseString(value).objectOrNull() ?: throw IOException("Invalid Apple JSON")
    } catch (error: JsonParseException) {
        throw IOException("Invalid Apple JSON", error)
    }

    private fun JsonElement.objectOrNull(): JsonObject? = takeIf(JsonElement::isJsonObject)?.asJsonObject
    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.objectOrNull()
    private fun JsonObject.objects(key: String): List<JsonObject>? =
        get(key)?.takeIf(JsonElement::isJsonArray)?.asJsonArray?.mapNotNull { it.objectOrNull() }

    private fun JsonObject.text(key: String): String? =
        get(key)?.takeIf(JsonElement::isJsonPrimitive)?.asString

    private fun invalidArtistPage(): Nothing = throw IOException("Invalid Apple artist page")

    private companion object {
        const val ITUNES_HOST = "itunes.apple.com"
        const val MUSIC_HOST = "music.apple.com"
        const val SEARCH_LIMIT = 12
        const val SONG_LIMIT = 100
        const val SEARCH_LIMIT_BYTES = 512L * 1024
        const val SONG_LIMIT_BYTES = 2L * 1024 * 1024
        const val PAGE_LIMIT_BYTES = 4L * 1024 * 1024
        val ARTIST_ID = Regex("[1-9][0-9]{0,18}")
        val STOREFRONT = Regex("[a-z]{2}")
        val CROP_STYLE = Regex("[a-z]{2}")
        val ARTIST_SCRIPT = Regex(
            """<script\b[^>]*?\s+id\s*=\s*["']serialized-server-data["'][^>]*>(.*?)</script\s*>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
    }
}
