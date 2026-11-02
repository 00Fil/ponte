package it.ponte

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.util.Calendar
import java.util.TimeZone

/** Un calendario del telefono (di solito un account Google). */
data class DevCal(val id: Long, val name: String, val account: String, val color: Int, val writable: Boolean)

/** Un evento letto dai calendari del telefono. */
data class DevEvent(val id: Long, val calId: Long, val title: String, val start: Long, val end: Long, val allDay: Boolean, val color: Int, val place: String, val note: String)

/**
 * Google Calendar tramite il fornitore di calendari di Android: gli account Google già presenti sul telefono sono sincronizzati dal sistema,
 * quindi non serve nessun accesso. Leggendo e scrivendo qui, gli eventi arrivano su Google e viceversa.
 */
class CalendarBridge(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("plan_local", Context.MODE_PRIVATE)

    fun granted() = ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
        ctx.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Calendari nascosti dalla vista (di default tutti visibili). */
    var hidden: Set<Long>
        get() = prefs.getStringSet("cal_hidden", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
        set(v) = prefs.edit().putStringSet("cal_hidden", v.map { it.toString() }.toSet()).apply()

    /** Dove salvare i nuovi eventi: 0 = solo Relay, altrimenti l'id del calendario. */
    var target: Long
        get() = prefs.getLong("cal_target", 0L)
        set(v) = prefs.edit().putLong("cal_target", v).apply()

    fun calendars(): List<DevCal> {
        if (!granted()) return emptyList()
        val out = ArrayList<DevCal>()
        try {
            ctx.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.CALENDAR_COLOR, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL),
                null, null, CalendarContract.Calendars.ACCOUNT_NAME,
            )?.use { c ->
                while (c.moveToNext()) out += DevCal(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getInt(3), c.getInt(4) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR)
            }
        } catch (_: Exception) {}
        return out
    }

    private fun utcToLocal(t: Long): Long {
        val u = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = t }
        return Calendar.getInstance().apply { clear(); set(u.get(Calendar.YEAR), u.get(Calendar.MONTH), u.get(Calendar.DAY_OF_MONTH)) }.timeInMillis
    }

    private fun localToUtc(t: Long): Long {
        val l = Calendar.getInstance().apply { timeInMillis = t }
        return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(l.get(Calendar.YEAR), l.get(Calendar.MONTH), l.get(Calendar.DAY_OF_MONTH)) }.timeInMillis
    }

    /** Eventi (già espansi per le ripetizioni) fra [from] e [to], dei calendari non nascosti. */
    fun events(from: Long, to: Long): List<DevEvent> {
        if (!granted()) return emptyList()
        val hide = hidden
        val out = ArrayList<DevEvent>()
        try {
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, from); ContentUris.appendId(it, to) }.build()
            ctx.contentResolver.query(
                uri,
                arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.CALENDAR_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.CALENDAR_COLOR, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.DESCRIPTION, CalendarContract.Instances.EVENT_COLOR),
                null, null, CalendarContract.Instances.BEGIN,
            )?.use { c ->
                while (c.moveToNext()) {
                    val cal = c.getLong(1)
                    if (cal in hide) continue
                    val all = c.getInt(5) == 1
                    val b = if (all) utcToLocal(c.getLong(3)) else c.getLong(3)
                    val e = if (all) utcToLocal(c.getLong(4)) else c.getLong(4)
                    val col = if (!c.isNull(9) && c.getInt(9) != 0) c.getInt(9) else c.getInt(6)
                    out += DevEvent(c.getLong(0), cal, c.getString(2).orEmpty().ifBlank { "(senza titolo)" }, b, e, all, col, c.getString(7).orEmpty(), c.getString(8).orEmpty())
                }
            }
        } catch (_: Exception) {}
        return out
    }

    /** Crea un evento nel calendario [calId]; ritorna l'id o -1. */
    fun insert(calId: Long, title: String, start: Long, end: Long, allDay: Boolean, place: String, note: String, remind: Int): Long {
        if (!granted()) return -1
        return try {
            val v = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.EVENT_LOCATION, place)
                put(CalendarContract.Events.DESCRIPTION, note)
                if (allDay) {
                    put(CalendarContract.Events.ALL_DAY, 1); put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
                    put(CalendarContract.Events.DTSTART, localToUtc(start)); put(CalendarContract.Events.DTEND, localToUtc(end).coerceAtLeast(localToUtc(start) + 86_400_000L))
                } else {
                    put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                    put(CalendarContract.Events.DTSTART, start); put(CalendarContract.Events.DTEND, end)
                }
            }
            val id = ContentUris.parseId(ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v) ?: return -1)
            if (remind >= 0) ctx.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, id); put(CalendarContract.Reminders.MINUTES, remind); put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            })
            id
        } catch (_: Exception) { -1 }
    }

    fun update(id: Long, title: String, start: Long, end: Long, allDay: Boolean, place: String, note: String): Boolean = try {
        val v = ContentValues().apply {
            put(CalendarContract.Events.TITLE, title); put(CalendarContract.Events.EVENT_LOCATION, place); put(CalendarContract.Events.DESCRIPTION, note)
            if (allDay) { put(CalendarContract.Events.DTSTART, localToUtc(start)); put(CalendarContract.Events.DTEND, localToUtc(end).coerceAtLeast(localToUtc(start) + 86_400_000L)) }
            else { put(CalendarContract.Events.DTSTART, start); put(CalendarContract.Events.DTEND, end) }
        }
        ctx.contentResolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), v, null, null) > 0
    } catch (_: Exception) { false }

    fun delete(id: Long): Boolean = try {
        ctx.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null) > 0
    } catch (_: Exception) { false }

    /** Registra un osservatore: quando Google sincronizza qualcosa di nuovo, [onChange] parte. */
    fun observe(onChange: () -> Unit): android.database.ContentObserver? {
        if (!granted()) return null
        val o = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) { override fun onChange(selfChange: Boolean) = onChange() }
        return try { ctx.contentResolver.registerContentObserver(CalendarContract.Events.CONTENT_URI, true, o); o } catch (_: Exception) { null }
    }
    fun unobserve(o: android.database.ContentObserver?) { if (o != null) try { ctx.contentResolver.unregisterContentObserver(o) } catch (_: Exception) {} }
}
