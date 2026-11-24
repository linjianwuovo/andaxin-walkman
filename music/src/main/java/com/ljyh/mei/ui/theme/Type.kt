package com.ljyh.mei.ui.theme

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font as PlatformFont
import android.graphics.fonts.FontFamily as PlatformFontFamily
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.ljyh.mei.R

private val sfProWeights = listOf(
    FontWeight.Light,
    FontWeight.Normal,
    FontWeight.Medium,
    FontWeight.SemiBold,
    FontWeight.Bold,
)

private class SfProFont(override val weight: FontWeight) : AndroidFont(
    loadingStrategy = FontLoadingStrategy.Blocking,
    typefaceLoader = SfProFontLoader,
    variationSettings = FontVariation.Settings(),
) {
    override val style = FontStyle.Normal
}

private object SfProFontLoader : AndroidFont.TypefaceLoader {
    private var regularFont: PlatformFont? = null
    private var textTypeface: Typeface? = null
    private var symbolTypeface: Typeface? = null

    private fun getRegularFont(context: Context): PlatformFont =
        regularFont ?: PlatformFont.Builder(context.resources, R.font.sf_pro).build()
            .also { regularFont = it }

    @Synchronized
    fun getSymbolTypeface(context: Context): Typeface {
        symbolTypeface?.let { return it }
        val regular = getRegularFont(context)
        // Symbols keep their single-face weight resolution and share the text font's buffer.
        return Typeface.CustomFallbackBuilder(PlatformFontFamily.Builder(regular).build())
            .setStyle(regular.style)
            .build()
            .also { symbolTypeface = it }
    }

    @Synchronized
    private fun getTextTypeface(context: Context): Typeface {
        textTypeface?.let { return it }

        val regular = getRegularFont(context)
        val family = PlatformFontFamily.Builder(regular)
        for (weight in sfProWeights) {
            if (weight == FontWeight.Normal) continue
            // Apply the variation to SF Pro alone. Android chooses the system fallback by style.
            family.addFont(
                PlatformFont.Builder(regular)
                    .setWeight(weight.weight)
                    .setFontVariationSettings("'wght' ${weight.weight}")
                    .build(),
            )
        }
        return Typeface.CustomFallbackBuilder(family.build()).build().also { textTypeface = it }
    }

    override fun loadBlocking(context: Context, font: AndroidFont): Typeface =
        Typeface.create(getTextTypeface(context), font.weight.weight, false)

    override suspend fun awaitLoad(context: Context, font: AndroidFont): Typeface =
        loadBlocking(context, font)
}

internal fun sfProSymbolTypeface(context: Context): Typeface =
    SfProFontLoader.getSymbolTypeface(context)

val AppFontFamily = FontFamily(sfProWeights.map(::SfProFont))

/** Shared typography for Material controls, iOS components, inputs, and lyrics. */
fun appTypography(letterSpacing: TextUnit): Typography {
    val base = TextStyle(fontFamily = AppFontFamily, letterSpacing = letterSpacing)
    return Typography(
        displayLarge = base,
        displayMedium = base,
        displaySmall = base,
        headlineLarge = base.copy(fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 41.sp),
        headlineMedium = base.copy(fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp),
        headlineSmall = base.copy(fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
        titleLarge = base.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 25.sp),
        titleMedium = base.copy(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
        titleSmall = base.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
        bodyLarge = base.copy(fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 22.sp),
        bodyMedium = base.copy(fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 20.sp),
        bodySmall = base.copy(fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
        labelLarge = base.copy(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
        labelMedium = base.copy(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
        labelSmall = base.copy(fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 12.sp),
    )
}
