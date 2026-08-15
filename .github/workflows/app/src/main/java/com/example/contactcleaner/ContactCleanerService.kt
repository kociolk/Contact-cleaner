package com.example.contactcleaner

import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.ContactsContract

class ContactCleanerService : Service() {

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            cleanBlacklistedNumbers()
        }
    }

    override fun onCreate() {
        super.onCreate()
        contentResolver.registerContentObserver(
            ContactsContract.Contacts.CONTENT_URI,
            true,
            observer
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        contentResolver.unregisterContentObserver(observer)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun cleanBlacklistedNumbers() {
        val prefs = getSharedPreferences("CleanerPrefs", Context.MODE_PRIVATE)
        val blacklisted = prefs.getStringSet("blacklisted_numbers", emptySet()) ?: return

        for (targetNumber in blacklisted) {
            deleteOnlyPhoneNumber(targetNumber)
        }
    }

    private fun deleteOnlyPhoneNumber(targetNumber: String) {
        val resolver = contentResolver

        val selection = "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"
        val selectionArgs = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            "%$targetNumber%"
        )

        resolver.delete(
            ContactsContract.Data.CONTENT_URI,
            selection,
            selectionArgs
        )
    }
}
