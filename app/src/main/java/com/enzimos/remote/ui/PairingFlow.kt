package com.enzimos.remote.ui

import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.enzimos.remote.R
import com.enzimos.remote.connection.ConnectionManager
import com.enzimos.remote.databinding.DialogPairingBinding
import com.enzimos.remote.pairing.DeviceStore
import com.enzimos.remote.pairing.PairingSession
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Runs a [PairingSession] behind a Material dialog: connecting → show the
 * six-digit code while the computer asks for approval → done or a clear
 * reason with Try again. Cancelling the dialog cancels the request on the
 * computer too (the socket closes).
 */
class PairingFlow(
    private val activity: AppCompatActivity,
    private val onPaired: (DeviceStore.PairedHost) -> Unit,
) {
    private var job: Job? = null
    private var dialog: AlertDialog? = null

    data class Target(
        val displayName: String,
        val addresses: List<String>,
        val hostId: String? = null,
        val fingerprint: String? = null,
        val qrToken: String? = null,
    )

    fun start(target: Target) {
        cancel()
        val binding = DialogPairingBinding.inflate(LayoutInflater.from(activity))
        binding.body.text = activity.getString(R.string.pairing_connecting)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setIcon(R.drawable.ic_phonelink)
            .setTitle(R.string.pair_connecting_title)
            .setView(binding.root)
            .setNegativeButton(R.string.action_cancel) { _, _ -> cancel() }
            .setOnCancelListener { cancel() }
            .show()
        this.dialog = dialog

        val store = DeviceStore(activity)
        val session = PairingSession(store.getDeviceId(), store.deviceName())
        job = activity.lifecycleScope.launch {
            session.pair(target.addresses, target.hostId, target.fingerprint, target.qrToken).collect { event ->
                when (event) {
                    is PairingSession.Event.AwaitingApproval -> {
                        dialog.setTitle(activity.getString(R.string.pair_approve_title, event.hostName))
                        binding.body.text = activity.getString(R.string.pair_approve_body)
                        val spaced = event.code.chunked(3).joinToString(" ")
                        binding.code.text = spaced
                        binding.code.contentDescription = activity.getString(R.string.pair_code_description, event.code.toList().joinToString(" "))
                        binding.code.visibility = View.VISIBLE
                        binding.hint.text = activity.getString(R.string.pair_cli_hint, event.code)
                        binding.hint.visibility = View.VISIBLE
                    }
                    is PairingSession.Event.Paired -> {
                        ConnectionManager.get(activity).addPaired(event.host)
                        dialog.setOnCancelListener(null)
                        dialog.dismiss()
                        onPaired(event.host)
                    }
                    is PairingSession.Event.Failed -> {
                        dialog.setOnCancelListener(null)
                        dialog.dismiss()
                        MaterialAlertDialogBuilder(activity)
                            .setTitle(R.string.pair_failed_title)
                            .setMessage(event.reason)
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(R.string.pair_try_again) { _, _ ->
                                // A QR token is single use; retry without it (desktop approval).
                                start(target.copy(qrToken = null))
                            }
                            .show()
                    }
                }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        dialog?.setOnCancelListener(null)
        dialog?.dismiss()
        dialog = null
    }
}
