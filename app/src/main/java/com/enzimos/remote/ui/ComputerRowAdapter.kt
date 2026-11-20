package com.enzimos.remote.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.enzimos.remote.connection.ConnectionManager
import com.enzimos.remote.databinding.ItemComputerRowBinding

/** One row per computer: status badge, name, status line and an optional action. */
data class ComputerRow(
    val key: String,
    val title: String,
    val subtitle: String,
    val status: ConnectionManager.Status,
    val selected: Boolean = false,
    val actionLabel: String? = null,
    val actionEnabled: Boolean = true,
    val showMore: Boolean = false,
    val showStatus: Boolean = true,
)

class ComputerRowAdapter(
    private val onClick: (ComputerRow) -> Unit,
    private val onAction: (ComputerRow) -> Unit = onClick,
    private val onMore: (ComputerRow, View) -> Unit = { _, _ -> },
) : ListAdapter<ComputerRow, ComputerRowAdapter.Holder>(object : DiffUtil.ItemCallback<ComputerRow>() {
    override fun areItemsTheSame(a: ComputerRow, b: ComputerRow) = a.key == b.key
    override fun areContentsTheSame(a: ComputerRow, b: ComputerRow) = a == b
}) {
    class Holder(val binding: ItemComputerRowBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemComputerRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        holder.binding.apply {
            title.text = row.title
            subtitle.text = row.subtitle
            HostUi.tintDot(statusDot, row.status)
            statusDot.visibility = if (row.showStatus) View.VISIBLE else View.GONE
            selected.visibility = if (row.selected) View.VISIBLE else View.GONE
            action.visibility = if (row.actionLabel != null) View.VISIBLE else View.GONE
            action.text = row.actionLabel
            action.isEnabled = row.actionEnabled
            action.setOnClickListener { onAction(row) }
            more.visibility = if (row.showMore) View.VISIBLE else View.GONE
            more.setOnClickListener { onMore(row, it) }
            this.row.setOnClickListener { onClick(row) }
            this.row.contentDescription = "${row.title}, ${row.subtitle}"
        }
    }
}
