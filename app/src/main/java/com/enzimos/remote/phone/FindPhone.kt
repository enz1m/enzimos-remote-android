package com.enzimos.remote.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.enzimos.remote.R
import com.enzimos.remote.databinding.ActivityFindPhoneBinding

/** Find my phone: rings at alarm volume until "Found it" (or two minutes). */
object FindPhone {
    private const val CHANNEL = "enzremote_find_phone"
    private const val NOTIFICATION_ID = 7402
    private const val TIMEOUT_MS = 120_000L
    private var player: MediaPlayer? = null
    private var savedVolume = -1
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { stopNow() }
    private lateinit var appContext: Context

    val ringing: Boolean get() = player != null

    fun start(context: Context) = handler.post {
        appContext = context.applicationContext
        if (player != null) return@post
        val audio = appContext.getSystemService(AudioManager::class.java)
        savedVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(appContext, uri)
                isLooping = true
                prepare()
                start()
            }
        }.getOrNull()
        vibrator().vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 400), 0))
        showNotification()
        // In front of the app if it is open; otherwise the notification has it.
        runCatching { appContext.startActivity(Intent(appContext, FoundItActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        handler.postDelayed(timeout, TIMEOUT_MS)
    }

    fun stop(context: Context) = handler.post { appContext = context.applicationContext; stopNow() }

    private fun stopNow() {
        handler.removeCallbacks(timeout)
        player?.run { runCatching { stop() }; release() }
        player = null
        if (::appContext.isInitialized) {
            vibrator().cancel()
            if (savedVolume >= 0) {
                appContext.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
                savedVolume = -1
            }
            appContext.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }
    }

    private fun vibrator(): Vibrator = if (Build.VERSION.SDK_INT >= 31) {
        appContext.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION") appContext.getSystemService(Vibrator::class.java)
    }

    private fun showNotification() {
        val manager = appContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, appContext.getString(R.string.find_phone_channel),
            NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) })
        val stop = PendingIntent.getBroadcast(appContext, 0, Intent(appContext, StopReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(appContext, 0, Intent(appContext, FoundItActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        manager.notify(NOTIFICATION_ID, Notification.Builder(appContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_transfer)
            .setContentTitle(appContext.getString(R.string.find_phone_title))
            .setContentText(appContext.getString(R.string.find_phone_text))
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .addAction(Notification.Action.Builder(null, appContext.getString(R.string.find_phone_found), stop).build())
            .build())
    }

    class StopReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            stop(context)
        }
    }

    /** Big "Found it" screen, shown over the lock screen. */
    class FoundItActivity : AppCompatActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            if (Build.VERSION.SDK_INT >= 27) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            } else {
                @Suppress("DEPRECATION")
                window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            }
            val binding = ActivityFindPhoneBinding.inflate(layoutInflater)
            setContentView(binding.root)
            binding.foundIt.setOnClickListener {
                stop(this)
                finish()
            }
        }

        override fun onResume() {
            super.onResume()
            if (!ringing) handler.postDelayed({ if (!ringing) finish() }, 300)
        }
    }
}
