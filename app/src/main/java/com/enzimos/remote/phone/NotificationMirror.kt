package com.enzimos.remote.phone

import android.app.Notification
import android.app.NotificationManager
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import com.enzimos.remote.connection.ConnectionManager
import com.enzimos.remote.settings.Preferences
import com.enzimos.remote.signaling.NotificationActionInfo
import com.enzimos.remote.signaling.PhoneNotificationMessage
import com.enzimos.remote.signaling.PhoneNotificationRemovedMessage
import com.enzimos.remote.signaling.encodeClientMessage
import java.io.ByteArrayOutputStream

/**
 * Mirrors this phone's notifications to connected computers (KDE Connect
 * style) and runs the actions chosen there: reply, buttons, dismiss.
 *
 * While Android keeps this listener bound the app process stays alive, so the
 * computer links stay up in the background too (notifications, find my phone).
 */
class NotificationMirror : NotificationListenerService() {
    private val preferences by lazy { Preferences(this) }
    private val connections get() = ConnectionManager.get(this)
    private val iconCache = HashMap<String, String?>()
    /** Last content sent per key, so unchanged updates are not resent. */
    private val sent = HashMap<String, Int>()
    private var linked = false

    override fun onListenerConnected() {
        instance = this
        updateLink()
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        if (linked) connections.stopBackgroundLink()
        linked = false
    }

    /** Keep links up in the background only while mirroring is on. */
    fun updateLink() {
        val wanted = preferences.mirrorNotifications
        if (wanted && !linked) connections.startBackgroundLink()
        if (!wanted && linked) connections.stopBackgroundLink()
        linked = wanted
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!preferences.mirrorNotifications || !mirrorable(sbn)) return
        val n = sbn.notification
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val actions = n.actions.orEmpty().take(MAX_ACTIONS).mapIndexed { index, action ->
            NotificationActionInfo(
                id = index.toString(),
                title = action.title?.toString()?.take(120).orEmpty().ifBlank { "Action" },
                reply = action.remoteInputs?.any { it.allowFreeFormInput } == true,
            )
        }
        val signature = listOf(title, text, actions).hashCode()
        if (sent[sbn.key] == signature) return
        sent[sbn.key] = signature
        val message = PhoneNotificationMessage(
            key = sbn.key.take(500),
            app = sbn.packageName,
            appName = appName(sbn.packageName),
            title = title.take(1000),
            text = text.take(8000),
            icon = icon(sbn.packageName),
            actions = actions,
        )
        broadcast(encodeClientMessage(message))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sent.remove(sbn.key) == null) return
        broadcast(encodeClientMessage(PhoneNotificationRemovedMessage(key = sbn.key.take(500))))
    }

    /** Run an action chosen on a computer; reply text goes into its RemoteInput. */
    fun invoke(key: String, actionId: String, text: String?) {
        val sbn = runCatching { getActiveNotifications(arrayOf(key)) }.getOrNull()?.firstOrNull() ?: return
        val action = sbn.notification.actions?.getOrNull(actionId.toIntOrNull() ?: -1) ?: return
        try {
            val inputs = action.remoteInputs
            if (text != null && inputs != null && inputs.isNotEmpty()) {
                val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
                val intent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                RemoteInput.addResultsToIntent(inputs, intent, results)
                action.actionIntent.send(this, 0, intent)
            } else {
                action.actionIntent.send()
            }
        } catch (error: Exception) {
            Log.w(TAG, "Could not run notification action: ${error.message}")
        }
    }

    fun dismiss(key: String) {
        sent.remove(key)
        runCatching { cancelNotification(key) }
    }

    private fun mirrorable(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        if (sbn.packageName == packageName || sbn.isOngoing) return false
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        if (n.category in SKIPPED_CATEGORIES) return false
        // Silent / minimized notifications stay on the phone.
        val ranking = Ranking()
        if (currentRanking?.getRanking(sbn.key, ranking) == true && ranking.importance < NotificationManager.IMPORTANCE_DEFAULT) return false
        return true
    }

    private fun broadcast(json: String) {
        connections.hosts.value.filter { it.status == ConnectionManager.Status.ONLINE }
            .forEach { connections.connection(it.hostId)?.client?.sendRaw(json) }
    }

    private fun appName(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun icon(pkg: String): String? = iconCache.getOrPut(pkg) {
        runCatching {
            val bitmap = packageManager.getApplicationIcon(pkg).toBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888)
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }.getOrNull()?.takeIf { it.length < 30_000 }
    }

    companion object {
        private const val TAG = "NotificationMirror"
        private const val ICON_SIZE = 64
        private const val MAX_ACTIONS = 6
        private val SKIPPED_CATEGORIES = setOf(
            Notification.CATEGORY_PROGRESS, Notification.CATEGORY_SERVICE, Notification.CATEGORY_TRANSPORT,
            Notification.CATEGORY_SYSTEM, Notification.CATEGORY_NAVIGATION,
        )

        @Volatile var instance: NotificationMirror? = null
            private set

        fun hasAccess(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
            val me = ComponentName(context, NotificationMirror::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }

        fun accessSettings(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }
}
