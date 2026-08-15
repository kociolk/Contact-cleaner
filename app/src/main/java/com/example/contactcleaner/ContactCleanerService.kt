package com.example.contactcleaner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

private const val TAG = "ContactCleanerService"
private const val CHANNEL_ID = "contact_cleaner_channel"
private const val NOTIF_ID = 1001
private const val WORK_NAME = "contact_cleaner_periodic_work"

class ContactCleanerService : Service() {

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            Log.i(TAG, "Contacts changed (selfChange=$selfChange) -> scanning")
            cleanBlacklistedNumbers()
        }

        override fun onChange(selfChange: Boolean, uri: android.net.Uri?) {
            super.onChange(selfChange, uri)
            Log.i(TAG, "Contacts changed uri=$uri -> scanning")
            cleanBlacklistedNumbers()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")

        // Register observer
        contentResolver.registerContentObserver(
            ContactsContract.Data.CONTENT_URI,
            true,
            observer
        )

        // Schedule periodic backup worker (in case service is killed by OEM)
        try {
            val periodicRequest = PeriodicWorkRequestBuilder<ContactCleanupWorker>(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicRequest
            )
        } catch (t: Throwable) {
            Log.w(TAG, "WorkManager scheduling failed: ${t.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand")
        createNotificationChannelIfNeeded()
        val notification: Notification = buildNotification()
        startForeground(NOTIF_ID, notification)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        contentResolver.unregisterContentObserver(observer)
        Log.i(TAG, "onDestroy: observer unregistered")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Contact Cleaner",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Monitors contacts and removes blacklisted numbers"
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Contact Cleaner")
            .setContentText("Monitoring contacts to remove blacklisted numbers")
            .setSmallIcon(android.R.drawable.ic_menu_delete)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun cleanBlacklistedNumbers() {
        try {
            val prefs = getSharedPreferences("CleanerPrefs", Context.MODE_PRIVATE)
            val blacklisted = prefs.getStringSet("blacklisted_numbers", emptySet()) ?: emptySet()
            if (blacklisted.isEmpty()) {
                Log.i(TAG, "No blacklisted numbers set")
                return
            }

            Log.i(TAG, "Cleaning blacklisted numbers: ${blacklisted.size}")
            for (targetNumber in blacklisted) {
                deletePhoneNumberRows(targetNumber)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "cleanBlacklistedNumbers error: ${t.message}", t)
        }
    }

    private fun deletePhoneNumberRows(targetNumber: String) {
        val resolver = contentResolver
        val normalizedTarget = PhoneNumberUtils.normalizeNumber(targetNumber) ?: targetNumber

        val projection = arrayOf(
            ContactsContract.Data._ID,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.Data.MIMETYPE} = ?"
        val selectionArgs = arrayOf(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)

        resolver.query(ContactsContract.Data.CONTENT_URI, projection, selection, selectionArgs, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.Data._ID)
            val numIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)

            val toDeleteIds = mutableListOf<Long>()

            while (cursor.moveToNext()) {
                val rowId = cursor.getLong(idIdx)
                val rawNumber = cursor.getString(numIdx) ?: continue
                val norm = PhoneNumberUtils.normalizeNumber(rawNumber) ?: rawNumber

                // Match exact normalized or suffix match (last digits) to handle country codes
                if (norm == normalizedTarget || norm.endsWith(normalizedTarget) || normalizedTarget.endsWith(norm)) {
                    toDeleteIds.add(rowId)
                    Log.i(TAG, "Marked for deletion: raw='$rawNumber' norm='$norm' target='$normalizedTarget' rowId=$rowId")
                }
            }

            for (id in toDeleteIds) {
                try {
                    val where = "${ContactsContract.Data._ID} = ?"
                    val whereArgsDel = arrayOf(id.toString())
                    val deleted = resolver.delete(ContactsContract.Data.CONTENT_URI, where, whereArgsDel)
                    Log.i(TAG, "Deleted data row id=$id deletedCount=$deleted")
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to delete data row id=$id: ${t.message}")
                }
            }
        }
    }
}
