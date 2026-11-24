package com.ljyh.mei.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import coil3.compose.AsyncImage
import com.ljyh.mei.R
import com.ljyh.mei.data.model.room.Playlist
import com.ljyh.mei.data.model.room.PlaylistType
import com.ljyh.mei.di.AppDatabase
import com.ljyh.mei.source.api.AccountRevisionCapability
import com.ljyh.mei.source.api.LibraryCapability
import com.ljyh.mei.source.api.LibraryCollection
import com.ljyh.mei.source.api.PageRequest
import com.ljyh.mei.source.api.ProviderIds
import com.ljyh.mei.source.api.SourceCapability
import com.ljyh.mei.source.api.SourceItem
import com.ljyh.mei.source.core.SourcePreferences
import com.ljyh.mei.source.core.SourceRouter
import com.ljyh.mei.ui.screen.main.library.component.groupedLazyItems
import dagger.hilt.android.AndroidEntryPoint
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class WidgetCollectionConfigureActivity : ComponentActivity() {
    @Inject lateinit var sourceRouter: SourceRouter
    @Inject lateinit var sourcePreferences: SourcePreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        setResult(RESULT_CANCELED, result)
        val manager = AppWidgetManager.getInstance(this)
        val provider = manager.getAppWidgetInfo(id)?.provider
        val limit = when (provider) {
            ComponentName(this, PinnedMusicWidget::class.java) -> 4
            ComponentName(this, PinnedMusicLargeWidget::class.java) -> WidgetCollection.MAX_SELECTION
            else -> {
                finish()
                return
            }
        }

        enableEdgeToEdge()
        setContent {
            val options by produceState<CollectionOptions?>(null, id) {
                try {
                    val (local, initialSelection) = withContext(Dispatchers.IO) {
                        val initialSelection = WidgetCollection.read(this@WidgetCollectionConfigureActivity, id).take(limit)
                        localCollections() to initialSelection
                    }
                    val cachedEntries = (local + initialSelection).distinctBy { it.id }
                    if (cachedEntries.isNotEmpty()) {
                        value = CollectionOptions(cachedEntries, initialSelection)
                    }
                    val remote = withContext(Dispatchers.IO) { remoteCollections() }
                    value = CollectionOptions((local + remote + initialSelection).distinctBy { it.id }, initialSelection)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Timber.w(error, "Could not load playlists for widget configuration")
                    if (value == null) value = CollectionOptions(emptyList(), emptyList(), failed = true)
                }
            }

            CollectionPicker(options, limit) { selected ->
                if (manager.getAppWidgetInfo(id)?.provider != provider) {
                    finish()
                    return@CollectionPicker false
                }
                val saved = withContext(Dispatchers.IO) {
                    WidgetCollection.save(this@WidgetCollectionConfigureActivity, id, selected.take(limit))
                }
                if (saved) {
                    MusicWidgets.refresh(applicationContext, null)
                    setResult(RESULT_OK, result)
                    finish()
                }
                saved
            }
        }
    }

    private suspend fun localCollections(): List<WidgetCollection> =
        AppDatabase.getDatabase(this).playlistDao().getAllPlaylist().first()
            .filter { playlist ->
                playlist.providerId == ProviderIds.LOCAL &&
                    (playlist.type == PlaylistType.USER || playlist.type == PlaylistType.FOLDER)
            }
            .sortedWith(compareByDescending<Playlist> { it.lastPlayTime }.thenBy { it.title })
            .map { WidgetCollection(it.id, it.title, it.cover) }

    private suspend fun remoteCollections(): List<WidgetCollection> =
        withTimeoutOrNull(8_000L) {
            sourceRouter.registry.awaitInitialized()
            val providerId = sourcePreferences.snapshot().preferredBrowseProvider
            val provider = sourceRouter.registry.provider(providerId) ?: return@withTimeoutOrNull emptyList()
            val library = provider as? LibraryCapability ?: return@withTimeoutOrNull emptyList()
            if (SourceCapability.PLAYLIST !in provider.capabilities) return@withTimeoutOrNull emptyList()
            val revisions = (provider as? AccountRevisionCapability)?.accountRevision
            val revision = revisions?.first()
            val entries = mutableListOf<WidgetCollection>()
            val collections = listOf(LibraryCollection.CREATED_PLAYLISTS, LibraryCollection.SAVED_PLAYLISTS)
            for (collection in collections.filter { it in library.collections }) {
                try {
                    var offset = 0
                    var cursor: String? = null
                    val seen = HashSet<String>()
                    do {
                        val page = sourceRouter.library(providerId, collection, PageRequest(offset, 100, cursor))
                        val playlists = page.items.filterIsInstance<SourceItem.PlaylistItem>().map { it.playlist }
                        val newEntries = playlists.filter { seen.add(it.ref.playlistId) }
                        entries += newEntries.map { WidgetCollection(encodeWidgetContent(it.ref), it.title, it.artworkUrl) }
                        offset += page.items.size
                        cursor = page.nextCursor
                    } while (page.hasMore && newEntries.isNotEmpty())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Timber.w(error, "Could not load %s for widget configuration", collection)
                }
            }
            val current = sourceRouter.registry.provider(providerId) === provider && revisions?.first() == revision
            if (current) entries else emptyList()
        }.orEmpty()
}

private data class CollectionOptions(
    val entries: List<WidgetCollection>,
    val initialSelection: List<WidgetCollection>,
    val failed: Boolean = false,
)

private val CollectionDraftSaver = Saver<List<WidgetCollection>?, ArrayList<String>>(
    save = { entries -> entries?.flatMapTo(ArrayList()) { listOf(it.id, it.title, it.artwork) } },
    restore = { values -> values.chunked(3).map { (id, title, artwork) -> WidgetCollection(id, title, artwork) } },
)

@Composable
private fun WidgetCollectionConfigureActivity.CollectionPicker(
    options: CollectionOptions?,
    limit: Int,
    onSave: suspend (List<WidgetCollection>) -> Boolean,
) {
    var draft by rememberSaveable(stateSaver = CollectionDraftSaver) {
        mutableStateOf<List<WidgetCollection>?>(null)
    }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    val selected = draft ?: options?.initialSelection.orEmpty()
    val entries = remember(options, selected) {
        (options?.entries.orEmpty() + selected).distinctBy { it.id }
    }

    WidgetConfigurePage(
        title = stringResource(R.string.widget_pins),
        saving = saving,
        canSave = selected.isNotEmpty(),
        onSave = {
            saving = true
            saveFailed = false
            lifecycleScope.launch {
                saveFailed = !onSave(selected)
                saving = false
            }
        },
    ) {
        when {
            entries.isEmpty() && options == null -> item {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = LocalGlassColors.current.accent,
                        strokeWidth = 2.dp,
                    )
                }
            }
            entries.isEmpty() -> item {
                GlasmioGroupedList {
                    GlasmioListRow(
                        title = stringResource(
                            if (options?.failed == true) R.string.widget_collection_load_error
                            else R.string.widget_collection_empty,
                        ),
                        showTopSeparator = false,
                    )
                }
            }
            else -> {
                item {
                    val colors = LocalGlassColors.current
                    Text(
                        text = if (saveFailed) stringResource(R.string.widget_collection_save_error)
                        else stringResource(R.string.widget_collection_limit, limit),
                        style = IosTypography.footnote,
                        color = if (saveFailed) colors.destructive else colors.secondaryContent,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    )
                }
                groupedLazyItems(entries, key = { it.id }) { entry, index ->
                    val colors = LocalGlassColors.current
                    val isSelected = selected.any { it.id == entry.id }
                    val canToggle = !saving && (isSelected || selected.size < limit)
                    GlasmioListRow(
                        title = entry.title,
                        modifier = Modifier.toggleable(
                            value = isSelected,
                            enabled = canToggle,
                            role = Role.Checkbox,
                            onValueChange = { checkedEntry ->
                                val current = draft ?: options?.initialSelection.orEmpty()
                                if (!checkedEntry) {
                                    draft = current.filterNot { it.id == entry.id }
                                } else if (current.size < limit && current.none { it.id == entry.id }) {
                                    draft = current + entry
                                }
                            },
                        ),
                        enabled = canToggle,
                        showTopSeparator = index > 0,
                        leading = {
                            val placeholder = painterResource(R.drawable.widget_artwork_placeholder)
                            AsyncImage(
                                model = entry.artwork.takeIf(String::isNotBlank),
                                contentDescription = null,
                                placeholder = placeholder,
                                fallback = placeholder,
                                error = placeholder,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(36.dp).clip(MaterialTheme.shapes.medium),
                            )
                        },
                        trailing = {
                            SfIcon(
                                if (isSelected) "checkmark.circle.fill" else "circle",
                                contentDescription = null,
                                size = 20.dp,
                                tint = if (isSelected) colors.accent else colors.tertiaryContent,
                            )
                        },
                    )
                }
            }
        }
    }
}
