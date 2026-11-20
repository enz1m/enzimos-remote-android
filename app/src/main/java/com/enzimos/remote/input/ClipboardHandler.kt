package com.enzimos.remote.input

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.util.Log

/** Bridges plain-text clipboard changes and suppresses echoes of text received from the host. */
class ClipboardHandler(context: Context) {

    private val clipboardManager =
        context.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    /** Whether clipboard sync is enabled. */
    @Volatile var enabled: Boolean = true

    /** Callback invoked when local clipboard changes (client → host). */
    @Volatile var onClipboardChanged: ((String) -> Unit)? = null

    /** Last text set by the host, to avoid echo loops. */
    @Volatile private var lastHostText: String? = null

    /** Last text read from local clipboard, to detect changes. */
    @Volatile private var lastLocalText: String? = null

    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        if (!enabled) return@OnPrimaryClipChangedListener
        val text = getLocalClipboardText() ?: return@OnPrimaryClipChangedListener

        if (text == lastHostText) return@OnPrimaryClipChangedListener

        if (text == lastLocalText) return@OnPrimaryClipChangedListener

        lastLocalText = text
        onClipboardChanged?.invoke(text)
    }

    /**
     * Start listening for local clipboard changes.
     */
    fun start() {
        clipboardManager.addPrimaryClipChangedListener(listener)
    }

    /**
     * Stop listening for local clipboard changes.
     */
    fun stop() {
        clipboardManager.removePrimaryClipChangedListener(listener)
    }

    /**
     * Set text received from the host onto the local clipboard.
     *
     * @param text The text from the remote session's clipboard
     */
    fun setFromHost(text: String) {
        if (!enabled) return

        // Limit clipboard text size to avoid DoS via huge clipboard payloads.
        val trimmed = if (text.length > MAX_CLIPBOARD_LENGTH) {
            Log.w(TAG, "Clipboard text truncated from ${text.length} to $MAX_CLIPBOARD_LENGTH")
            text.substring(0, MAX_CLIPBOARD_LENGTH)
        } else {
            text
        }

        lastHostText = trimmed
        val clip = ClipData.newPlainText(CLIP_LABEL, trimmed)
        try {
            clipboardManager.setPrimaryClip(clip)
        } catch (e: SecurityException) {
            // On Android 10+ (API 29), setting clipboard from the background
            // throws SecurityException. This can happen in PiP mode or when
            // the app momentarily loses focus.
            Log.w(TAG, "Cannot set clipboard (app not in foreground): ${e.message}")
        }
    }

    /**
     * Get the current local clipboard text, if any.
     *
     * Returns null if the clipboard is empty or does not contain text.
     */
    fun getLocalClipboardText(): String? {
        val clip = clipboardManager.primaryClip ?: return null
        if (clip.itemCount == 0) return null

        val desc = clip.description
        if (desc != null && !desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) &&
            !desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
        ) {
            return null
        }

        return clip.getItemAt(0)?.text?.toString()
    }

    /**
     * Build the JSON control message for clipboard text.
     *
     * @param text The clipboard text to send
     * @return JSON string for the data channel
     */
    fun buildClipboardMessage(text: String): String {
        return """{"type":"clipboard","v":1,"text":"${escapeJson(text)}"}"""
    }

    companion object {
        private const val TAG = "ClipboardHandler"

        /** Label for clipboard items set by the remote session. */
        const val CLIP_LABEL = "enzimos remote"

        /** Maximum clipboard text length (64 KiB). */
        const val MAX_CLIPBOARD_LENGTH = 65_536

        /** Escape a string for JSON embedding. */
        internal fun escapeJson(text: String): String {
            val sb = StringBuilder(text.length + 16)
            for (ch in text) {
                when (ch) {
                    '\\' -> sb.append("\\\\")
                    '"' -> sb.append("\\\"")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    '\b' -> sb.append("\\b")
                    '\u000C' -> sb.append("\\f")
                    else -> {
                        if (ch.code < 0x20) {
                            sb.append("\\u")
                            sb.append(String.format(java.util.Locale.US, "%04x", ch.code))
                        } else {
                            sb.append(ch)
                        }
                    }
                }
            }
            return sb.toString()
        }
    }
}
