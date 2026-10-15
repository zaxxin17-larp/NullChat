package com.nullsec.nullchat.media

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.tasks.await

/**
 * Enforces the per-user daily upload count and total storage quota with
 * Firestore transactions.
 *
 *   users/{uid}                         { storageBytes: Long, storageQuota: Long }
 *   users/{uid}/uploadStats/{yyyyMMdd}  { count: Int }
 *
 * Reservation happens AFTER compression (so the size is exact) and BEFORE the
 * Storage upload. If the upload fails, [release] refunds it.
 *
 * ⚠️ Because this runs on the client, a modified app could skip it. For a
 * tamper-proof version move [reserve]/[release] into a Callable Cloud Function
 * and set the counters to server-write-only in firestore.rules.
 */
object UploadLimiter {

    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()

    private val dayFormat = SimpleDateFormat("yyyyMMdd", Locale.US)

    fun dayKey(now: Long = System.currentTimeMillis()): String = dayFormat.format(Date(now))

    suspend fun reserve(uid: String, sizeBytes: Long) {
        val userRef = db.collection("users").document(uid)
        val dayRef = userRef.collection("uploadStats").document(dayKey())

        db.runTransaction { txn ->
            val user = txn.get(userRef)
            val used = user.getLong("storageBytes") ?: 0L
            val quota = user.getLong("storageQuota") ?: MediaLimits.USER_QUOTA_BYTES

            if (used + sizeBytes > quota) {
                val leftMb = (quota - used).coerceAtLeast(0) / 1_048_576
                throw QuotaExceededException(
                    "Storage full — only $leftMb MB left of ${quota / 1_048_576} MB"
                )
            }

            val day = txn.get(dayRef)
            val count = day.getLong("count") ?: 0L
            if (count >= MediaLimits.DAILY_UPLOAD_LIMIT) {
                throw DailyLimitException(
                    "Daily limit reached (${MediaLimits.DAILY_UPLOAD_LIMIT} uploads). Try again tomorrow."
                )
            }

            txn.set(
                userRef,
                mapOf("storageBytes" to used + sizeBytes, "storageQuota" to quota),
                SetOptions.merge()
            )
            txn.set(dayRef, mapOf("count" to count + 1), SetOptions.merge())
        }.await()
    }

    suspend fun release(uid: String, sizeBytes: Long) {
        val userRef = db.collection("users").document(uid)
        val dayRef = userRef.collection("uploadStats").document(dayKey())

        db.runTransaction { txn ->
            val user = txn.get(userRef)
            val used = user.getLong("storageBytes") ?: 0L
            val day = txn.get(dayRef)
            val count = day.getLong("count") ?: 0L
            txn.set(
                userRef,
                mapOf("storageBytes" to (used - sizeBytes).coerceAtLeast(0)),
                SetOptions.merge()
            )
            txn.set(dayRef, mapOf("count" to (count - 1).coerceAtLeast(0)), SetOptions.merge())
        }.await()
    }

    /** Stamps a server timestamp on a "usage" doc for the ops dashboard (optional). */
    fun markUsageStamp(uid: String): Map<String, Any> =
        mapOf("lastUploadAt" to FieldValue.serverTimestamp(), "uid" to uid)
}
