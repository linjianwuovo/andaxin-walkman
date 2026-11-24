package com.ljyh.mei.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.ljyh.mei.R
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UniversalWidgetConfigureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        setResult(RESULT_CANCELED, result)
        val manager = AppWidgetManager.getInstance(this)
        val provider = manager.getAppWidgetInfo(id)?.provider
        val titles = mapOf(
            ComponentName(this, UniversalMusicWidget::class.java) to R.string.widget_universal,
            ComponentName(this, UniversalMusicWideWidget::class.java) to R.string.widget_universal,
            ComponentName(this, MusicShortcutsWidget::class.java) to R.string.widget_shortcuts,
            ComponentName(this, MusicShortcutsWideWidget::class.java) to R.string.widget_shortcuts,
            ComponentName(this, MusicShortcutsLargeWidget::class.java) to R.string.widget_shortcuts,
        )
        val title = titles[provider]
        if (title == null) {
            finish()
            return
        }

        val initialSelection = UniversalWidgetSelection.read(this, id) ?: UniversalWidgetSelection.DAILY
        enableEdgeToEdge()
        setContent {
            var selected by rememberSaveable { mutableStateOf(initialSelection) }
            var saving by remember { mutableStateOf(false) }
            var saveFailed by remember { mutableStateOf(false) }

            WidgetConfigurePage(
                title = stringResource(title),
                saving = saving,
                canSave = true,
                onSave = {
                    if (manager.getAppWidgetInfo(id)?.provider != provider) {
                        finish()
                    } else {
                        saving = true
                        saveFailed = false
                        val selection = selected
                        lifecycleScope.launch {
                            val saved = withContext(Dispatchers.IO) {
                                UniversalWidgetSelection.save(applicationContext, id, selection)
                            }
                            if (saved) {
                                MusicWidgets.refresh(applicationContext, null)
                                setResult(RESULT_OK, result)
                                finish()
                            } else {
                                saveFailed = true
                                saving = false
                            }
                        }
                    }
                },
            ) {
                item {
                    GlasmioGroupedList(Modifier.selectableGroup()) {
                        UniversalWidgetSelection.entries.forEachIndexed { index, option ->
                            val active = option == selected
                            GlasmioListRow(
                                title = stringResource(option.label),
                                modifier = Modifier.selectable(
                                    selected = active,
                                    enabled = !saving,
                                    role = Role.RadioButton,
                                    onClick = { selected = option },
                                ),
                                enabled = !saving,
                                showTopSeparator = index > 0,
                                trailing = {
                                    if (active) {
                                        SfIcon("checkmark", null, size = 17.dp, tint = LocalGlassColors.current.accent)
                                    }
                                },
                            )
                        }
                    }
                }
                if (saveFailed) {
                    item {
                        Text(
                            stringResource(R.string.widget_collection_save_error),
                            style = IosTypography.footnote,
                            color = LocalGlassColors.current.destructive,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}
