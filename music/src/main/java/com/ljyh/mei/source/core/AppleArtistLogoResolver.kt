package com.ljyh.mei.source.core

import android.content.Context
import android.util.LruCache
import com.ljyh.mei.source.api.ArtistRef
import com.ljyh.mei.source.api.SourceArtistInfo
import com.ljyh.mei.source.api.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class AppleArtistLogo(val url: String, val width: Int, val height: Int, val keyColorRgb: Int? = null)

internal data class AppleArtistCandidate(val id: String, val name: String, val pageUrl: String? = null)

internal data class AppleArtistSong(
    val title: String,
    val album: String?,
    val durationMs: Long?,
    val isrc: String? = null,
)

internal interface AppleArtistCatalog {
    suspend fun searchArtists(name: String, storefront: String): List<AppleArtistCandidate>
    suspend fun artistSongs(artistIds: Set<String>, storefront: String): Map<String, List<AppleArtistSong>>
    suspend fun artistLogo(artistId: String, storefront: String, pageUrl: String? = null): AppleArtistLogo?
}

@Singleton
class AppleArtistLogoResolver @Inject internal constructor(
    @ApplicationContext context: Context,
    private val catalog: AppleArtistArtworkClient,
) {
    private val mappings = context.getSharedPreferences("apple-artist-mappings-v1", Context.MODE_PRIVATE)
    private val artworkCache = LruCache<ArtistRef, CachedLogo>(MAX_CACHE_ENTRIES)
    private val artistLocks = mutableMapOf<ArtistRef, ArtistLock>()

    internal suspend fun resolve(
        ref: ArtistRef,
        artist: suspend () -> SourceArtistInfo,
        tracks: suspend () -> List<Track>,
        nameHint: String? = null,
    ): AppleArtistLogo? = resolve(ref, artist, tracks, nameHint, prefetch = false)

    internal suspend fun prefetch(
        ref: ArtistRef,
        artist: suspend () -> SourceArtistInfo,
        tracks: suspend () -> List<Track>,
        nameHint: String? = null,
    ) {
        resolve(ref, artist, tracks, nameHint, prefetch = true)
    }

    private suspend fun resolve(
        ref: ArtistRef,
        artist: suspend () -> SourceArtistInfo,
        tracks: suspend () -> List<Track>,
        nameHint: String?,
        prefetch: Boolean,
    ): AppleArtistLogo? = withContext(Dispatchers.IO) {
        val lock = synchronized(artistLocks) {
            artistLocks.getOrPut(ref, ::ArtistLock).also { it.users++ }
        }
        try {
            lock.mutex.withLock { resolveLocked(ref, artist, tracks, nameHint, prefetch) }
        } finally {
            synchronized(artistLocks) {
                if (--lock.users == 0) artistLocks.remove(ref)
            }
        }
    }

    private suspend fun resolveLocked(
        ref: ArtistRef,
        artist: suspend () -> SourceArtistInfo,
        tracks: suspend () -> List<Track>,
        nameHint: String?,
        playbackPrefetch: Boolean,
    ): AppleArtistLogo? = coroutineScope resolve@{
        val now = System.currentTimeMillis()
        artworkCache.get(ref)?.takeIf {
            now < it.expiresAt && (playbackPrefetch || !it.prefetchOnly)
        }?.let { return@resolve it.logo }
        val existing = readMapping(ref, now)
        existing?.artwork?.takeUnless { it.needsKeyColorRefresh }?.let {
            artworkCache.put(ref, it)
            return@resolve it.logo
        }
        var prefetch: Pair<AppleArtistIdentity, Deferred<Result<AppleArtistLogo?>>>? = null
        var earlySearch: Deferred<List<AppleArtistCandidate>?>? = null
        try {
            val mapping = existing ?: run {
                val hintedName = nameHint?.trim()?.takeIf { it.length in 1..256 }
                earlySearch = hintedName?.let { name ->
                    async {
                        try {
                            catalog.searchArtists(name, STOREFRONTS.first())
                        } catch (_: IOException) {
                            null
                        }
                    }
                }
                val info = artist()
                if (info.ref != ref) return@resolve null
                val names = (listOf(info.name) + info.alternateNames + info.translatedNames + info.aliases)
                    .map(String::trim).filter(String::isNotEmpty).distinctBy(::normalizedAppleArtistName)
                if (names.isEmpty()) return@resolve null
                val preferredSearch = earlySearch?.takeIf { hintedName in names.take(MAX_SEARCH_NAMES) }
                if (preferredSearch == null) earlySearch?.cancel()
                val match = withTimeoutOrNull(MATCH_TIMEOUT_MS) {
                    val evidence = async(start = CoroutineStart.LAZY) { appleArtistSourceSongs(info, tracks(), names) }
                    try {
                        findAppleArtist(
                            catalog, names,
                            onLeadCandidate = { candidate ->
                                evidence.start()
                                if (prefetch?.first != candidate) {
                                    prefetch?.second?.cancel()
                                    // The match deadline must not wait for speculative artwork.
                                    prefetch = candidate to this@resolve.async {
                                        appleArtworkResult { catalog.artistLogo(candidate.id, candidate.storefront, candidate.pageUrl) }
                                    }
                                }
                            },
                            searchArtists = { name, storefront ->
                                val ready = if (name == hintedName && storefront == STOREFRONTS.first()) {
                                    preferredSearch?.await()
                                } else null
                                ready ?: catalog.searchArtists(name, storefront)
                            }
                        ) { evidence.await() }
                    } finally {
                        evidence.cancel()
                    }
                }
                currentCoroutineContext().ensureActive()
                if (match == null) {
                    cacheLogo(ref, null, RETRY_DELAY_MS, prefetchOnly = playbackPrefetch)
                    return@resolve null
                }
                CachedMapping(match, System.currentTimeMillis()).also { saveMapping(ref, it) }
            }
            val identity = mapping.identity
            if (prefetch?.first != identity) {
                prefetch?.second?.cancel()
                prefetch = null
            }
            val result = findAppleArtistLogo(catalog, identity, prefetch?.second)
            val logo = result.getOrNull()
            currentCoroutineContext().ensureActive()
            val legacyArtwork = mapping.artwork
            if (result.isFailure && legacyArtwork?.logo != null) {
                // Keep a valid legacy logo when its metadata refresh fails, then retry after a short delay.
                val currentTime = System.currentTimeMillis()
                if (currentTime < legacyArtwork.expiresAt) {
                    artworkCache.put(ref, legacyArtwork.copy(
                        expiresAt = minOf(legacyArtwork.expiresAt, currentTime + RETRY_DELAY_MS),
                    ))
                    return@resolve legacyArtwork.logo
                }
            }
            val artwork = cacheLogo(ref, logo, when {
                logo != null -> LOGO_CACHE_MS
                result.isSuccess -> NO_LOGO_CACHE_MS
                else -> RETRY_DELAY_MS
            }, prefetchOnly = playbackPrefetch && result.isFailure)
            if (result.isSuccess) {
                saveMapping(ref, mapping.copy(artwork = artwork))
            }
            logo
        } finally {
            earlySearch?.cancel()
            prefetch?.second?.cancel()
        }
    }

    private fun readMapping(ref: ArtistRef, now: Long): CachedMapping? {
        val entry = mappings.getString(ref.cacheKey(), null) ?: return null
        val fields = entry.substringBefore('\n').split(':', limit = 4)
        if (fields.size < 3) return null
        val confirmedAt = fields[0].toLongOrNull() ?: return null
        if (confirmedAt !in (now - MAPPING_CACHE_MS)..now) return null
        val storefront = fields[1].takeIf { it in STOREFRONTS } ?: return null
        val id = fields[2].takeIf(ARTIST_ID::matches) ?: return null
        val identity = AppleArtistIdentity(id, storefront, fields.getOrNull(3)?.takeIf(String::isNotEmpty))
        return CachedMapping(identity, confirmedAt, readCachedLogo(entry.substringAfter('\n', ""), now))
    }

    private fun readCachedLogo(value: String, now: Long): CachedLogo? {
        if (value.startsWith(NO_LOGO_PREFIX)) {
            val expiresAt = value.removePrefix(NO_LOGO_PREFIX).toLongOrNull()
                ?.takeIf { it > now && it - now <= NO_LOGO_CACHE_MS } ?: return null
            return CachedLogo(null, expiresAt)
        }
        val fields = value.substringBefore('\n').split(':', limit = 4)
        if (fields.size != 4) return null
        val expiresAt = fields[0].toLongOrNull()?.takeIf { it > now && it - now <= LOGO_CACHE_MS } ?: return null
        val width = fields[1].toIntOrNull() ?: return null
        val height = fields[2].toIntOrNull() ?: return null
        val artwork = validatedAppleArtistLogo(fields[3], width, height) ?: return null
        val keyColor = value.substringAfter('\n', "").takeIf { it.startsWith(KEY_COLOR_PREFIX) }
            ?.removePrefix(KEY_COLOR_PREFIX)
        val logo = artwork.copy(keyColorRgb = appleArtistKeyColorRgb(keyColor))
        return CachedLogo(logo, expiresAt, needsKeyColorRefresh = keyColor == null)
    }

    private fun saveMapping(ref: ArtistRef, mapping: CachedMapping) = synchronized(mappings) {
        val key = ref.cacheKey()
        val editor = mappings.edit()
        if (!mappings.contains(key)) {
            val entries = mappings.all
            if (entries.size >= MAX_CACHE_ENTRIES) {
                entries.entries.sortedBy { (_, value) -> (value as? String)?.substringBefore(':')?.toLongOrNull() ?: 0L }
                    .take(entries.size - MAX_CACHE_ENTRIES + 1).forEach { editor.remove(it.key) }
            }
        }
        val (id, storefront, pageUrl) = mapping.identity
        val artwork = mapping.artwork
        val logo = artwork?.logo
        val entry = "${mapping.confirmedAt}:$storefront:$id:${pageUrl.orEmpty()}"
        val suffix = when {
            artwork == null -> ""
            logo == null -> "\n$NO_LOGO_PREFIX${artwork.expiresAt}"
            else -> {
                val keyColor = logo.keyColorRgb?.toString(16)?.padStart(6, '0').orEmpty()
                "\n${artwork.expiresAt}:${logo.width}:${logo.height}:${logo.url}\n$KEY_COLOR_PREFIX$keyColor"
            }
        }
        editor.putString(key, entry + suffix).apply()
    }

    private fun cacheLogo(ref: ArtistRef, logo: AppleArtistLogo?, lifetime: Long, prefetchOnly: Boolean): CachedLogo =
        CachedLogo(logo, System.currentTimeMillis() + lifetime, prefetchOnly = prefetchOnly).also {
            artworkCache.put(ref, it)
        }

    private fun ArtistRef.cacheKey(): String = "${providerId.length}:$providerId$artistId"

    private data class CachedMapping(
        val identity: AppleArtistIdentity,
        val confirmedAt: Long,
        val artwork: CachedLogo? = null,
    )

    private data class CachedLogo(
        val logo: AppleArtistLogo?,
        val expiresAt: Long,
        val needsKeyColorRefresh: Boolean = false,
        val prefetchOnly: Boolean = false,
    )

    private class ArtistLock {
        val mutex = Mutex()
        var users = 0
    }
}

internal data class AppleArtistIdentity(val id: String, val storefront: String, val pageUrl: String? = null)

internal suspend fun findAppleArtistLogo(
    catalog: AppleArtistCatalog,
    identity: AppleArtistIdentity,
    prefetched: Deferred<Result<AppleArtistLogo?>>? = null,
): Result<AppleArtistLogo?> = coroutineScope {
    suspend fun lookup(storefront: String): Result<AppleArtistLogo?> =
        withTimeoutOrNull(LOGO_STOREFRONT_TIMEOUT_MS) {
            if (storefront == identity.storefront && prefetched != null) {
                prefetched.await()
            } else {
                appleArtworkResult {
                    catalog.artistLogo(identity.id, storefront, identity.pageUrl.takeIf { storefront == identity.storefront })
                }
            }
        } ?: Result.failure(IOException("Apple artist artwork lookup timed out"))

    val preferredStorefront = STOREFRONTS.first()
    val preferred = lookup(preferredStorefront)
    if (preferred.getOrNull() != null) return@coroutineScope preferred

    val alternatives = (listOf(identity.storefront) + STOREFRONTS).distinct().filterNot { it == preferredStorefront }
    val pending = alternatives.map { storefront -> async { lookup(storefront) } }
    var failure = preferred.exceptionOrNull()
    try {
        // Start the fallback requests together, then consume them in storefront order.
        for (request in pending) {
            val result = request.await()
            if (result.getOrNull() != null) return@coroutineScope result
            failure = failure ?: result.exceptionOrNull()
        }
        failure?.let { Result.failure(it) } ?: Result.success(null)
    } finally {
        pending.forEach { it.cancel() }
    }
}

private inline fun appleArtworkResult(load: () -> AppleArtistLogo?): Result<AppleArtistLogo?> =
    try {
        Result.success(load())
    } catch (error: IOException) {
        Result.failure(error)
    }

internal fun appleArtistSourceSongs(
    artist: SourceArtistInfo,
    tracks: List<Track>,
    names: List<String>,
): List<Track> {
    val acceptedNames = names.mapTo(mutableSetOf(), ::normalizedAppleArtistName)
    return tracks.filter { track ->
        track.ref.providerId == artist.ref.providerId && track.artists.any { credited ->
            credited.ref?.let { it == artist.ref } ?: (normalizedAppleArtistName(credited.name) in acceptedNames)
        }
    }
}

internal suspend fun findAppleArtist(
    catalog: AppleArtistCatalog,
    names: List<String>,
    onLeadCandidate: (AppleArtistIdentity) -> Unit = {},
    searchArtists: suspend (name: String, storefront: String) -> List<AppleArtistCandidate> = catalog::searchArtists,
    sourceSongs: suspend () -> List<Track>,
): AppleArtistIdentity? {
    val queries = names.take(MAX_SEARCH_NAMES)
    if (queries.isEmpty()) return null
    val acceptedNames = names.mapTo(mutableSetOf(), ::normalizedAppleArtistName)
    val unresolved = mutableSetOf<String>()
    var evidence: List<Track>? = null
    for (storefront in STOREFRONTS) {
        var prefetchedLead: AppleArtistCandidate? = null
        fun prefetch(candidate: AppleArtistCandidate) {
            if (candidate.pageUrl == null || candidate == prefetchedLead) return
            prefetchedLead = candidate
            onLeadCandidate(AppleArtistIdentity(candidate.id, storefront, candidate.pageUrl))
        }
        val candidates = try {
            coroutineScope {
                val pending = queries.map { name -> async { searchArtists(name, storefront) } }
                val primary = pending.first().await()
                primary.firstOrNull { normalizedAppleArtistName(it.name) in acceptedNames }?.let(::prefetch)
                primary + pending.drop(1).awaitAll().flatten()
            }
                .filter { normalizedAppleArtistName(it.name) in acceptedNames }
                .distinctBy(AppleArtistCandidate::id)
        } catch (_: IOException) {
            continue
        }
        if (candidates.isEmpty()) continue
        if (candidates.size > MAX_CANDIDATES) return null
        prefetch(candidates.first())
        val candidateIds = candidates.mapTo(linkedSetOf(), AppleArtistCandidate::id)
        val lookup = coroutineScope {
            val songs = async {
                try {
                    catalog.artistSongs(candidateIds, storefront)
                } catch (_: IOException) {
                    null
                }
            }
            val source = evidence ?: sourceSongs().also { evidence = it }
            if (!hasAppleArtistEvidence(source)) {
                songs.cancel()
                return@coroutineScope null
            }
            source to songs.await()
        } ?: return null
        val (source, songs) = lookup
        if (songs == null) {
            unresolved += candidateIds
            continue
        }
        val matches = candidates.filter { confirmsAppleArtist(source, songs[it.id].orEmpty()) }
        if (matches.size > 1) return null
        // A failed lookup cannot rule out a competing identity.
        val match = matches.singleOrNull()
        if (match != null) return AppleArtistIdentity(match.id, storefront, match.pageUrl).takeIf {
            unresolved.all { it == match.id }
        }
    }
    return null
}

private val STOREFRONTS = listOf("cn", "hk", "tw", "us", "jp")
private val ARTIST_ID = Regex("[1-9][0-9]{0,18}")
private const val KEY_COLOR_PREFIX = "keyColor="
private const val NO_LOGO_PREFIX = "noLogo="
private const val MAX_SEARCH_NAMES = 2
internal const val MAX_CANDIDATES = 8
private const val MAX_CACHE_ENTRIES = 256
private const val MATCH_TIMEOUT_MS = 20_000L
private const val LOGO_STOREFRONT_TIMEOUT_MS = 6_000L
private const val RETRY_DELAY_MS = 60_000L
private const val NO_LOGO_CACHE_MS = 30 * 60_000L
private const val LOGO_CACHE_MS = 6 * 60 * 60_000L
private const val MAPPING_CACHE_MS = 90 * 24 * 60 * 60_000L
