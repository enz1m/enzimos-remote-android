package com.enzimos.remote.share

import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.enzimos.remote.connection.ConnectionManager
import com.enzimos.remote.settings.Preferences
import com.enzimos.remote.signaling.ClipboardSyncRequest
import com.enzimos.remote.signaling.ClipboardText
import com.enzimos.remote.signaling.FILE_CHUNK_SIZE
import com.enzimos.remote.signaling.FileCancelRequest
import com.enzimos.remote.signaling.FileChunkRequest
import com.enzimos.remote.signaling.FileCompleteRequest
import com.enzimos.remote.signaling.FileMessage
import com.enzimos.remote.signaling.FileOfferRequest
import com.enzimos.remote.signaling.FilePullRequest
import com.enzimos.remote.signaling.FileReceivedRequest
import com.enzimos.remote.signaling.HostMessage
import com.enzimos.remote.signaling.MAX_CLIPBOARD_TEXT
import com.enzimos.remote.signaling.SignalingClient
import com.enzimos.remote.signaling.encodeClientMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * File transfers and clipboard sharing with paired computers (KDE Connect
 * style), over each computer's pinned signaling link.
 *
 * Sending: `file_offer`, then up to [WINDOW] `file_chunk`s in flight, each
 * acknowledged by `file_progress`, then `file_complete` with the SHA-256.
 * Receiving: the computer's `file_offer` is accepted automatically into
 * Downloads; pieces are pulled with up to [WINDOW] `file_pull`s in flight and
 * verified before the file becomes visible.
 */
class ShareManager private constructor(private val app: Application) {
    enum class Direction { SEND, RECEIVE }
    enum class Phase { ACTIVE, DONE, FAILED }

    data class Transfer(
        val id: String,
        val hostId: String,
        val hostName: String,
        val name: String,
        val size: Long,
        val direction: Direction,
        val done: Long = 0,
        val phase: Phase = Phase.ACTIVE,
        val error: String? = null,
        /** Opens the received file. */
        val uri: Uri? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val replies = ConcurrentHashMap<String, Channel<FileMessage>>()
    private val _transfers = MutableStateFlow<List<Transfer>>(emptyList())
    val transfers: StateFlow<List<Transfer>> = _transfers.asStateFlow()
    private val connections get() = ConnectionManager.get(app)
    private val preferences by lazy { Preferences(app) }
    private val notifications by lazy { TransferNotifications(app) }
    private val clipboard by lazy { app.getSystemService(ClipboardManager::class.java) }
    /** Text that came from a computer, so it is not sent straight back. */
    @Volatile private var lastRemoteText: String? = null
    @Volatile private var lastClipStamp = 0L
    private var clipboardObserved = false

    // ── Connection events (from ConnectionManager.HostConnection) ──

    fun onConnected(client: SignalingClient) {
        client.sendRaw(encodeClientMessage(ClipboardSyncRequest(enabled = preferences.clipboardSharing)))
    }

    /** Tell every connected computer whether to send its clipboard. */
    fun clipboardSharingChanged() {
        val json = encodeClientMessage(ClipboardSyncRequest(enabled = preferences.clipboardSharing))
        onlineHosts(null).forEach { connections.connection(it.hostId)?.client?.sendRaw(json) }
    }

    fun onMessage(hostId: String, message: HostMessage) {
        when (message) {
            is HostMessage.File -> {
                val file = message.msg
                if (file.type == "file_offer") receive(hostId, file)
                else replies[file.transferId]?.trySend(file)
            }
            is HostMessage.Clipboard -> if (preferences.clipboardSharing) setPhoneClipboard(message.msg.text)
            else -> Unit
        }
    }

    // ── Clipboard ──

    private fun setPhoneClipboard(text: String) {
        lastRemoteText = text
        Log.i(TAG, "Clipboard from computer (${text.length} characters)")
        scope.launch(Dispatchers.Main) {
            runCatching {
                clipboard.setPrimaryClip(ClipData.newPlainText("enzimOS Remote", text))
                lastClipStamp = clipboard.primaryClipDescription?.timestamp ?: lastClipStamp
            }.onFailure { Log.w(TAG, "Could not set clipboard: ${it.message}") }
        }
    }

    /**
     * Send the phone's clipboard to online computers when it changed since the
     * last check. Call with window focus (Android only allows reading then).
     * The first focus acknowledges existing text; explicit Send clipboard can
     * share it. Clips marked sensitive are never sent automatically.
     */
    fun syncClipboardFromPhone() {
        if (!preferences.clipboardSharing) return
        val description = try { clipboard.primaryClipDescription } catch (_: Exception) { return }
        val firstObservation = !clipboardObserved
        clipboardObserved = true
        if (description == null) return
        if (firstObservation) {
            lastClipStamp = description.timestamp
            return
        }
        if (description.timestamp == lastClipStamp) return
        lastClipStamp = description.timestamp
        if (isSensitive(description)) return
        val text = readClipboardText() ?: return
        if (text == lastRemoteText) return
        sendText(text)
    }

    /** Explicit "Send clipboard": sends even unchanged text. */
    fun sendClipboardNow(hostId: String? = null): Boolean {
        val text = readClipboardText() ?: return false
        return sendText(text, hostId) > 0
    }

    private fun readClipboardText(): String? = runCatching {
        clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(app)?.toString()
    }.getOrNull()?.takeIf { it.isNotEmpty() && it.toByteArray().size <= MAX_CLIPBOARD_TEXT }

    private fun isSensitive(description: ClipDescription): Boolean =
        Build.VERSION.SDK_INT >= 33 && description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true

    /** Put text on online computers' clipboards; returns how many received it. */
    fun sendText(text: String, hostId: String? = null): Int {
        if (text.toByteArray().size > MAX_CLIPBOARD_TEXT) return 0
        val json = encodeClientMessage(ClipboardText(text = text))
        return onlineHosts(hostId).count { connections.connection(it.hostId)?.client?.sendRaw(json) == true }
    }

    private fun onlineHosts(hostId: String?) = connections.hosts.value.filter {
        it.status == ConnectionManager.Status.ONLINE && (hostId == null || it.hostId == hostId)
    }

    // ── Sending ──

    /**
     * Send files to a computer. Files are opened right away: access granted by
     * a share sheet ends when the sharing screen closes. Returns how many started.
     */
    fun sendFiles(hostId: String, uris: List<Uri>): Int {
        val host = connections.state(hostId) ?: return 0
        var started = 0
        for (uri in uris) {
            val (name, size) = describe(uri)
            val mime = app.contentResolver.getType(uri)
            val input = runCatching { app.contentResolver.openInputStream(uri) }.getOrNull()
            val transfer = Transfer(newId(), hostId, host.name, name, size, Direction.SEND)
            track(transfer)
            if (input == null) {
                fail(transfer.id, "Could not open $name")
                continue
            }
            started++
            TransferService.hold(app)
            scope.launch {
                try {
                    send(transfer, input, mime)
                } catch (error: Exception) {
                    fail(transfer.id, error.message ?: "Transfer failed")
                    connections.connection(hostId)?.client?.sendRaw(encodeClientMessage(FileCancelRequest(transferId = transfer.id)))
                } finally {
                    runCatching { input.close() }
                    replies.remove(transfer.id)
                    TransferService.release(app)
                }
            }
        }
        return started
    }

    private suspend fun send(transfer: Transfer, input: InputStream, mime: String?) {
        val client = connections.connection(transfer.hostId)?.client ?: throw IllegalStateException("Computer is offline")
        val replyChannel = Channel<FileMessage>(Channel.UNLIMITED).also { replies[transfer.id] = it }
        suspend fun reply(): FileMessage = withTimeout(REPLY_TIMEOUT_MS) { replyChannel.receive() }.also {
            if (it.type == "file_error" || it.type == "file_cancelled") throw IllegalStateException(it.reason ?: "The computer stopped the transfer")
        }
        fun post(json: String) { if (!client.sendRaw(json)) throw IllegalStateException("Connection lost") }

        post(encodeClientMessage(FileOfferRequest(transferId = transfer.id, name = transfer.name, size = transfer.size, mime = mime)))
        val accepted = reply()
        val chunkSize = accepted.chunkSize.coerceIn(1024, FILE_CHUNK_SIZE)
        val digest = MessageDigest.getInstance("SHA-256")
        input.let { stream ->
            val buffer = ByteArray(chunkSize)
            var offset = 0L
            var inFlight = 0
            var eof = false
            while (true) {
                while (!eof && inFlight < WINDOW) {
                    val read = readFully(stream, buffer)
                    if (read <= 0) { eof = true; break }
                    digest.update(buffer, 0, read)
                    val data = Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP)
                    post(encodeClientMessage(FileChunkRequest(transferId = transfer.id, offset = offset, data = data)))
                    offset += read
                    inFlight++
                }
                if (inFlight == 0) break
                val progress = reply()
                if (progress.type == "file_progress") {
                    inFlight--
                    progressed(transfer.id, progress.received)
                }
            }
            if (offset != transfer.size && transfer.size > 0) throw IllegalStateException("The file changed while sending")
        }
        post(encodeClientMessage(FileCompleteRequest(transferId = transfer.id, sha256 = digest.digest().toHex())))
        while (true) {
            val saved = reply()
            if (saved.type == "file_saved") break
        }
        finish(transfer.id, null)
    }

    // ── Receiving ──

    private fun receive(hostId: String, offer: FileMessage) {
        val host = connections.state(hostId)
        val transfer = Transfer(offer.transferId, hostId, host?.name ?: "Computer", safeName(offer.name), offer.size, Direction.RECEIVE)
        track(transfer)
        scope.launch {
            TransferService.hold(app)
            val target = runCatching { createDownload(transfer.name, offer.mime) }.getOrNull()
            try {
                if (target == null) throw IllegalStateException("Could not create the file")
                pull(transfer, offer, target)
                finish(transfer.id, target.uri)
            } catch (error: Exception) {
                target?.discard()
                fail(transfer.id, error.message ?: "Transfer failed")
                connections.connection(hostId)?.client?.sendRaw(encodeClientMessage(FileCancelRequest(transferId = transfer.id)))
            } finally {
                replies.remove(transfer.id)
                TransferService.release(app)
            }
        }
    }

    private suspend fun pull(transfer: Transfer, offer: FileMessage, target: Download) {
        val client = connections.connection(transfer.hostId)?.client ?: throw IllegalStateException("Computer is offline")
        val replyChannel = Channel<FileMessage>(Channel.UNLIMITED).also { replies[transfer.id] = it }
        val digest = MessageDigest.getInstance("SHA-256")
        target.open().use { out ->
            var requested = 0L
            var written = 0L
            var inFlight = 0
            var eof = transfer.size == 0L
            fun request() {
                if (!client.sendRaw(encodeClientMessage(FilePullRequest(transferId = transfer.id, offset = requested)))) {
                    throw IllegalStateException("Connection lost")
                }
                requested += FILE_CHUNK_SIZE
                inFlight++
            }
            if (!eof) request()
            while (inFlight > 0) {
                while (!eof && inFlight < WINDOW && requested < transfer.size) request()
                val piece = withTimeout(REPLY_TIMEOUT_MS) { replyChannel.receive() }
                if (piece.type != "file_data") throw IllegalStateException(piece.reason ?: "The computer stopped the transfer")
                inFlight--
                if (piece.offset != written) throw IllegalStateException("Out-of-order data")
                val bytes = Base64.decode(piece.data, Base64.DEFAULT)
                out.write(bytes)
                digest.update(bytes)
                written += bytes.size
                progressed(transfer.id, written)
                if (piece.eof) eof = true
            }
            if (written != transfer.size) throw IllegalStateException("Received $written of ${transfer.size} bytes")
        }
        if (digest.digest().toHex() != offer.sha256.lowercase()) throw IllegalStateException("Checksum mismatch")
        target.publish()
        client.sendRaw(encodeClientMessage(FileReceivedRequest(transferId = transfer.id)))
    }

    /** A file being written to Downloads; invisible until [publish]. */
    private inner class Download(val uri: Uri, private val file: File?) {
        fun open(): OutputStream = file?.outputStream() ?: app.contentResolver.openOutputStream(uri)
            ?: throw IllegalStateException("Could not write the file")

        fun publish() {
            if (Build.VERSION.SDK_INT >= 29 && file == null) {
                app.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
        }

        fun discard() {
            if (file != null) file.delete() else runCatching { app.contentResolver.delete(uri, null, null) }
        }
    }

    private fun createDownload(name: String, mime: String?): Download {
        val type = mime ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, type)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create $name")
            return Download(uri, null)
        }
        // Android 8-9: app-specific Downloads (no storage permission needed).
        val dir = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(app.filesDir, "Downloads")
        dir.mkdirs()
        var file = File(dir, name)
        var n = 1
        while (file.exists()) file = File(dir, name.replaceFirst(Regex("(\\.[^.]*)?$"), " (${n++})$1"))
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
        return Download(uri, file)
    }

    // ── Bookkeeping ──

    private fun track(transfer: Transfer) {
        _transfers.update { list -> (list.filter { it.phase == Phase.ACTIVE } + transfer).takeLast(20) }
        notifications.update(transfer)
    }

    private fun progressed(id: String, done: Long) = change(id) { it.copy(done = done) }
    private fun finish(id: String, uri: Uri?) = change(id) { it.copy(done = it.size, phase = Phase.DONE, uri = uri) }
    private fun fail(id: String, error: String) = change(id) { it.copy(phase = Phase.FAILED, error = error) }

    private fun change(id: String, transform: (Transfer) -> Transfer) {
        var changed: Transfer? = null
        _transfers.update { list -> list.map { if (it.id == id) transform(it).also { t -> changed = t } else it } }
        changed?.let(notifications::update)
    }

    private fun describe(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment ?: "file"
        var size = -1L
        runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        if (size < 0) size = runCatching { app.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: 0
        return safeName(name) to size.coerceAtLeast(0)
    }

    companion object {
        private const val TAG = "ShareManager"
        /** Pieces in flight per transfer. */
        const val WINDOW = 8
        private const val REPLY_TIMEOUT_MS = 30_000L
        private val random = SecureRandom()

        @Volatile private var instance: ShareManager? = null

        fun get(context: Context): ShareManager = instance ?: synchronized(this) {
            instance ?: ShareManager(context.applicationContext as Application).also { instance = it }
        }

        fun newId(): String = ByteArray(12).also(random::nextBytes).toHex()

        /** A plain file name: no directories, control characters or leading dots. */
        fun safeName(name: String): String {
            val base = name.substringAfterLast('/').substringAfterLast('\\')
                .map { if (it.isISOControl()) '_' else it }.joinToString("").trim().trimStart('.').trim()
            return base.ifEmpty { "file" }.take(200)
        }

        private fun readFully(stream: InputStream, buffer: ByteArray): Int {
            var total = 0
            while (total < buffer.size) {
                val read = stream.read(buffer, total, buffer.size - total)
                if (read < 0) break
                total += read
            }
            return if (total == 0) -1 else total
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
