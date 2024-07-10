package com.builttoroam.devicecalendar

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.CalendarContract
import com.builttoroam.devicecalendar.common.Constants
import com.builttoroam.devicecalendar.common.DayOfWeek
import com.builttoroam.devicecalendar.common.RecurrenceFrequency
import com.builttoroam.devicecalendar.models.Availability
import com.builttoroam.devicecalendar.models.Calendar
import com.builttoroam.devicecalendar.models.EventStatus
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import io.flutter.plugin.common.EventChannel

fun getBackgroundHandler(): Handler {
    val handlerThread = HandlerThread("CalendarObserver")
    handlerThread.start()
    return Handler(handlerThread.looper)
}

class CalendarChangeObserver(private val context: Context, private val eventSink: EventChannel.EventSink) : ContentObserver(Handler(getBackgroundHandler().looper)) {

    private var _gson: Gson? = null
    private val calendarContentUri: Uri = CalendarContract.Calendars.CONTENT_URI

    init {
        context.contentResolver.registerContentObserver(calendarContentUri, true, this)
        val gsonBuilder = GsonBuilder()
        gsonBuilder.registerTypeAdapter(RecurrenceFrequency::class.java, RecurrenceFrequencySerializer())
        gsonBuilder.registerTypeAdapter(DayOfWeek::class.java, DayOfWeekSerializer())
        gsonBuilder.registerTypeAdapter(Availability::class.java, AvailabilitySerializer())
        gsonBuilder.registerTypeAdapter(EventStatus::class.java, EventStatusSerializer())
        _gson = gsonBuilder.create()
    }

    override fun onChange(selfChange: Boolean) {
        super.onChange(selfChange)
        fetchAndSendCalendarData()
    }

    fun fetchAndSendCalendarData() {
        val calendarData = queryAllCalendarData()
        if (calendarData != null) {
            // Ensure this runs on the main thread
            Handler(Looper.getMainLooper()).post {
                eventSink.success(calendarData)
            }
        }
    }

    private fun atLeastAPI(api: Int): Boolean {
        return api <= android.os.Build.VERSION.SDK_INT
    }

    private fun queryAllCalendarData(): String? {
        val contentResolver: ContentResolver? = context.contentResolver
        val uri: Uri = CalendarContract.Calendars.CONTENT_URI
        val cursor: Cursor? = if (atLeastAPI(17)) {
            contentResolver?.query(uri, Constants.CALENDAR_PROJECTION, null, null, null)
        } else {
            contentResolver?.query(uri, Constants.CALENDAR_PROJECTION_OLDER_API, null, null, null)
        }
        val calendars: MutableList<Calendar> = mutableListOf()
        try {
            while (cursor?.moveToNext() == true) {
                val calendar = parseCalendarRow(cursor) ?: continue
                calendars.add(calendar)
            }
            return _gson?.toJson(calendars)
        } catch (e: Exception) {
            return null
        } finally {
            cursor?.close()
        }
    }

    private fun parseCalendarRow(cursor: Cursor?): Calendar? {
        if (cursor == null) {
            return null
        }

        val calId = cursor.getLong(Constants.CALENDAR_PROJECTION_ID_INDEX)
        val displayName = cursor.getString(Constants.CALENDAR_PROJECTION_DISPLAY_NAME_INDEX)
        val accessLevel = cursor.getInt(Constants.CALENDAR_PROJECTION_ACCESS_LEVEL_INDEX)
        val calendarColor = cursor.getInt(Constants.CALENDAR_PROJECTION_COLOR_INDEX)
        val accountName = cursor.getString(Constants.CALENDAR_PROJECTION_ACCOUNT_NAME_INDEX)
        val accountType = cursor.getString(Constants.CALENDAR_PROJECTION_ACCOUNT_TYPE_INDEX)
        val ownerAccount = cursor.getString(Constants.CALENDAR_PROJECTION_OWNER_ACCOUNT_INDEX)

        val calendar = Calendar(
                calId.toString(),
                displayName,
                calendarColor,
                accountName,
                accountType,
                ownerAccount
        )

        calendar.isReadOnly = isCalendarReadOnly(accessLevel)
        if (atLeastAPI(17)) {
            val isPrimary = cursor.getString(Constants.CALENDAR_PROJECTION_IS_PRIMARY_INDEX)
            calendar.isDefault = isPrimary == "1"
        } else {
            calendar.isDefault = false
        }
        return calendar
    }

    private fun isCalendarReadOnly(accessLevel: Int): Boolean {
        return when (accessLevel) {
            CalendarContract.Events.CAL_ACCESS_CONTRIBUTOR,
            CalendarContract.Events.CAL_ACCESS_ROOT,
            CalendarContract.Events.CAL_ACCESS_OWNER,
            CalendarContract.Events.CAL_ACCESS_EDITOR -> false

            else -> true
        }
    }
}