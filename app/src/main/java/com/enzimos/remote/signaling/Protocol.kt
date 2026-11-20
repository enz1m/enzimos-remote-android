package com.enzimos.remote.signaling

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * enzremote signaling protocol v1 — Kotlin data classes.
 *
 * All messages are JSON objects with a "type" field discriminator.
 * Field validation mirrors the host-side rigor defined in
 * `crates/signaling/src/protocol.rs`.
 */

// ---------------------------------------------------------------------------
// Protocol constants
// ---------------------------------------------------------------------------

const val PROTOCOL_VERSION = 1

/** Maximum device_id length. */
const val MAX_DEVICE_ID = 128
/** Maximum session_id length. */
const val MAX_SESSION_ID = 64
/** Maximum token length. */
const val MAX_TOKEN = 512
/** Maximum SDP body length. */
const val MAX_SDP_SIZE = 32_768
/** Maximum ICE candidate length. */
const val MAX_ICE_SIZE = 4096
/** Maximum reason string length. */
const val MAX_REASON = 256
/** Maximum video codecs in capabilities. */
const val MAX_VIDEO_CODECS = 32
/** Maximum command args. */
const val MAX_COMMAND_ARGS = 64
/** Maximum single command arg size. */
const val MAX_COMMAND_ARG_SIZE = 4096

// Dimension limits
const val MIN_WIDTH = 240
const val MAX_WIDTH = 8192
const val MIN_HEIGHT = 240
const val MAX_HEIGHT = 8192

// Scale limits
const val MIN_SCALE = 0.5
const val MAX_SCALE = 4.0
const val DEFAULT_SCALE = 1.0

// Refresh rate limits
const val MIN_REFRESH_HZ = 15
const val MAX_REFRESH_HZ = 240
const val DEFAULT_REFRESH_HZ = 60

// ---------------------------------------------------------------------------
// Validation regex
// ---------------------------------------------------------------------------

private val DEVICE_ID_REGEX = Regex("^[a-zA-Z0-9._-]+$")
private val SESSION_ID_REGEX = Regex("^[a-z0-9_-]+$")

// ---------------------------------------------------------------------------
// Validation helpers
// ---------------------------------------------------------------------------

/** Validate device_id: 1-128 chars, `[a-zA-Z0-9._-]`. */
fun validateDeviceId(id: String): Boolean =
    id.length in 1..MAX_DEVICE_ID && DEVICE_ID_REGEX.matches(id)

/** Validate session_id: 1-64 chars, `[a-z0-9_-]`. */
fun validateSessionId(id: String): Boolean =
    id.length in 1..MAX_SESSION_ID && SESSION_ID_REGEX.matches(id)

/** Validate token: 1-512 chars, no control characters. */
fun validateToken(token: String): Boolean =
    token.length in 1..MAX_TOKEN && token.none { it.isISOControl() }

/** Validate dimensions (width or height). */
fun validateDimension(value: Int): Boolean =
    value in MIN_WIDTH..MAX_WIDTH

/** Validate scale factor. */
fun validateScale(value: Double): Boolean =
    value.isFinite() && value >= MIN_SCALE && value <= MAX_SCALE

/** Validate refresh rate. */
fun validateRefreshHz(value: Int): Boolean =
    value in MIN_REFRESH_HZ..MAX_REFRESH_HZ

// ---------------------------------------------------------------------------
// Client → Host messages
// ---------------------------------------------------------------------------

@Serializable
data class Capabilities(
    @SerialName("video_codecs") val videoCodecs: List<String> = listOf("h264"),
    val input: List<String> = listOf("touch"),
    val resize: Boolean = false,
    val clipboard: Boolean = false,
    val audio: Boolean = false,
)

@Serializable
data class HelloMessage(
    val type: String = "hello",
    val v: Int = PROTOCOL_VERSION,
    @SerialName("device_id") val deviceId: String,
    val token: String,
    val capabilities: Capabilities = Capabilities(),
)

@Serializable
data class SessionRequestMessage(
    val type: String = "session_request",
    val v: Int = PROTOCOL_VERSION,
    @SerialName("session_id") val sessionId: String,
    val width: Int,
    val height: Int,
    val command: List<String>,
    val scale: Double = DEFAULT_SCALE,
    @SerialName("refresh_hz") val refreshHz: Int = DEFAULT_REFRESH_HZ,
)

@Serializable
data class SessionResumeMessage(
    val type: String = "session_resume",
    val v: Int = PROTOCOL_VERSION,
    @SerialName("session_id") val sessionId: String,
)

@Serializable
data class SessionResizeMessage(
    val type: String = "session_resize",
    val v: Int = PROTOCOL_VERSION,
    @SerialName("session_id") val sessionId: String,
    val width: Int,
    val height: Int,
    val scale: Double = DEFAULT_SCALE,
    @SerialName("refresh_hz") val refreshHz: Int = DEFAULT_REFRESH_HZ,
)

@Serializable
data class SessionStopMessage(
    val type: String = "session_stop",
    val v: Int = PROTOCOL_VERSION,
    @SerialName("session_id") val sessionId: String,
)

@Serializable
data class SessionInspectMessage(
    val type: String = "session_inspect",
    val v: Int = PROTOCOL_VERSION,
    @SerialName("session_id") val sessionId: String,
)

@Serializable
data class SessionListMessage(
    val type: String = "session_list",
    val v: Int = PROTOCOL_VERSION,
)

@Serializable
data class SdpMessage(
    val type: String = "sdp",
    val v: Int = PROTOCOL_VERSION,
    @SerialName("session_id") val sessionId: String,
    @SerialName("sdp_type") val sdpType: String,
    val sdp: String,
)

@Serializable
data class IceMessage(
    val type: String = "ice",
    val v: Int = PROTOCOL_VERSION,
    @SerialName("session_id") val sessionId: String,
    val candidate: String,
    @SerialName("sdp_m_line_index") val sdpMLineIndex: Int = 0,
)

@Serializable
data class StreamStartMessage(
    val type: String = "stream_start",
    val v: Int = PROTOCOL_VERSION,
    @SerialName("session_id") val sessionId: String,
)

@Serializable
data class StreamStopMessage(
    val type: String = "stream_stop",
    val v: Int = PROTOCOL_VERSION,
    @SerialName("session_id") val sessionId: String,
)

@Serializable
data class StreamPauseMessage(
    val type: String = "stream_pause", val v: Int = PROTOCOL_VERSION,
    @SerialName("session_id") val sessionId: String,
)

@Serializable
data class DesktopAttachMessage(
    val type: String = "desktop_attach", val v: Int = PROTOCOL_VERSION,
    @SerialName("session_id") val sessionId: String,
)

@Serializable
data class ProbeMessage(
    val type: String = "probe",
    val v: Int = PROTOCOL_VERSION,
)

@Serializable
data class ComputerInfoRequest(val type: String = "computer_info", val v: Int = PROTOCOL_VERSION)

@Serializable
data class AppLaunchMessage(
    val type: String = "app_launch", val v: Int = PROTOCOL_VERSION,
    @SerialName("session_id") val sessionId: String,
    @SerialName("app_id") val appId: String,
    val width: Int, val height: Int, val scale: Double,
    @SerialName("refresh_hz") val refreshHz: Int,
)

@Serializable
data class RemoteApp(
    val id: String, val name: String, val description: String = "", val available: Boolean = false,
)

@Serializable
data class ComputerInfo(
    val hostname: String = "Computer", val os: String = "Linux",
    @SerialName("cpu_model") val cpuModel: String = "Processor",
    @SerialName("cpu_threads") val cpuThreads: Int = 0,
    @SerialName("cpu_usage_percent") val cpuUsagePercent: Double? = null,
    @SerialName("memory_total_bytes") val memoryTotalBytes: Long = 0,
    @SerialName("memory_available_bytes") val memoryAvailableBytes: Long = 0,
    @SerialName("disk_total_bytes") val diskTotalBytes: Long = 0,
    @SerialName("disk_available_bytes") val diskAvailableBytes: Long = 0,
    @SerialName("uptime_secs") val uptimeSecs: Long = 0,
    val apps: List<RemoteApp> = emptyList(),
    @SerialName("shared_desktop") val sharedDesktop: Boolean = false,
    /** The phone can be used as an extra monitor. */
    @SerialName("second_display") val secondDisplay: Boolean = false,
    /** This phone's screen or camera can be shown there (`uplink_start`). */
    val uplink: Boolean = false,
    /** Codecs the computer can stream. */
    @SerialName("video_codecs") val videoCodecs: List<String> = emptyList(),
    /** Revision of the full catalog; refetch `app_list` when it changes. */
    @SerialName("app_revision") val appRevision: String? = null,
    /** Monitors that can be shown on the phone (desktop view), primary first. */
    val monitors: List<Monitor> = emptyList(),
    @SerialName("net_rx_bytes_per_sec") val netRxBytesPerSec: Long? = null,
    @SerialName("net_tx_bytes_per_sec") val netTxBytesPerSec: Long? = null,
)

/** A monitor of the computer (`computer_info.monitors`). */
@Serializable
data class Monitor(
    val connector: String,
    val name: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val primary: Boolean = false,
)

/** One launchable application from `app_list_result`. */
@Serializable
data class CatalogApp(
    val id: String,
    val name: String,
    val description: String = "",
    /** `iced` (enzUI, adapts to phones) or `desktop` (freedesktop entry). */
    val kind: String = "desktop",
    /** Icon revision; fetch pixels with `app_icons`. */
    val icon: String? = null,
    val keywords: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
) {
    val phoneReady: Boolean get() = kind == "iced"

    fun matches(query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        return name.lowercase().contains(q) || description.lowercase().contains(q) || keywords.any { it.contains(q) }
    }
}

@Serializable
data class AppListRequest(val type: String = "app_list", val v: Int = PROTOCOL_VERSION)

// ── File transfer and clipboard (see docs/signaling-protocol.md) ──

/** Decoded bytes per `file_chunk` / `file_data`. */
const val FILE_CHUNK_SIZE = 40 * 1024
/** Largest clipboard text either side sends. */
const val MAX_CLIPBOARD_TEXT = 48 * 1024

@Serializable
data class FileOfferRequest(
    val type: String = "file_offer",
    val v: Int = PROTOCOL_VERSION,
    @SerialName("transfer_id") val transferId: String,
    val name: String,
    val size: Long,
    val mime: String? = null,
)

@Serializable
data class FileChunkRequest(
    val type: String = "file_chunk",
    val v: Int = PROTOCOL_VERSION,
    @SerialName("transfer_id") val transferId: String,
    val offset: Long,
    val data: String,
)

@Serializable
data class FileCompleteRequest(
    val type: String = "file_complete",
    val v: Int = PROTOCOL_VERSION,
    @SerialName("transfer_id") val transferId: String,
    val sha256: String,
)

@Serializable
data class FilePullRequest(
    val type: String = "file_pull",
    val v: Int = PROTOCOL_VERSION,
    @SerialName("transfer_id") val transferId: String,
    val offset: Long,
)

@Serializable
data class FileReceivedRequest(
    val type: String = "file_received",
    val v: Int = PROTOCOL_VERSION,
    @SerialName("transfer_id") val transferId: String,
)

@Serializable
data class FileCancelRequest(
    val type: String = "file_cancel",
    val v: Int = PROTOCOL_VERSION,
    @SerialName("transfer_id") val transferId: String,
    val reason: String? = null,
)

@Serializable
data class ClipboardSyncRequest(val type: String = "clipboard_sync", val v: Int = PROTOCOL_VERSION, val enabled: Boolean)

@Serializable
data class ClipboardText(val type: String = "clipboard", val v: Int = PROTOCOL_VERSION, val text: String)

// ── Phone features: notifications, status, find my phone ──

@Serializable
data class NotificationActionInfo(val id: String, val title: String, val reply: Boolean = false)

@Serializable
data class PhoneNotificationMessage(
    val type: String = "phone_notification",
    val v: Int = PROTOCOL_VERSION,
    val key: String,
    val app: String,
    @SerialName("app_name") val appName: String,
    val title: String,
    val text: String = "",
    val icon: String? = null,
    val actions: List<NotificationActionInfo> = emptyList(),
)

@Serializable
data class PhoneNotificationRemovedMessage(val type: String = "phone_notification_removed", val v: Int = PROTOCOL_VERSION, val key: String)

@Serializable
data class PhoneStatusMessage(val type: String = "phone_status", val v: Int = PROTOCOL_VERSION, val battery: Int, val charging: Boolean)

@Serializable
data class NotificationActionMessage(val key: String, @SerialName("action_id") val actionId: String, val text: String? = null)

@Serializable
data class NotificationDismissMessage(val key: String)

@Serializable
data class RingMessage(val ringing: Boolean)

// ── Media remote and desktop input ──

@Serializable
data class SecondDisplayStartMessage(
    val type: String = "second_display_start",
    val v: Int = PROTOCOL_VERSION,
    @SerialName("session_id") val sessionId: String,
    val width: Int,
    val height: Int,
)

/** Show an existing monitor (desktop view); only for hosts listing `monitors`. */
@Serializable
data class DesktopViewStartMessage(
    val type: String = "second_display_start",
    val v: Int = PROTOCOL_VERSION,
    @SerialName("session_id") val sessionId: String,
    /** The largest size to stream (the phone's screen); the host keeps the monitor's shape. */
    val width: Int,
    val height: Int,
    val view: Boolean = true,
    /** Connector; null shows the primary monitor. */
    val output: String? = null,
)

// ── Phone video to the computer (mirroring, webcam) ──

@Serializable
data class UplinkStartMessage(
    val type: String = "uplink_start",
    val v: Int = PROTOCOL_VERSION,
    @SerialName("uplink_id") val uplinkId: String,
    val kind: String,
    val sdp: String,
)

@Serializable
data class UplinkIceMessage(
    val type: String = "uplink_ice",
    val v: Int = PROTOCOL_VERSION,
    @SerialName("uplink_id") val uplinkId: String,
    val candidate: String,
    @SerialName("sdp_m_line_index") val sdpMLineIndex: Int,
)

@Serializable
data class UplinkStopMessage(val type: String = "uplink_stop", val v: Int = PROTOCOL_VERSION, @SerialName("uplink_id") val uplinkId: String)

@Serializable
data class UplinkHostMessage(
    @SerialName("uplink_id") val uplinkId: String,
    val sdp: String = "",
    val candidate: String = "",
    @SerialName("sdp_m_line_index") val sdpMLineIndex: Int = 0,
    val event: kotlinx.serialization.json.JsonObject? = null,
)

// ── Text messages for the computer ──

@Serializable
data class SmsStatusMessage(val type: String = "sms_status", val v: Int = PROTOCOL_VERSION, val enabled: Boolean, val changed: Boolean = false)

@Serializable
data class SmsResultMessage(
    val type: String = "sms_result",
    val v: Int = PROTOCOL_VERSION,
    @SerialName("request_id") val requestId: String,
    val result: kotlinx.serialization.json.JsonObject,
)

@Serializable
data class SmsRequestMessage(
    @SerialName("request_id") val requestId: String,
    val request: kotlinx.serialization.json.JsonObject,
)

@Serializable
data class MediaStateRequest(
    val type: String = "media_state_request",
    val v: Int = PROTOCOL_VERSION,
    val player: String? = null,
    @SerialName("known_art") val knownArt: String? = null,
)

@Serializable
data class MediaCommandRequest(
    val type: String = "media_command",
    val v: Int = PROTOCOL_VERSION,
    val player: String? = null,
    val command: String,
    val value: Double? = null,
)

@Serializable
data class MediaPlayerInfo(val id: String, val name: String)

@Serializable
data class MediaState(
    val players: List<MediaPlayerInfo> = emptyList(),
    val player: String? = null,
    val status: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    @SerialName("length_ms") val lengthMs: Long = 0,
    @SerialName("position_ms") val positionMs: Long = 0,
    @SerialName("can_seek") val canSeek: Boolean = false,
    @SerialName("art_id") val artId: String? = null,
    val art: String? = null,
    val volume: Int = 0,
    val muted: Boolean = false,
)

@Serializable
data class MediaStateMessage(val state: MediaState)

/** Host messages about one transfer (responses and the computer's offers). */
@Serializable
data class FileMessage(
    val type: String,
    @SerialName("transfer_id") val transferId: String,
    val name: String = "",
    val size: Long = 0,
    val mime: String? = null,
    val sha256: String = "",
    @SerialName("chunk_size") val chunkSize: Int = FILE_CHUNK_SIZE,
    val received: Long = 0,
    val offset: Long = 0,
    val data: String = "",
    val eof: Boolean = false,
    val reason: String? = null,
)

@Serializable
data class AppIconsRequest(
    val type: String = "app_icons",
    val v: Int = PROTOCOL_VERSION,
    @SerialName("app_ids") val appIds: List<String>,
)

@Serializable
data class AppListResultMessage(
    val type: String = "app_list_result",
    val revision: String = "",
    val apps: List<CatalogApp> = emptyList(),
)

@Serializable
data class AppIconData(
    @SerialName("app_id") val appId: String,
    val icon: String,
    val png: String,
)

@Serializable
data class AppIconsResultMessage(
    val type: String = "app_icons_result",
    val icons: List<AppIconData> = emptyList(),
)

@Serializable
data class ComputerInfoResult(val computer: ComputerInfo)

/** Ask an unpaired computer for approval (sent instead of hello). */
@Serializable
data class PairRequestMessage(
    val type: String = "pair_request",
    val v: Int = PROTOCOL_VERSION,
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String,
    @SerialName("client_nonce") val clientNonce: String,
    /** One-time token from a scanned QR code; omitted for desktop approval. */
    @SerialName("pairing_token") val pairingToken: String? = null,
)

@Serializable
data class UnpairMessage(val type: String = "unpair", val v: Int = PROTOCOL_VERSION)

@Serializable
data class PingMessage(
    val type: String = "ping",
    val v: Int = PROTOCOL_VERSION,
    @SerialName("timestamp_ms") val timestampMs: Long = System.currentTimeMillis(),
)

// ---------------------------------------------------------------------------
// Host → Client messages
// ---------------------------------------------------------------------------

@Serializable
data class ServerCapabilities(
    val input: List<String> = emptyList(),
    @SerialName("video_codecs") val videoCodecs: List<String> = emptyList(),
    val resize: Boolean = false,
    val clipboard: Boolean = false,
    val audio: Boolean = false,
)

@Serializable
data class HelloAckMessage(
    val type: String = "hello_ack",
    val v: Int = PROTOCOL_VERSION,
    val capabilities: ServerCapabilities = ServerCapabilities(),
    @SerialName("server_version") val serverVersion: String = "",
    @SerialName("device_token") val deviceToken: String? = null,
    @SerialName("host_id") val hostId: String? = null,
    @SerialName("host_name") val hostName: String? = null,
)

@Serializable
data class HelloRejectMessage(
    val type: String = "hello_reject",
    val reason: String = "",
    /** `not_paired`: the credential is unknown or revoked; offer re-pairing. */
    val code: String? = null,
) {
    val notPaired: Boolean get() = code == "not_paired"
}

@Serializable
data class PairPendingMessage(
    val type: String = "pair_pending",
    @SerialName("host_id") val hostId: String = "",
    @SerialName("host_name") val hostName: String = "",
    @SerialName("host_nonce") val hostNonce: String = "",
    @SerialName("timeout_secs") val timeoutSecs: Int = 120,
)

@Serializable
data class PairResultMessage(
    val type: String = "pair_result",
    val accepted: Boolean = false,
    @SerialName("device_token") val deviceToken: String? = null,
    val reason: String? = null,
)

@Serializable
data class UnpairedMessage(val type: String = "unpaired")

@Serializable
data class SessionCreatedMessage(
    val type: String = "session_created",
    @SerialName("session_id") val sessionId: String,
    val width: Int,
    val height: Int,
    val scale: Double = DEFAULT_SCALE,
    @SerialName("refresh_hz") val refreshHz: Int = DEFAULT_REFRESH_HZ,
)

@Serializable
data class SessionResumedMessage(
    val type: String = "session_resumed",
    @SerialName("session_id") val sessionId: String,
    val width: Int = 0,
    val height: Int = 0,
    val scale: Double = DEFAULT_SCALE,
    @SerialName("refresh_hz") val refreshHz: Int = DEFAULT_REFRESH_HZ,
)

@Serializable
data class SessionResizedMessage(
    val type: String = "session_resized",
    @SerialName("session_id") val sessionId: String,
    val width: Int,
    val height: Int,
    val scale: Double = DEFAULT_SCALE,
    @SerialName("refresh_hz") val refreshHz: Int = DEFAULT_REFRESH_HZ,
)

@Serializable
data class SessionStoppedMessage(
    val type: String = "session_stopped",
    @SerialName("session_id") val sessionId: String,
    val reason: String = "",
)

@Serializable
data class SessionErrorMessage(
    val type: String = "session_error",
    @SerialName("session_id") val sessionId: String = "",
    @SerialName("reason") val error: String = "",
)

@Serializable
data class HostSdpMessage(
    val type: String = "sdp",
    @SerialName("session_id") val sessionId: String,
    @SerialName("sdp_type") val sdpType: String,
    val sdp: String,
)

@Serializable
data class HostIceMessage(
    val type: String = "ice",
    @SerialName("session_id") val sessionId: String,
    val candidate: String,
    @SerialName("sdp_m_line_index") val sdpMLineIndex: Int = 0,
)

@Serializable
data class StreamStartedMessage(
    val type: String = "stream_started",
    @SerialName("session_id") val sessionId: String,
)

@Serializable
data class StreamStoppedMessage(
    val type: String = "stream_stopped",
    @SerialName("session_id") val sessionId: String,
    val reason: String = "",
)

@Serializable
data class SessionInfoItem(
    @SerialName("session_id") val sessionId: String,
    val active: Boolean = false,
    val status: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val scale: Double = DEFAULT_SCALE,
    @SerialName("refresh_hz") val refreshHz: Int = DEFAULT_REFRESH_HZ,
    @SerialName("uptime_secs") val uptimeSecs: Long = 0,
    @SerialName("owner_device_id") val ownerDeviceId: String = "",
    @SerialName("negotiation_state") val negotiationState: String = "",
    /** Catalog ID the host launched this session for, when known. */
    @SerialName("app_id") val appId: String? = null,
)

@Serializable
data class SessionListResultMessage(
    val type: String = "session_list_result",
    val sessions: List<SessionInfoItem> = emptyList(),
)

@Serializable
data class SessionInspectionMessage(
    val type: String = "session_inspection",
    @SerialName("session_id") val sessionId: String,
    val status: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val scale: Double = DEFAULT_SCALE,
    @SerialName("refresh_hz") val refreshHz: Int = DEFAULT_REFRESH_HZ,
    val command: List<String> = emptyList(),
    @SerialName("compositor_alive") val compositorAlive: Boolean = false,
    @SerialName("app_alive") val appAlive: Boolean = false,
    @SerialName("stream_alive") val streamAlive: Boolean = false,
    @SerialName("negotiation_state") val negotiationState: String = "",
)

@Serializable
data class ProbeResultMessage(
    val type: String = "probe_result",
    val v: Int = PROTOCOL_VERSION,
    @SerialName("server_version") val serverVersion: String = "",
    val hostname: String = "",
    @SerialName("active_sessions") val activeSessions: Int = 0,
    @SerialName("active_connections") val activeConnections: Int = 0,
    @SerialName("server_uptime_secs") val serverUptimeSecs: Long = 0,
    @SerialName("total_connections") val totalConnections: Long = 0,
    @SerialName("total_messages") val totalMessages: Long = 0,
    @SerialName("total_auth_failures") val totalAuthFailures: Long = 0,
    @SerialName("total_rate_limits") val totalRateLimits: Long = 0,
    @SerialName("total_sessions_created") val totalSessionsCreated: Long = 0,
    @SerialName("peak_connections") val peakConnections: Long = 0,
)

@Serializable
data class PongMessage(
    val type: String = "pong",
    val v: Int = PROTOCOL_VERSION,
    @SerialName("timestamp_ms") val timestampMs: Long = 0,
)

@Serializable
data class SessionEventMessage(
    val type: String = "session_event",
    @SerialName("session_id") val sessionId: String,
    val event: String,
    /** Why the app ended, e.g. its last error output when it failed to start. */
    val detail: String? = null,
)

@Serializable
data class ErrorMessage(
    val type: String = "error",
    @SerialName("reason") val error: String = "",
)

// ---------------------------------------------------------------------------
// Sealed class for host messages (parsed from incoming JSON)
// ---------------------------------------------------------------------------

sealed class HostMessage {
    data class HelloAck(val msg: HelloAckMessage) : HostMessage()
    data class HelloReject(val msg: HelloRejectMessage) : HostMessage()
    data class SessionCreated(val msg: SessionCreatedMessage) : HostMessage()
    data class SessionResumed(val msg: SessionResumedMessage) : HostMessage()
    data class SessionResized(val msg: SessionResizedMessage) : HostMessage()
    data class SessionStopped(val msg: SessionStoppedMessage) : HostMessage()
    data class SessionError(val msg: SessionErrorMessage) : HostMessage()
    data class Sdp(val msg: HostSdpMessage) : HostMessage()
    data class Ice(val msg: HostIceMessage) : HostMessage()
    data class StreamStarted(val msg: StreamStartedMessage) : HostMessage()
    data class StreamStopped(val msg: StreamStoppedMessage) : HostMessage()
    data class SessionListResult(val msg: SessionListResultMessage) : HostMessage()
    data class SessionInspection(val msg: SessionInspectionMessage) : HostMessage()
    data class ProbeResult(val msg: ProbeResultMessage) : HostMessage()
    data class ComputerInfoResult(val msg: com.enzimos.remote.signaling.ComputerInfoResult) : HostMessage()
    data class Pong(val msg: PongMessage) : HostMessage()
    data class SessionEvent(val msg: SessionEventMessage) : HostMessage()
    data class Error(val msg: ErrorMessage) : HostMessage()
    data class AppListResult(val msg: AppListResultMessage) : HostMessage()
    data class AppIconsResult(val msg: AppIconsResultMessage) : HostMessage()
    data class File(val msg: FileMessage) : HostMessage()
    data class Clipboard(val msg: ClipboardText) : HostMessage()
    data object ClipboardAck : HostMessage()
    data class NotificationAction(val msg: NotificationActionMessage) : HostMessage()
    data class NotificationDismiss(val msg: NotificationDismissMessage) : HostMessage()
    data class Ring(val msg: RingMessage) : HostMessage()
    data class Media(val state: MediaState) : HostMessage()
    data class Uplink(val kind: String, val msg: UplinkHostMessage) : HostMessage()
    data class SmsRequest(val msg: SmsRequestMessage) : HostMessage()
    data class PairPending(val msg: PairPendingMessage) : HostMessage()
    data class PairResult(val msg: PairResultMessage) : HostMessage()
    data class Unpaired(val msg: UnpairedMessage) : HostMessage()
    data class Unknown(val type: String, val raw: String) : HostMessage()
}

// ---------------------------------------------------------------------------
// JSON codec
// ---------------------------------------------------------------------------

/** JSON instance configured for the enzremote protocol. */
val protocolJson = Json {
    ignoreUnknownKeys = true  // forward-compat: host may add fields
    encodeDefaults = true
    isLenient = false
    coerceInputValues = true
}

/** Parse an incoming host message from raw JSON text. */
fun parseHostMessage(text: String): HostMessage {
    return try {
        val element = protocolJson.parseToJsonElement(text)
        val type = element.jsonObject["type"]?.jsonPrimitive?.content ?: return HostMessage.Unknown("", text)

        when (type) {
            "hello_ack" -> HostMessage.HelloAck(protocolJson.decodeFromString<HelloAckMessage>(text))
            "hello_reject" -> HostMessage.HelloReject(protocolJson.decodeFromString<HelloRejectMessage>(text))
            "session_created" -> HostMessage.SessionCreated(protocolJson.decodeFromString<SessionCreatedMessage>(text))
            "session_resumed" -> HostMessage.SessionResumed(protocolJson.decodeFromString<SessionResumedMessage>(text))
            "session_resized" -> HostMessage.SessionResized(protocolJson.decodeFromString<SessionResizedMessage>(text))
            "session_stopped" -> HostMessage.SessionStopped(protocolJson.decodeFromString<SessionStoppedMessage>(text))
            "session_error" -> HostMessage.SessionError(protocolJson.decodeFromString<SessionErrorMessage>(text))
            "sdp" -> HostMessage.Sdp(protocolJson.decodeFromString<HostSdpMessage>(text))
            "ice" -> HostMessage.Ice(protocolJson.decodeFromString<HostIceMessage>(text))
            "stream_started" -> HostMessage.StreamStarted(protocolJson.decodeFromString<StreamStartedMessage>(text))
            "stream_stopped" -> HostMessage.StreamStopped(protocolJson.decodeFromString<StreamStoppedMessage>(text))
            "session_list_result" -> HostMessage.SessionListResult(protocolJson.decodeFromString<SessionListResultMessage>(text))
            "session_inspection" -> HostMessage.SessionInspection(protocolJson.decodeFromString<SessionInspectionMessage>(text))
            "probe_result" -> HostMessage.ProbeResult(protocolJson.decodeFromString<ProbeResultMessage>(text))
            "computer_info_result" -> HostMessage.ComputerInfoResult(protocolJson.decodeFromString<ComputerInfoResult>(text))
            "app_list_result" -> HostMessage.AppListResult(protocolJson.decodeFromString<AppListResultMessage>(text))
            "app_icons_result" -> HostMessage.AppIconsResult(protocolJson.decodeFromString<AppIconsResultMessage>(text))
            "file_offer", "file_accepted", "file_progress", "file_saved", "file_data",
            "file_finished", "file_cancelled", "file_error" ->
                HostMessage.File(protocolJson.decodeFromString<FileMessage>(text))
            "clipboard" -> HostMessage.Clipboard(protocolJson.decodeFromString<ClipboardText>(text))
            "clipboard_ack", "ack" -> HostMessage.ClipboardAck
            "notification_action" -> HostMessage.NotificationAction(protocolJson.decodeFromString<NotificationActionMessage>(text))
            "notification_dismiss" -> HostMessage.NotificationDismiss(protocolJson.decodeFromString<NotificationDismissMessage>(text))
            "ring" -> HostMessage.Ring(protocolJson.decodeFromString<RingMessage>(text))
            "media_state" -> HostMessage.Media(protocolJson.decodeFromString<MediaStateMessage>(text).state)
            "uplink_answer", "uplink_ice", "uplink_input", "uplink_stopped" ->
                HostMessage.Uplink(type, protocolJson.decodeFromString<UplinkHostMessage>(text))
            "sms_request" -> HostMessage.SmsRequest(protocolJson.decodeFromString<SmsRequestMessage>(text))
            "pong" -> HostMessage.Pong(protocolJson.decodeFromString<PongMessage>(text))
            "session_event" -> HostMessage.SessionEvent(protocolJson.decodeFromString<SessionEventMessage>(text))
            "error" -> HostMessage.Error(protocolJson.decodeFromString<ErrorMessage>(text))
            "pair_pending" -> HostMessage.PairPending(protocolJson.decodeFromString<PairPendingMessage>(text))
            "pair_result" -> HostMessage.PairResult(protocolJson.decodeFromString<PairResultMessage>(text))
            "unpaired" -> HostMessage.Unpaired(protocolJson.decodeFromString<UnpairedMessage>(text))
            else -> HostMessage.Unknown(type, text)
        }
    } catch (e: Exception) {
        // Log only the exception class — e.message may contain fragments
        // of the input JSON which could include tokens or SDP secrets.
        android.util.Log.w("Protocol", "Failed to parse host message (${e::class.simpleName})")
        // Redact raw text — subscribers that log Unknown messages must not
        // leak sensitive data from malformed messages.
        HostMessage.Unknown("parse_error", "<parse_error>")
    }
}

/** Serialize a client message to JSON text. */
inline fun <reified T> encodeClientMessage(msg: T): String =
    protocolJson.encodeToString(msg)
