package com.enzimos.remote.phone

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import com.enzimos.remote.connection.ConnectionManager
import com.enzimos.remote.settings.Preferences
import com.enzimos.remote.signaling.SignalingClient
import com.enzimos.remote.signaling.SmsRequestMessage
import com.enzimos.remote.signaling.SmsResultMessage
import com.enzimos.remote.signaling.SmsStatusMessage
import com.enzimos.remote.signaling.encodeClientMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.Executors

/**
 * Text messages for computers: answers `sms_request` (conversations, one
 * thread, send) from the phone's SMS store, and tells computers when
 * conversations change so their Messages window refreshes. Opt-in in
 * settings; needs SMS (and optionally contacts) permission. MMS is not
 * included.
 */
class SmsBridge private constructor(private val app: Application) {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val connections get() = ConnectionManager.get(app)
    private var observer: ContentObserver? = null
    private val names = HashMap<String, String?>()

    fun enabled(): Boolean = Preferences(app).smsSharing && hasPermission(app)

    fun onConnected(client: SignalingClient) {
        updateObserver()
        if (enabled()) client.sendRaw(encodeClientMessage(SmsStatusMessage(enabled = true)))
    }

    /** The setting or permission changed: tell every connected computer. */
    fun settingChanged() {
        updateObserver()
        broadcast(SmsStatusMessage(enabled = enabled()))
    }

    fun handle(hostId: String, message: SmsRequestMessage) {
        val client = connections.connection(hostId)?.client ?: return
        io.execute {
            val result = if (!enabled()) error("Text messages are turned off on the phone")
            else runCatching { answer(message.request) }.getOrElse {
                Log.w(TAG, "SMS request failed: ${it::class.simpleName}")
                error(it.message ?: "Failed")
            }
            client.sendRaw(encodeClientMessage(SmsResultMessage(requestId = message.requestId, result = result)))
        }
    }

    private fun answer(request: JsonObject): JsonObject {
        fun str(key: String) = request[key]?.jsonPrimitive?.contentOrNull
        fun int(key: String) = request[key]?.jsonPrimitive?.intOrNull
        return when (str("op")) {
            "conversations" -> ok("conversations", conversations((int("limit") ?: 50).coerceIn(1, 200)))
            "thread" -> {
                val thread = request["thread_id"]?.jsonPrimitive?.longOrNull ?: return error("thread_id is required")
                ok("messages", thread(thread, (int("limit") ?: 80).coerceIn(1, 300)))
            }
            "send" -> {
                val address = str("address")?.trim().orEmpty()
                val text = str("text").orEmpty()
                if (address.isEmpty() || address.length > 64 || text.isBlank() || text.length > 5000) {
                    return error("Give a phone number and a message")
                }
                send(address, text)
                buildJsonObject { put("ok", true) }
            }
            else -> error("Unknown request")
        }
    }

    private fun conversations(limit: Int): JsonArray {
        data class Conversation(val thread: Long, val address: String, val snippet: String, val date: Long, val sent: Boolean, var unread: Int)
        val byThread = LinkedHashMap<Long, Conversation>()
        val projection = arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ)
        app.contentResolver.query(Telephony.Sms.CONTENT_URI, projection, null, null, "${Telephony.Sms.DATE} DESC")?.use { cursor ->
            var scanned = 0
            while (cursor.moveToNext() && scanned++ < 5000) {
                val thread = cursor.getLong(0)
                val unread = cursor.getInt(5) == 0 && cursor.getInt(4) == Telephony.Sms.MESSAGE_TYPE_INBOX
                val known = byThread[thread]
                if (known != null) {
                    if (unread) known.unread++
                    continue
                }
                if (byThread.size >= limit) continue
                byThread[thread] = Conversation(thread, cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty().take(160),
                    cursor.getLong(3), cursor.getInt(4) != Telephony.Sms.MESSAGE_TYPE_INBOX, if (unread) 1 else 0)
            }
        }
        return buildJsonArray {
            for (c in byThread.values) add(buildJsonObject {
                put("thread_id", c.thread)
                put("address", c.address)
                name(c.address)?.let { put("name", it) }
                put("snippet", c.snippet)
                put("date", c.date)
                put("sent", c.sent)
                put("unread", c.unread)
            })
        }
    }

    private fun thread(thread: Long, limit: Int): JsonArray {
        val rows = ArrayList<JsonObject>()
        val projection = arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE)
        app.contentResolver.query(Telephony.Sms.CONTENT_URI, projection, "${Telephony.Sms.THREAD_ID} = ?",
            arrayOf(thread.toString()), "${Telephony.Sms.DATE} DESC")?.use { cursor ->
            var size = 0
            // Stay well inside the 128 KiB signaling message limit.
            while (cursor.moveToNext() && rows.size < limit && size < 90_000) {
                val body = cursor.getString(2).orEmpty().take(4000)
                size += body.length * 2 + 120
                val type = cursor.getInt(4)
                rows += buildJsonObject {
                    put("id", cursor.getLong(0))
                    put("address", cursor.getString(1).orEmpty())
                    put("body", body)
                    put("date", cursor.getLong(3))
                    put("sent", type != Telephony.Sms.MESSAGE_TYPE_INBOX)
                    put("status", when (type) {
                        Telephony.Sms.MESSAGE_TYPE_OUTBOX, Telephony.Sms.MESSAGE_TYPE_QUEUED -> "sending"
                        Telephony.Sms.MESSAGE_TYPE_FAILED -> "failed"
                        Telephony.Sms.MESSAGE_TYPE_DRAFT -> "draft"
                        else -> "done"
                    })
                }
            }
        }
        rows.reverse()
        return JsonArray(rows)
    }

    @Suppress("DEPRECATION")
    private fun send(address: String, text: String) {
        val manager = if (Build.VERSION.SDK_INT >= 31) app.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
        val parts = manager.divideMessage(text)
        // The system records messages sent by non-default SMS apps in Sent.
        if (parts.size > 1) manager.sendMultipartTextMessage(address, null, parts, null, null)
        else manager.sendTextMessage(address, null, text, null, null)
    }

    /** Contact name for a number, when contacts permission was granted. */
    private fun name(address: String): String? {
        if (address.isEmpty() || app.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return synchronized(names) { names.getOrPut(address) {
            runCatching {
                val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
                app.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            }.getOrNull()
        } }
    }

    private fun updateObserver() {
        val wanted = enabled()
        if (wanted && observer == null) {
            val observer = object : ContentObserver(main) {
                private val notify = Runnable {
                    synchronized(names) { names.clear() }
                    broadcast(SmsStatusMessage(enabled = true, changed = true))
                }
                override fun onChange(selfChange: Boolean) {
                    // A received or sent text touches several rows: report once.
                    main.removeCallbacks(notify)
                    main.postDelayed(notify, 800)
                }
            }
            app.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
            this.observer = observer
        } else if (!wanted) {
            observer?.let { app.contentResolver.unregisterContentObserver(it) }
            observer = null
        }
    }

    private fun broadcast(message: SmsStatusMessage) {
        val json = encodeClientMessage(message)
        connections.hosts.value.filter { it.status == ConnectionManager.Status.ONLINE }
            .forEach { connections.connection(it.hostId)?.client?.sendRaw(json) }
    }

    private fun ok(key: String, value: JsonArray) = buildJsonObject {
        put("ok", true)
        put(key, value)
    }

    private fun error(message: String) = buildJsonObject {
        put("ok", false)
        put("error", JsonPrimitive(message))
    }

    companion object {
        private const val TAG = "SmsBridge"
        val PERMISSIONS = arrayOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS)

        fun hasPermission(context: Context) =
            context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

        @Volatile private var instance: SmsBridge? = null

        fun get(context: Context): SmsBridge = instance ?: synchronized(this) {
            instance ?: SmsBridge(context.applicationContext as Application).also { instance = it }
        }
    }
}
