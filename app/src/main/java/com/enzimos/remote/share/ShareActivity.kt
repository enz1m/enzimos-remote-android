package com.enzimos.remote.share

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.enzimos.remote.MainActivity
import com.enzimos.remote.R
import com.enzimos.remote.connection.ConnectionManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Share → enzimOS Remote" from any app: files are sent to a computer's
 * Downloads, text goes to its clipboard. Picks the computer automatically when
 * only one is paired and online.
 */
class ShareActivity : AppCompatActivity() {
    private var uris: List<Uri> = emptyList()
    private var text: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uris = sharedUris(intent)
        text = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { uris.isEmpty() && it.isNotBlank() }
        if (uris.isEmpty() && text == null) {
            finish()
            return
        }
        val connections = ConnectionManager.get(this)
        lifecycleScope.launch {
            // The app just came to the foreground; links connect in well under a second.
            withTimeoutOrNull(2_500) {
                connections.hosts.first { list -> list.isEmpty() || list.any { it.status == ConnectionManager.Status.ONLINE } }
            }
            choose(connections.hosts.value)
        }
    }

    private fun choose(hosts: List<ConnectionManager.HostState>) {
        val online = hosts.filter { it.status == ConnectionManager.Status.ONLINE }
        when {
            hosts.isEmpty() -> MaterialAlertDialogBuilder(this)
                .setTitle(R.string.share_no_computers)
                .setMessage(R.string.share_no_computers_detail)
                .setPositiveButton(R.string.share_open_app) { _, _ -> startActivity(Intent(this, MainActivity::class.java)) }
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener { finish() }
                .show()
            hosts.size == 1 && online.size == 1 -> send(online.single())
            else -> {
                val labels = hosts.map { host ->
                    if (host.status == ConnectionManager.Status.ONLINE) host.name
                    else getString(R.string.share_host_offline, host.name)
                }.toTypedArray()
                MaterialAlertDialogBuilder(this)
                    .setTitle(if (text != null) R.string.share_text_title else R.string.share_files_title)
                    .setItems(labels) { _, which ->
                        val host = hosts[which]
                        if (host.status == ConnectionManager.Status.ONLINE) send(host)
                        else Toast.makeText(this, getString(R.string.share_offline_toast, host.name), Toast.LENGTH_LONG).show()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .setOnDismissListener { finish() }
                    .show()
            }
        }
    }

    private fun send(host: ConnectionManager.HostState) {
        val share = ShareManager.get(this)
        val message = text?.let {
            if (share.sendText(it, host.hostId) > 0) getString(R.string.share_text_sent, host.name)
            else getString(R.string.share_failed, host.name)
        } ?: run {
            val started = share.sendFiles(host.hostId, uris)
            resources.getQuantityString(R.plurals.share_files_sending, started, started, host.name)
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }

    @Suppress("DEPRECATION")
    private fun sharedUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_STREAM),
        )
        Intent.ACTION_SEND_MULTIPLE ->
            (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
        else -> emptyList()
    }
}
