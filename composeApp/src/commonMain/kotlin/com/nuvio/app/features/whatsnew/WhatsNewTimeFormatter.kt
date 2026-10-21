package com.nuvio.app.features.whatsnew

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.periodUntil
import kotlinx.datetime.plus
import kotlin.time.Instant

internal enum class LastCheckedUnit {
    JUST_NOW,
    MINUTE,
    HOUR,
    DAY,
    WEEK,
    MONTH,
    YEAR,
}

internal data class LastCheckedAge(
    val amount: Long,
    val unit: LastCheckedUnit,
)

internal fun calculateLastCheckedAge(
    fetchedAtMillis: Long,
    nowMillis: Long,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): LastCheckedAge {
    val effectiveNowMillis = nowMillis.coerceAtLeast(fetchedAtMillis)
    val elapsedMillis = effectiveNowMillis - fetchedAtMillis

    val minuteMillis = 60_000L
    val hourMillis = 60L * minuteMillis
    val dayMillis = 24L * hourMillis

    if (elapsedMillis < minuteMillis) {
        return LastCheckedAge(amount = 0L, unit = LastCheckedUnit.JUST_NOW)
    }

    if (elapsedMillis < hourMillis) {
        return LastCheckedAge(
            amount = elapsedMillis / minuteMillis,
            unit = LastCheckedUnit.MINUTE,
        )
    }

    val start = Instant.fromEpochMilliseconds(fetchedAtMillis)
    val end = Instant.fromEpochMilliseconds(effectiveNowMillis)
    val calendarPeriod = start.periodUntil(end, timeZone)
    // periodUntil keeps Jan 31 -> Feb 28 at 0 months, but a month added to Jan 31 lands on Feb 28,
    // so count that month as complete.
    val periodMonths = calendarPeriod.years * 12L + calendarPeriod.months
    val totalMonths = if (start.plus(periodMonths + 1, DateTimeUnit.MONTH, timeZone) <= end) {
        periodMonths + 1
    } else {
        periodMonths
    }

    return when {
        totalMonths >= 12 -> LastCheckedAge(
            amount = totalMonths / 12,
            unit = LastCheckedUnit.YEAR,
        )
        totalMonths >= 1 -> LastCheckedAge(
            amount = totalMonths,
            unit = LastCheckedUnit.MONTH,
        )
        calendarPeriod.days >= 7 -> LastCheckedAge(
            amount = (calendarPeriod.days / 7L),
            unit = LastCheckedUnit.WEEK,
        )
        calendarPeriod.days >= 1 -> LastCheckedAge(
            amount = calendarPeriod.days.toLong(),
            unit = LastCheckedUnit.DAY,
        )
        elapsedMillis >= dayMillis -> LastCheckedAge(
            amount = elapsedMillis / dayMillis,
            unit = LastCheckedUnit.DAY,
        )
        else -> LastCheckedAge(
            amount = elapsedMillis / hourMillis,
            unit = LastCheckedUnit.HOUR,
        )
    }
}