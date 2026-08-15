package com.example.contactcleaner

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import android.util.Log

private const val TAG = "ContactCleanupWorker"

class ContactCleanupWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.i(TAG, "Periodic cleanup worker running")
        return try {
            val prefs = applicationContext.getSharedPreferences("CleanerPrefs", Context.MODE_PRIVATE)
            val blacklisted = prefs.getStringSet("blacklisted_numbers", emptySet()) ?: emptySet()
            if (blacklisted.isNotEmpty()) {
                val serviceLike = ContactCleanerService()
                // We cannot call service methods directly; replicate deletion logic here
                // Simple approach: query and delete similarly to service implementation
                val resolver = applicationContext.contentResolver
                val projection = arrayOf(
                    android.provider.ContactsContract.Data._ID,
                    android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
                )
                val selection = "${android.provider.ContactsContract.Data.MIMETYPE} = ?"
                val selectionArgs = arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)

                resolver.query(android.provider.ContactsContract.Data.CONTENT_URI, projection, selection, selectionArgs, null)?.use { cursor ->
                    val idIdx = cursor.getColumnIndexOrThrow(android.provider.ContactsContract.Data._ID)
                    val numIdx = cursor.getColumnIndexOrThrow(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)

                    val toDeleteIds = mutableListOf<Long>()

                    while (cursor.moveToNext()) {
                        val rowId = cursor.getLong(idIdx)
                        val rawNumber = cursor.getString(numIdx) ?: continue
                        val norm = android.telephony.PhoneNumberUtils.normalizeNumber(rawNumber) ?: rawNumber

                        for (target in blacklisted) {
                            val normalizedTarget = android.telephony.PhoneNumberUtils.normalizeNumber(target) ?: target
                            if (norm == normalizedTarget || norm.endsWith(normalizedTarget) || normalizedTarget.endsWith(norm)) {
                                toDeleteIds.add(rowId)
                                Log.i(TAG, "Worker: marked for deletion rowId=$rowId raw='$rawNumber' norm='$norm' target='$normalizedTarget'")
                                break
                            }
                        }
                    }

                    for (id in toDeleteIds) {
                        try {
                            val where = "${android.provider.ContactsContract.Data._ID} = ?"
                            val whereArgsDel = arrayOf(id.toString())
                            val deleted = resolver.delete(android.provider.ContactsContract.Data.CONTENT_URI, where, whereArgsDel)
                            Log.i(TAG, "Worker: deleted data row id=$id deletedCount=$deleted")
                        } catch (t: Throwable) {
                            Log.w(TAG, "Worker: failed to delete id=$id: ${t.message}")
                        }
                    }
                }
            } else {
                Log.i(TAG, "Worker: no blacklisted numbers configured")
            }

            Result.success()
        } catch (t: Throwable) {
            Log.e(TAG, "Worker failed: ${t.message}", t)
            Result.retry()
        }
    }
}
