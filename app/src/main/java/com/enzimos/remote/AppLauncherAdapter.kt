package com.enzimos.remote

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.recyclerview.widget.RecyclerView
import com.enzimos.remote.databinding.ItemAppLauncherBinding

/**
 * Data class representing an app launcher entry in the drawer grid.
 */
data class AppLauncher(
    val appId: String,
    val label: String,
    @DrawableRes val iconRes: Int,
)

/**
 * RecyclerView adapter for the app launcher grid in the app drawer.
 *
 * Displays a fixed list of available desktop applications.
 * Tapping a launcher triggers [onLaunch] with the app ID.
 */
class AppLauncherAdapter(
    private val launchers: List<AppLauncher>,
    private val onLaunch: (String) -> Unit,
) : RecyclerView.Adapter<AppLauncherAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAppLauncherBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(launchers[position])
    }

    override fun getItemCount(): Int = launchers.size

    inner class ViewHolder(
        private val binding: ItemAppLauncherBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(launcher: AppLauncher) {
            binding.ivLauncherIcon.setImageResource(launcher.iconRes)
            binding.tvLauncherLabel.text = launcher.label
            binding.root.contentDescription = "Launch ${launcher.label}"
            binding.root.setOnClickListener {
                onLaunch(launcher.appId)
            }
        }
    }
}
