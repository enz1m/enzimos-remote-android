package com.enzimos.remote.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.enzimos.remote.R
import com.enzimos.remote.databinding.ItemActionTileBinding
import com.enzimos.remote.databinding.ItemAppTileBinding
import com.google.android.material.color.MaterialColors
import androidx.core.view.isVisible
import com.enzimos.remote.databinding.ItemHomeActionsBinding
import com.enzimos.remote.databinding.ItemHomeDesktopBinding
import com.enzimos.remote.databinding.ItemHomeNoteBinding
import com.enzimos.remote.databinding.ItemHomeResourcesBinding
import com.enzimos.remote.databinding.ItemHomeStatusBinding
import com.enzimos.remote.databinding.ItemRunningAppBinding
import com.enzimos.remote.databinding.ItemSectionTitleBinding
import com.enzimos.remote.signaling.CatalogApp
import com.enzimos.remote.signaling.ComputerInfo
import com.enzimos.remote.signaling.SessionInfoItem

/** Rows of the launcher-style home screen. App tiles take one grid cell; everything else spans. */
sealed class HomeItem(val key: String) {
    data class Status(val bind: (ItemHomeStatusBinding) -> Unit, val token: Any) : HomeItem("status")
    data class Section(val title: String, val detail: String = "") : HomeItem("section:$title")
    data class Running(
        val session: SessionInfoItem,
        val app: CatalogApp?,
        val name: String,
        val closable: Boolean,
    ) : HomeItem("running:${session.sessionId}")
    data class App(
        val app: CatalogApp,
        val section: String,
        val favorite: Boolean,
        val running: Boolean,
        val enabled: Boolean,
        val iconsVersion: Int,
    ) : HomeItem("app:$section:${app.id}")
    data class Note(val text: String) : HomeItem("note:$text")
    /** The whole desktop; [detail] names the monitor (desktop view) or explains the shared desktop. */
    data class Desktop(val detail: String) : HomeItem("desktop")
    /** Usage rings and history; [cpuHistory] holds recent processor load (0..1). */
    data class Resources(val info: ComputerInfo, val cpuHistory: List<Float>) : HomeItem("resources")
    /** Quick actions; the phone row needs [secondDisplay] / [uplink] support. [active]: running uplink kind. */
    data class Actions(val enabled: Boolean, val secondDisplay: Boolean = false, val uplink: Boolean = false, val active: String? = null) : HomeItem("actions")
}

/** Running: filled primary circle with a slow pulse ring, so it reads as "on". */
private fun markActive(tile: ItemActionTileBinding, on: Boolean) {
    val view = tile.icon
    val background = if (on) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorSecondaryContainer
    val foreground = if (on) com.google.android.material.R.attr.colorOnPrimary else com.google.android.material.R.attr.colorOnSecondaryContainer
    view.backgroundTintList = android.content.res.ColorStateList.valueOf(MaterialColors.getColor(view, background))
    view.imageTintList = android.content.res.ColorStateList.valueOf(MaterialColors.getColor(view, foreground))
    val pulse = tile.pulse
    val running = pulse.tag as? android.animation.Animator
    if (on && running == null) {
        pulse.isVisible = true
        pulse.tag = android.animation.ObjectAnimator.ofPropertyValuesHolder(pulse,
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 0.86f, 1.12f),
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.86f, 1.12f),
            android.animation.PropertyValuesHolder.ofFloat(View.ALPHA, 0.9f, 0f),
        ).apply {
            duration = 1400
            repeatCount = android.animation.ValueAnimator.INFINITE
            start()
        }
    } else if (!on && running != null) {
        running.cancel()
        pulse.tag = null
        pulse.isVisible = false
    }
}

class HomeAdapter(
    private val icons: (CatalogApp, ImageView) -> Unit,
    private val onApp: (CatalogApp) -> Unit,
    private val onAppLongPress: (CatalogApp, View) -> Unit,
    private val onResume: (HomeItem.Running) -> Unit,
    private val onClose: (HomeItem.Running) -> Unit,
    private val onDesktop: () -> Unit,
    private val bindResources: (ItemHomeResourcesBinding, HomeItem.Resources) -> Unit,
    private val onSendFiles: () -> Unit = {},
    private val onSendClipboard: () -> Unit = {},
    private val onOpenRemote: () -> Unit = {},
    private val onSecondDisplay: () -> Unit = {},
    private val onMirror: () -> Unit = {},
    private val onWebcam: () -> Unit = {},
) : ListAdapter<HomeItem, RecyclerView.ViewHolder>(object : DiffUtil.ItemCallback<HomeItem>() {
    override fun areItemsTheSame(a: HomeItem, b: HomeItem) = a.key == b.key
    override fun areContentsTheSame(a: HomeItem, b: HomeItem) = a == b
}) {

    /** Full-width rows; app tiles use one cell. */
    inner class SpanLookup : GridLayoutManager.SpanSizeLookup() {
        var columns = 4
        override fun getSpanSize(position: Int): Int =
            if (position in 0 until itemCount && getItem(position) is HomeItem.App) 1 else columns
    }

    val spanLookup = SpanLookup()

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is HomeItem.Status -> 0
        is HomeItem.Section -> 1
        is HomeItem.Running -> 2
        is HomeItem.App -> 3
        is HomeItem.Note -> 4
        is HomeItem.Desktop -> 5
        is HomeItem.Resources -> 6
        is HomeItem.Actions -> 7
    }

    class Holder(val binding: androidx.viewbinding.ViewBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = when (viewType) {
            0 -> ItemHomeStatusBinding.inflate(inflater, parent, false)
            1 -> ItemSectionTitleBinding.inflate(inflater, parent, false)
            2 -> ItemRunningAppBinding.inflate(inflater, parent, false)
            3 -> ItemAppTileBinding.inflate(inflater, parent, false)
            4 -> ItemHomeNoteBinding.inflate(inflater, parent, false)
            5 -> ItemHomeDesktopBinding.inflate(inflater, parent, false)
            7 -> ItemHomeActionsBinding.inflate(inflater, parent, false)
            else -> ItemHomeResourcesBinding.inflate(inflater, parent, false)
        }
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val binding = (holder as Holder).binding
        when (val item = getItem(position)) {
            is HomeItem.Status -> item.bind(binding as ItemHomeStatusBinding)
            is HomeItem.Section -> (binding as ItemSectionTitleBinding).apply {
                title.text = item.title
                detail.text = item.detail
                detail.visibility = if (item.detail.isEmpty()) View.GONE else View.VISIBLE
            }
            is HomeItem.Running -> (binding as ItemRunningAppBinding).apply {
                val context = root.context
                name.text = item.name
                detail.text = context.getString(
                    R.string.computer_status_line,
                    context.getString(R.string.home_running),
                    HostUi.duration(item.session.uptimeSecs),
                )
                if (item.app != null) {
                    icon.imageTintList = null
                    icons(item.app, icon)
                } else {
                    icon.setImageResource(HostUi.appIcon(if (item.session.sessionId.startsWith("desktop-")) "desktop" else ""))
                }
                root.setOnClickListener { onResume(item) }
                open.setOnClickListener { onResume(item) }
                open.contentDescription = context.getString(R.string.resume_app, item.name)
                close.visibility = if (item.closable) View.VISIBLE else View.GONE
                close.contentDescription = context.getString(R.string.close_named_app, item.name)
                close.setOnClickListener { onClose(item) }
            }
            is HomeItem.App -> (binding as ItemAppTileBinding).apply {
                val context = root.context
                name.text = item.app.name
                icons(item.app, icon)
                phoneBadge.visibility = if (item.app.phoneReady) View.VISIBLE else View.GONE
                runningDot.visibility = if (item.running) View.VISIBLE else View.GONE
                tile.alpha = if (item.enabled) 1f else 0.5f
                tile.contentDescription = buildString {
                    append(if (item.running) context.getString(R.string.app_running_label, item.app.name) else item.app.name)
                    if (item.app.phoneReady) append(". ").append(context.getString(R.string.app_phone_ready))
                    if (item.app.description.isNotBlank()) append(". ").append(item.app.description)
                }
                tile.tooltipText = item.app.description.ifBlank { item.app.name }
                tile.setOnClickListener { onApp(item.app) }
                tile.setOnLongClickListener {
                    onAppLongPress(item.app, it)
                    true
                }
            }
            is HomeItem.Note -> (binding as ItemHomeNoteBinding).note.text = item.text
            is HomeItem.Desktop -> (binding as ItemHomeDesktopBinding).apply {
                detail.text = item.detail
                root.setOnClickListener { onDesktop() }
            }
            is HomeItem.Resources -> bindResources(binding as ItemHomeResourcesBinding, item)
            is HomeItem.Actions -> (binding as ItemHomeActionsBinding).apply {
                val context = root.context
                fun tile(tile: ItemActionTileBinding, icon: Int, label: Int, description: String, visible: Boolean, onClick: () -> Unit) {
                    tile.root.isVisible = visible
                    tile.icon.setImageResource(icon)
                    tile.label.setText(label)
                    tile.root.contentDescription = description
                    tile.root.isEnabled = item.enabled
                    tile.root.alpha = if (item.enabled) 1f else 0.45f
                    tile.root.setOnClickListener { onClick() }
                }
                tile(actionRemote, R.drawable.ic_remote, R.string.home_remote, context.getString(R.string.home_remote), true, onOpenRemote)
                tile(actionFiles, R.drawable.ic_send_file, R.string.home_files_short, context.getString(R.string.home_send_files), true, onSendFiles)
                tile(actionClipboard, R.drawable.ic_clipboard, R.string.home_send_clipboard, context.getString(R.string.home_send_clipboard), true, onSendClipboard)
                tile(actionExtend, R.drawable.ic_second_display, R.string.home_second_screen, context.getString(R.string.second_display_name), item.secondDisplay, onSecondDisplay)
                val mirroring = item.active == "screen"
                val webcamOn = item.active == "camera"
                tile(actionMirror, R.drawable.ic_mirror, R.string.mirror_screen,
                    context.getString(if (mirroring) R.string.stop_mirroring else R.string.mirror_screen), item.uplink, onMirror)
                tile(actionWebcam, R.drawable.ic_webcam, R.string.use_as_webcam,
                    context.getString(if (webcamOn) R.string.stop_webcam else R.string.use_as_webcam), item.uplink, onWebcam)
                markActive(actionMirror, mirroring)
                markActive(actionWebcam, webcamOn)
                activeHint.isVisible = item.active != null
                activeHint.text = when (item.active) {
                    "camera" -> context.getString(R.string.webcam_hint)
                    "screen" -> context.getString(R.string.mirror_hint)
                    else -> ""
                }
            }
        }
    }
}
