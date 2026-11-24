package com.ljyh.mei.ui.screen.source

import androidx.compose.runtime.Composable
import io.github.neoruaa.glasmio.glass.rememberDetachedSharedGlassBackdrop
import io.github.neoruaa.glasmio.glass.render.Backdrop

internal const val SupportsDetachedLibraryShareBackdrop = true

@Composable
internal fun rememberLibraryShareBackdrop(
    backdrop: Backdrop,
    enabled: () -> Boolean,
): Backdrop = rememberDetachedSharedGlassBackdrop(backdrop, enabled)
