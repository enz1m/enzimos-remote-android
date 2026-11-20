package com.enzimos.remote

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.enzimos.remote.databinding.ItemWindowBinding
import com.enzimos.remote.webrtc.ServerMessageParser.WindowInfo

/**
 * RecyclerView adapter for the window switching bottom sheet.
 *
 * Displays compositor windows sorted by focus recency (most recent first).
 * Tapping a window item triggers [onWindowSelected] with the window address.
 */
class WindowListAdapter(
    private val onWindowSelected: (String) -> Unit,
) : RecyclerView.Adapter<WindowListAdapter.ViewHolder>() {

    private val windows = mutableListOf<WindowInfo>()

    /** Update the window list and refresh the UI. */
    fun updateWindows(newWindows: List<WindowInfo>) {
        // Sort by focusHistoryId (most recently focused first, -1 last).
        val sorted = newWindows.sortedByDescending { it.focusHistoryId }
        val diff = DiffUtil.calculateDiff(WindowDiffCallback(windows, sorted))
        windows.clear()
        windows.addAll(sorted)
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemWindowBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val window = windows[position]
        holder.bind(window)
    }

    override fun getItemCount(): Int = windows.size

    inner class ViewHolder(
        private val binding: ItemWindowBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(window: WindowInfo) {
            val title = window.title.ifEmpty { "(untitled)" }
            binding.tvWindowTitle.text = title
            binding.tvWindowClass.text = window.className.ifEmpty { window.address }
            binding.root.setOnClickListener {
                onWindowSelected(window.address)
            }
            binding.root.contentDescription = "Switch to $title"
        }
    }

    private class WindowDiffCallback(
        private val old: List<WindowInfo>,
        private val new: List<WindowInfo>,
    ) : DiffUtil.Callback() {
        override fun getOldListSize(): Int = old.size
        override fun getNewListSize(): Int = new.size
        override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
            old[oldPos].address == new[newPos].address
        override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
            old[oldPos] == new[newPos]
    }
}
