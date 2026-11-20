package com.enzimos.remote

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.enzimos.remote.databinding.ItemSessionBinding
import com.enzimos.remote.signaling.SessionInfoItem

/**
 * RecyclerView adapter for displaying active sessions.
 */
class SessionAdapter(
    private val onResume: (SessionInfoItem) -> Unit,
    private val onStop: (SessionInfoItem) -> Unit,
) : RecyclerView.Adapter<SessionAdapter.ViewHolder>() {

    private val sessions = mutableListOf<SessionInfoItem>()

    class ViewHolder(val binding: ItemSessionBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSessionBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val session = sessions[position]
        holder.binding.tvSessionId.text = session.sessionId
        holder.binding.tvSessionStatus.text = session.status

        val details = buildString {
            append("${session.width}x${session.height}")
            if (session.scale != 1.0) {
                append(" @${session.scale}x")
            }
            if (session.uptimeSecs > 0) {
                append(" | ${formatUptime(session.uptimeSecs)}")
            }
            if (session.ownerDeviceId.isNotEmpty()) {
                append(" | ${session.ownerDeviceId}")
            }
        }
        holder.binding.tvSessionDetails.text = details

        holder.binding.btnSessionResume.setOnClickListener {
            onResume(session)
        }
        holder.binding.btnSessionStop.setOnClickListener {
            onStop(session)
        }
    }

    override fun getItemCount(): Int = sessions.size

    /**
     * Replace the session list with new data.
     */
    @android.annotation.SuppressLint("NotifyDataSetChanged")
    fun updateSessions(newSessions: List<SessionInfoItem>) {
        sessions.clear()
        sessions.addAll(newSessions)
        notifyDataSetChanged()
    }

    companion object {
        /**
         * Format uptime in seconds to a human-readable string.
         * Delegates to [SessionFormatter.formatUptime] which handles
         * negative values and days.
         */
        fun formatUptime(seconds: Long): String =
            SessionFormatter.formatUptime(seconds)
    }
}
