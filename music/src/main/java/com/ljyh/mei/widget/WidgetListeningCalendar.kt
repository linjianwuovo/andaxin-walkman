package com.ljyh.mei.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.widget.RemoteViews
import com.ljyh.mei.R
import com.ljyh.mei.data.model.melox.ListeningStatsPeriod
import com.ljyh.mei.di.AppDatabase
import com.ljyh.mei.source.api.AccountRevisionCapability
import com.ljyh.mei.source.api.SourceFeatureCapability
import com.ljyh.mei.source.core.ProviderRegistry
import com.ljyh.mei.source.core.SourcePreferences
import com.ljyh.mei.source.shared.SharedOnlineRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.withTimeoutOrNull

internal data class WidgetListeningCalendar(
    val date: LocalDate,
    val activeDays: Set<Int>,
    val listenedDays: Int = activeDays.size,
    val durationMinutes: Long? = null,
)

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface WidgetListeningDependencies {
    fun sourcePreferences(): SourcePreferences
    fun providerRegistry(): ProviderRegistry
    fun onlineRepository(): SharedOnlineRepository
}

private val statsZone = ZoneId.of("Asia/Shanghai")

@OptIn(ExperimentalCoroutinesApi::class)
internal fun observeWidgetListeningCalendar(context: Context): Flow<WidgetListeningCalendar> {
    val dependencies = EntryPointAccessors.fromApplication(context, WidgetListeningDependencies::class.java)
    val registry = dependencies.providerRegistry()
    val preferred = dependencies.sourcePreferences().settings.map { it.preferredBrowseProvider }.distinctUntilChanged()
    val online = combine(preferred, registry.revision) { providerId, _ -> registry.provider(providerId) }
        .distinctUntilChanged { previous, current -> previous === current }
        .flatMapLatest { provider ->
            if (provider !is SourceFeatureCapability || "listeningStatsReport" !in provider.features["stats"].orEmpty()) {
                return@flatMapLatest flowOf<WidgetListeningCalendar?>(null)
            }
            val revisions = (provider as? AccountRevisionCapability)?.accountRevision ?: flowOf(null)
            combine(revisions, calendarDates { statsZone }) { revision, (_, date) -> revision to date }
                .distinctUntilChanged()
                .transformLatest { (_, date) ->
                    emit(null)
                    val value = withTimeoutOrNull(6_000L) {
                        try {
                            dependencies.onlineRepository().screen("widget-listening", provider.id)
                                .listeningStatsReport(ListeningStatsPeriod.Month)
                                .let { report ->
                                    val month = YearMonth.from(date)
                                    WidgetListeningCalendar(
                                        date = date,
                                        activeDays = report.bins.asSequence().filter { it.durationMinutes > 0 }
                                            .mapNotNull { runCatching { LocalDate.parse(it.period) }.getOrNull() }
                                            .filter { YearMonth.from(it) == month && !it.isAfter(date) }
                                            .map(LocalDate::getDayOfMonth).toSet(),
                                        listenedDays = report.listenedDays.coerceIn(0, date.dayOfMonth),
                                        durationMinutes = report.totalMinutes.coerceAtLeast(0),
                                    )
                                }
                        } catch (error: CancellationException) {
                            currentCoroutineContext().ensureActive()
                            null
                        } catch (_: Exception) {
                            null
                        }
                    }
                    if (value != null) emit(value)
                }
        }
    return online.flatMapLatest { value ->
        if (value != null) flowOf(value) else observeLocalListeningCalendar(context)
    }.distinctUntilChanged()
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun observeLocalListeningCalendar(context: Context): Flow<WidgetListeningCalendar> {
    val dao = AppDatabase.getDatabase(context).historyDao()
    return calendarDates(ZoneId::systemDefault).flatMapLatest { (zone, date) ->
        val start = date.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        dao.observeListeningDays(start, end).map { days ->
            WidgetListeningCalendar(date, days.toSet())
        }
    }
}

private fun calendarDates(zone: () -> ZoneId): Flow<Pair<ZoneId, LocalDate>> = flow {
    while (true) {
        val now = ZonedDateTime.now(zone())
        val date = now.toLocalDate()
        emit(now.zone to date)
        val next = date.plusDays(1).atStartOfDay(now.zone)
        delay(Duration.between(ZonedDateTime.now(now.zone), next).toMillis().coerceAtLeast(1))
    }
}

internal fun widgetListeningCalendarViews(context: Context, value: WidgetListeningCalendar?): RemoteViews =
    RemoteViews(context.packageName, R.layout.widget_listening_calendar).apply {
        val destination = if (value?.durationMinutes != null) MusicWidgets.ACTION_OPEN_STATS else MusicWidgets.ACTION_OPEN_HISTORY
        setOnClickPendingIntent(R.id.widget_root, MusicWidgets.open(context, destination))
        if (value != null) {
            val locale = context.resources.configuration.locales[0]
            val monthFormat = DateTimeFormatter.ofPattern("yyyy/MM", locale)
            setTextViewText(R.id.widget_calendar_month, value.date.format(monthFormat))
            val days = context.resources.getQuantityString(
                R.plurals.widget_listening_days, value.listenedDays, value.listenedDays,
            )
            val minutes = value.durationMinutes
            val (duration, unit) = when {
                minutes == null -> value.listenedDays.toLong() to R.string.widget_listening_unit_days
                minutes < 60 -> minutes to R.string.widget_listening_unit_minutes
                else -> (minutes / 60) to R.string.widget_listening_unit_hours
            }
            val label = if (minutes != null) R.string.widget_listening_monthly else R.string.widget_listening_local
            setTextViewText(R.id.widget_calendar_label, context.getString(label))
            setTextViewText(R.id.widget_calendar_duration, NumberFormat.getIntegerInstance(locale).format(duration))
            setTextViewText(R.id.widget_calendar_unit, context.getString(unit))
            setTextViewText(R.id.widget_calendar_summary, if (minutes != null) days else "")
            setImageViewBitmap(R.id.widget_calendar_days, ListeningCalendarGrid.bitmap(context, locale, value))
            setContentDescription(
                R.id.widget_calendar_days,
                context.getString(R.string.widget_listening_active_dates, value.activeDays.sorted().joinToString(", ")),
            )
        }
    }

private object ListeningCalendarGrid {
    private data class Key(val locale: Locale, val date: LocalDate, val activeDays: Set<Int>)

    private var current: Pair<Key, Bitmap>? = null

    @Synchronized
    fun bitmap(context: Context, locale: Locale, value: WidgetListeningCalendar): Bitmap {
        val key = Key(locale, value.date, value.activeDays)
        current?.takeIf { it.first == key }?.let { return it.second }
        val bitmap = draw(context, key)
        current = key to bitmap
        return bitmap
    }

    private fun draw(context: Context, key: Key): Bitmap {
        val scale = 3
        val bitmap = Bitmap.createBitmap(160 * scale, 130 * scale, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(scale.toFloat(), scale.toFloat())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = 8f
            typeface = Typeface.DEFAULT_BOLD
            color = 0xB3283248.toInt()
        }
        val textOffset = -(paint.ascent() + paint.descent()) / 2f
        val weekdayStyle = if (key.locale.language in setOf("zh", "ja", "ko")) TextStyle.NARROW else TextStyle.SHORT
        for (column in 0 until 7) {
            val label = DayOfWeek.SUNDAY.plus(column.toLong()).getDisplayName(weekdayStyle, key.locale)
            canvas.drawText(label, 8f + 24f * column, 8f + textOffset, paint)
        }

        val options = BitmapFactory.Options().apply { inScaled = false }
        val markers = listOf(
            R.drawable.widget_ncm_calendar_active,
            R.drawable.widget_ncm_calendar_today_active,
            R.drawable.widget_ncm_calendar_today_empty,
        ).map { BitmapFactory.decodeResource(context.resources, it, options) }
        val month = YearMonth.from(key.date)
        val offset = month.atDay(1).dayOfWeek.value % 7
        val area = RectF()
        val numbers = NumberFormat.getIntegerInstance(key.locale)
        for (day in 1..month.lengthOfMonth()) {
            val index = offset + day - 1
            val x = 8f + 24f * (index % 7)
            val y = 27f + 19f * (index / 7)
            val active = day in key.activeDays
            val today = day == key.date.dayOfMonth
            val marker = when {
                today && active -> markers[1]
                today -> markers[2]
                active -> markers[0]
                else -> null
            }
            marker?.let {
                area.set(x - 8f, y - 8f, x + 8f, y + 8f)
                paint.color = Color.WHITE
                canvas.drawBitmap(it, null, area, paint)
            }
            if (!today) {
                paint.color = when {
                    active -> Color.WHITE
                    day > key.date.dayOfMonth -> 0x33283248
                    else -> 0xB3283248.toInt()
                }
                canvas.drawText(numbers.format(day), x, y + textOffset, paint)
            }
        }
        return bitmap
    }
}
