package com.ljyh.mei.ui.local

import androidx.compose.runtime.compositionLocalOf
import androidx.datastore.preferences.core.Preferences

val LocalAppPreferences = compositionLocalOf<Preferences?> { null }
