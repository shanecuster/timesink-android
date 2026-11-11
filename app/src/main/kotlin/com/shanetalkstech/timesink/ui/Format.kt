package com.shanetalkstech.timesink.ui

import java.time.LocalDate
import java.time.temporal.IsoFields
import java.util.Locale

/**
 * Small text-formatting helpers for numbers and dates. They have no screen code
 * in them, so they are easy to read on their own.
 */

/**
 * Writes a number of seconds the way the terminal app does: `1h 05m` for an
 * hour or more, `45m` below that, and `0m` for nothing.
 *
 * Seconds are dropped (rounded down), because the screens show minutes.
 */
fun formatDuration(secs: Long): String {
    // `coerceAtLeast(0)` guards against a negative value ever being shown.
    val minutes = secs.coerceAtLeast(0) / 60
    val hours = minutes / 60
    // `%02d` pads the minutes to two digits (05, not 5).
    return if (hours > 0) String.format(Locale.US, "%dh %02dm", hours, minutes % 60)
    else "${minutes}m"
}

/**
 * The banner's date line for a local day number (days since 1970-01-01), for
 * example `Wednesday November 11 2026 · Week 46`.
 *
 * The week is the ISO week (weeks start on Monday and week 1 holds the first
 * Thursday), which is what most calendars call the week number and what the
 * terminal app shows.
 */
fun dateLine(day: Long): String {
    val date = LocalDate.ofEpochDay(day)
    // `getDisplayName`-style names are in the phone's language; the terminal app
    // is English, and so is this banner for now.
    val weekday = date.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
    val month = date.month.name.lowercase().replaceFirstChar { it.uppercase() }
    val week = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
    return String.format(Locale.US, "%s %s %02d %d · Week %d", weekday, month, date.dayOfMonth, date.year, week)
}

/**
 * A heat-map row label for a local day number: `Mon 09` (short weekday and
 * two-digit day of the month).
 */
fun dayLabel(day: Long): String {
    val date = LocalDate.ofEpochDay(day)
    val weekday = date.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
    return String.format(Locale.US, "%s %02d", weekday, date.dayOfMonth)
}

/** A month's name for the month bars: `Nov 2026` (month is 1 to 12). */
fun monthLabel(year: Int, month: Int): String {
    val name = java.time.Month.of(month).name.take(3).lowercase().replaceFirstChar { it.uppercase() }
    return "$name $year"
}
