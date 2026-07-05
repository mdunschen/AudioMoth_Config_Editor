package com.audiomoth.configeditor

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.USB_SERVICE
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.audiomoth.configeditor.ui.theme.AudioMothConfigEditorTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val APP_BUILD_TAG = "AudioMoth-USB-HID-v1.3-stable"
private const val VERBOSE_DEBUG_LOG = false

data class AudioMothDeviceInfo(
    val deviceId: String = "Unknown",
    val firmwareVersion: String = "Unknown",
    val firmwareDescription: String = "Unknown",
    val batteryState: String = "Unknown",
    val deviceTime: String = "Unknown",
    val rawDevice: UsbDevice? = null,
    val debugLog: String = ""
)

private fun formatFirmwareLabel(info: AudioMothDeviceInfo?): String {
    if (info == null) return ""
    val descRaw = info.firmwareDescription
    val versionRaw = info.firmwareVersion

    val desc = if (descRaw.isNotBlank() && descRaw != "Unknown") descRaw else ""
    val version = if (versionRaw.isNotBlank() && versionRaw != "Unknown") versionRaw else ""

    if (desc.isEmpty()) return version

    // Remove any leading "AudioMoth" and "Firmware" tokens (case-insensitive), plus surrounding separators
    var cleaned = desc.replace(Regex("(?i)AudioMoth[-_ ]*Firmware"), "")
    // Trim separators and whitespace
    cleaned = cleaned.replace(Regex("^[\\s\\-_:]+|[\\s\\-_:]+$"), "").trim()

    // If the cleaned description already contains a version-like token, don't append the separate firmwareVersion
    val versionPattern = Regex("\\d+\\.\\d+(?:\\.\\d+)?")
    val hasVersionInDesc = versionPattern.containsMatchIn(cleaned)

    return when {
        cleaned.isNotEmpty() && hasVersionInDesc -> cleaned
        cleaned.isNotEmpty() && version.isNotEmpty() -> "$cleaned $version"
        cleaned.isNotEmpty() -> cleaned
        else -> version
    }
}

private data class ApplyUsbResult(
    val applied: Boolean,
    val debugLog: String
)

private const val MAX_DEBUG_LOG_CHARS = 48000

private fun trimDebugLog(value: String): String {
    if (value.length <= MAX_DEBUG_LOG_CHARS) return value
    return value.takeLast(MAX_DEBUG_LOG_CHARS)
}

class MainActivity : ComponentActivity() {
    private val ACTION_USB_PERMISSION = "com.audiomoth.configeditor.USB_PERMISSION"
    private var onPermissionResult: ((Boolean, UsbDevice?) -> Unit)? = null
    private var currentDeviceInfo by mutableStateOf<AudioMothDeviceInfo?>(null)
    private var applyInProgress by mutableStateOf(false)
    private var usbLifecycleReceiver: BroadcastReceiver? = null

    /**
     * Reads supported device diagnostics on a background thread and publishes the result to the UI.
     */
    private fun readAndPublishDeviceInfo(usbDevice: UsbDevice) {
        Thread {
            val info = readDeviceInfo(usbDevice)
            runOnUiThread {
                currentDeviceInfo = info
            }
        }.start()
    }

    // ---------- USB helpers ----------

    private fun findInterruptEndpoint(usbInterface: UsbInterface, direction: Int): UsbEndpoint? {
        return (0 until usbInterface.endpointCount)
            .map { usbInterface.getEndpoint(it) }
            .firstOrNull { endpoint ->
                endpoint.direction == direction &&
                    endpoint.type == UsbConstants.USB_ENDPOINT_XFER_INT
            }
    }

    private fun pollHidInput(connection: UsbDeviceConnection, endpoint: UsbEndpoint, timeoutMs: Int = 250): ByteArray? {
        val buffer = ByteArray(endpoint.maxPacketSize.coerceAtLeast(8))
        val length = connection.bulkTransfer(endpoint, buffer, buffer.size, timeoutMs)
        return if (length > 0) buffer.copyOf(length) else null
    }

    private fun sendHidReport(connection: UsbDeviceConnection, endpoint: UsbEndpoint, report: ByteArray, timeoutMs: Int = 1000): Int {
        return connection.bulkTransfer(endpoint, report, report.size, timeoutMs)
    }

    private fun hasInterruptEndpoints(usbInterface: UsbInterface): Boolean {
        var hasIn = false
        var hasOut = false
        for (i in 0 until usbInterface.endpointCount) {
            val endpoint = usbInterface.getEndpoint(i)
            if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_INT) {
                if (endpoint.direction == UsbConstants.USB_DIR_IN) hasIn = true
                if (endpoint.direction == UsbConstants.USB_DIR_OUT) hasOut = true
            }
        }
        return hasIn && hasOut
    }

    private fun usbCommandRoundTrip(
        connection: UsbDeviceConnection,
        interruptInEndpoint: UsbEndpoint?,
        interruptOutEndpoint: UsbEndpoint?,
        payload: ByteArray = ByteArray(0)
    ): ByteArray? {
        // The AudioMoth command used for app-packet exchanges and the
        // associated timing/read parameters are fixed for this transport.
        val COMMAND: Byte = 0x06.toByte()
        val TIMEOUT_MS = 800
        val MAX_READS = 6
        if (interruptOutEndpoint == null || interruptInEndpoint == null) return null

        // AudioMoth firmware can expect either:
        // 1) report-id layout: [0]=0x00, [1]=cmd, payload starts at [2]
        // 2) no-report-id layout: [0]=cmd, payload starts at [1]
        data class Layout(val first: Byte, val second: Byte?, val payloadOffset: Int)
        val layouts = listOf(
            Layout(first = 0x00, second = COMMAND, payloadOffset = 2),
            Layout(first = COMMAND, second = null, payloadOffset = 1)
        )

        for (layout in layouts) {
            val report = ByteArray(64)
            report[0] = layout.first
            if (layout.second != null) {
                report[1] = layout.second
            }

            val maxPayload = (64 - layout.payloadOffset).coerceAtLeast(0)
            val copyLength = minOf(maxPayload, payload.size)
            if (copyLength > 0) {
                System.arraycopy(payload, 0, report, layout.payloadOffset, copyLength)
            }

            val written = sendHidReport(connection, interruptOutEndpoint, report, TIMEOUT_MS)
            if (written < 0) continue

            // Give device a short processing window before polling for interrupt IN response.
            Thread.sleep(25)

            repeat(MAX_READS) {
                val rx = pollHidInput(connection, interruptInEndpoint, TIMEOUT_MS)
                if (rx != null && rx.isNotEmpty()) {
                    if (rx.all { it == 0.toByte() }) return@repeat
                    if (rx[0] == COMMAND) return rx
                    if (rx.size > 1 && rx[0] == 0.toByte() && rx[1] == COMMAND) return rx
                }
            }
        }

        return null
    }

    // Convenience wrapper for the common AudioMoth "app packet" command parameters
    private fun usbAppPacketRoundTrip(
        connection: UsbDeviceConnection,
        interruptInEndpoint: UsbEndpoint?,
        interruptOutEndpoint: UsbEndpoint?,
        payload: ByteArray = ByteArray(0)
    ): ByteArray? {
        return usbCommandRoundTrip(
            connection = connection,
            interruptInEndpoint = interruptInEndpoint,
            interruptOutEndpoint = interruptOutEndpoint,
            payload = payload
        )
    }


    private fun writeUsbAppPacket(
        connection: UsbDeviceConnection,
        interruptInEndpoint: UsbEndpoint?,
        interruptOutEndpoint: UsbEndpoint?,
        packet: ByteArray,
        debug: StringBuilder
    ): Pair<Boolean, Boolean> {
        val response = usbAppPacketRoundTrip(
            connection = connection,
            interruptInEndpoint = interruptInEndpoint,
            interruptOutEndpoint = interruptOutEndpoint,
            payload = packet.copyOf(62)
        )

        if (response == null || response.size < 2) {
            debug.append("SetPacket:NoAck ")
            return Pair(false, false)
        }

        val isCommandAck = response[0] == 0x06.toByte() || (response[0] == 0.toByte() && response[1] == 0x06.toByte())
        if (!isCommandAck) {
            debug.append("SetPacket:BadAck ")
            return Pair(false, false)
        }

        if (response.size < 63) {
            debug.append("SetPacket:AckOnly(${response.size}) ")
            return Pair(true, false)
        }

        val dataOffset = if (response[0] == 0.toByte() && response[1] == 0x06.toByte()) 2 else 1
        if (response.size < dataOffset + 62) {
            debug.append("SetPacket:AckShort(${response.size}) ")
            return Pair(true, false)
        }

        var matches = true
        for (i in 0 until 62) {
            if (response[i + dataOffset] != packet[i]) {
                matches = false
                break
            }
        }
        debug.append(if (matches) "SetPacket:EchoOK " else "SetPacket:EchoMismatch ")
        return Pair(true, matches)
    }

    private fun describeUsbDevice(device: UsbDevice): String {
        val builder = StringBuilder()
        builder.append("Device VID=0x${device.vendorId.toString(16).uppercase()} PID=0x${device.productId.toString(16).uppercase()}\n")
        builder.append("Interfaces=${device.interfaceCount}\n")
        for (index in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(index)
            builder.append("  IF${index}: class=0x${usbInterface.interfaceClass.toString(16).uppercase()} subclass=0x${usbInterface.interfaceSubclass.toString(16).uppercase()} protocol=0x${usbInterface.interfaceProtocol.toString(16).uppercase()} endpoints=${usbInterface.endpointCount}\n")
            for (endpointIndex in 0 until usbInterface.endpointCount) {
                val endpoint = usbInterface.getEndpoint(endpointIndex)
                val dir = when (endpoint.direction) {
                    UsbConstants.USB_DIR_IN -> "IN"
                    UsbConstants.USB_DIR_OUT -> "OUT"
                    else -> "?"
                }
                val type = when (endpoint.type) {
                    UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CTRL"
                    0x01 -> "ISO"
                    UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
                    UsbConstants.USB_ENDPOINT_XFER_INT -> "INT"
                    else -> "?"
                }
                builder.append("    EP${endpointIndex}: $dir $type addr=0x${endpoint.address.toString(16).uppercase()} max=${endpoint.maxPacketSize}\n")
            }
        }
        return builder.toString()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.d("USB", "App Started - Scanning devices [$APP_BUILD_TAG]")
        val initialDevice = findAudioMothDevice(this)
        if (initialDevice != null) {
            Log.d("USB", "Found device on start, requesting permission")
            requestUsbPermission(initialDevice)
        }

        usbLifecycleReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                        if (device != null) {
                            currentDeviceInfo = null
                            onPermissionResult = null
                            Toast.makeText(context, "AudioMoth disconnected", Toast.LENGTH_SHORT).show()
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        val device = findAudioMothDevice(context)
                        if (device != null) {
                            requestUsbPermission(device)
                        }
                    }
                }
            }
        }

        val deviceLifecycleFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbLifecycleReceiver, deviceLifecycleFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbLifecycleReceiver, deviceLifecycleFilter)
        }

        setContent {
            AudioMothConfigEditorTheme {
                val deviceInfo = currentDeviceInfo
                val isApplyingConfig = applyInProgress

                // Set up the listener for initial permission request
                onPermissionResult = { granted, usbDevice ->
                    if (granted && usbDevice != null) {
                        readAndPublishDeviceInfo(usbDevice)
                    }
                }

                MainScreen(
                    deviceInfo = deviceInfo,
                    onRequestUsbPermission = { device ->
                        onPermissionResult = { granted, usbDevice ->
                            if (granted && usbDevice != null) {
                                readAndPublishDeviceInfo(usbDevice)
                            } else {
                                currentDeviceInfo = null
                            }
                        }
                        requestUsbPermission(device)
                    },
                    onSyncTime = { device ->
                        Thread {
                            syncTime(device)
                        }.start()
                    },
                    isApplyingConfig = isApplyingConfig,
                    onApplyConfig = { device, config ->
                        if (applyInProgress) return@MainScreen
                        applyInProgress = true
                        val priorDebugLog = currentDeviceInfo?.debugLog ?: ""
                        Thread {
                            runCatching {
                                applyConfigViaUsb(device, config, priorDebugLog)
                            }.onSuccess { result ->
                                runOnUiThread {
                                    runCatching {
                                        currentDeviceInfo = currentDeviceInfo?.copy(
                                                debugLog = trimDebugLog(result.debugLog)
                                            )
                                        Toast.makeText(
                                            this@MainActivity,
                                            if (result.applied) "Configuration applied via USB" else "Failed to apply configuration",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }.onFailure { uiError ->
                                        Log.e("USB", "Apply UI update failed", uiError)
                                    }
                                    applyInProgress = false
                                }
                            }.onFailure { error ->
                                Log.e("USB", "Apply failed with exception", error)
                                runOnUiThread {
                                    runCatching {
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Failed to apply configuration",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                    applyInProgress = false
                                }
                            }
                        }.start()
                    }
                )
            }
        }
    }

    private fun readDeviceInfo(device: UsbDevice): AudioMothDeviceInfo {
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        val vidStr = "0x%04X".format(device.vendorId)
        val pidStr = "0x%04X".format(device.productId)
        Log.d("USB", "Opening device: VID=$vidStr PID=$pidStr")
        
        val connection = usbManager.openDevice(device) ?: return AudioMothDeviceInfo(deviceId = "Failed to open")
        
        val debug = StringBuilder()
        debug.append("Build:$APP_BUILD_TAG\n")
        debug.append(describeUsbDevice(device))
        debug.append("VID:$vidStr PID:$pidStr\n")

        return try {
            // Probe HID plus vendor-specific interrupt interfaces, since some AudioMoth firmware
            // exposes command transport on a non-HID class interface.
            val probeInterfaces = (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .filter { usbInterface ->
                    val isSupportedClass =
                        usbInterface.interfaceClass == UsbConstants.USB_CLASS_HID ||
                        usbInterface.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC
                    val hasInterruptEndpoint = (0 until usbInterface.endpointCount)
                        .map { usbInterface.getEndpoint(it) }
                        .any { it.type == UsbConstants.USB_ENDPOINT_XFER_INT }
                    isSupportedClass && hasInterruptEndpoint
                }

            if (probeInterfaces.isEmpty()) return AudioMothDeviceInfo(deviceId = "No probe interface found", debugLog = debug.toString())
            debug.append("ProbeInterfaces:${probeInterfaces.size}\n")

            var finalDeviceInfo: AudioMothDeviceInfo? = null
            var fallbackDeviceInfo: AudioMothDeviceInfo? = null

            fun isAllZero(data: ByteArray, count: Int = data.size): Boolean {
                if (count <= 0) return true
                return data.take(count).all { it == 0.toByte() }
            }

            fun runProbe(
                cmd: Byte,
                usbInterface: UsbInterface,
                interruptInEndpoint: UsbEndpoint?,
                interruptOutEndpoint: UsbEndpoint?
            ): ByteArray {
                val response = ByteArray(64)
                val packet = ByteArray(64)

                debug.append("${"%02X".format(cmd)}: ")

                val searchParams = listOf(
                    Pair(0x0300, 0),
                    Pair(0x0300 or (cmd.toInt() and 0xFF), 0),
                    Pair(0x0300, usbInterface.id),
                    Pair(0x0300 or (cmd.toInt() and 0xFF), usbInterface.id)
                )
                val packetLayouts = listOf(
                    Pair(0x00.toByte(), cmd),
                    Pair(cmd, 0x00.toByte())
                )

                // First prefer interrupt OUT/IN transport on the active interface.
                if (interruptOutEndpoint != null && interruptInEndpoint != null) {
                    for ((b0, b1) in packetLayouts) {
                        packet.fill(0)
                        packet[0] = b0
                        packet[1] = b1

                        val sent = sendHidReport(connection, interruptOutEndpoint, packet, 500)
                        debug.append("Out$sent ")
                        if (sent >= 0) {
                            Thread.sleep(50)
                            val rx = pollHidInput(connection, interruptInEndpoint, 500)
                            if (rx != null && rx.isNotEmpty()) {
                                val hex = rx.joinToString(" ") { "%02X".format(it) }
                                if (isAllZero(rx)) {
                                    debug.append("InZero(${rx.size})")
                                    if (VERBOSE_DEBUG_LOG) debug.append(" $hex")
                                    debug.append(" ")
                                } else {
                                    debug.append("In(${rx.size}) $hex ")
                                    return rx.copyOf()
                                }
                            }
                        }
                    }
                }

                // Fallback to control-transfer probes for interfaces/firmware requiring SET/GET_REPORT.
                for ((v, idx) in searchParams) {
                    for ((b0, b1) in packetLayouts) {
                        packet.fill(0)
                        packet[0] = b0
                        packet[1] = b1

                        val sent = connection.controlTransfer(0x21, 0x09, v, idx, packet, 64, 500)
                        if (sent >= 0) {
                            Thread.sleep(20)
                            val rx = connection.controlTransfer(0xA1, 0x01, v, idx, response, 64, 500)
                            if (rx > 0) {
                                val hex = response.take(rx).joinToString(" ") { "%02X".format(it) }
                                if (isAllZero(response, rx)) {
                                    debug.append("CtrlZero(${v.toString(16)},$idx)")
                                    if (VERBOSE_DEBUG_LOG) debug.append(" $hex")
                                    debug.append(" ")
                                } else {
                                    debug.append("CtrlOK(${v.toString(16)},$idx) $hex ")
                                    return response.copyOf()
                                }
                            } else {
                                debug.append("CtrlRx$rx ")
                            }
                        } else {
                            val rxDirect = connection.controlTransfer(0xA1, 0x01, v, idx, response, 64, 500)
                            if (rxDirect > 0) {
                                val hex = response.take(rxDirect).joinToString(" ") { "%02X".format(it) }
                                if (isAllZero(response, rxDirect)) {
                                    debug.append("CtrlDirectZero")
                                    if (VERBOSE_DEBUG_LOG) debug.append(" $hex")
                                    debug.append(" ")
                                } else {
                                    debug.append("CtrlDirectOK $hex ")
                                    return response.copyOf()
                                }
                            }
                        }
                    }
                }

                debug.append("FAIL ")
                return ByteArray(0)
            }

            fun parseResponse(response: ByteArray, expectedCommand: Byte): ByteArray? {
                if (response.isEmpty()) return null
                if (response[0] == expectedCommand) return response.sliceArray(1 until response.size)
                if (response.size > 1 && response[0] == 0.toByte() && response[1] == expectedCommand) {
                    return response.sliceArray(2 until response.size)
                }
                return null
            }

            for (usbInterface in probeInterfaces) {
                val interfaceId = usbInterface.id
                debug.append("TryIntf:$interfaceId class=0x${usbInterface.interfaceClass.toString(16)} sub=0x${usbInterface.interfaceSubclass.toString(16)} proto=0x${usbInterface.interfaceProtocol.toString(16)} \n")

                val claimed = connection.claimInterface(usbInterface, true)
                debug.append("Intf:$interfaceId Claim:$claimed\n")
                if (!claimed) {
                    debug.append("Intf:$interfaceId ClaimFailed\n")
                    continue
                }

                val interfaceSet = connection.setInterface(usbInterface)
                debug.append("Intf:$interfaceId SetIface:$interfaceSet\n")

                val interruptInEndpoint = findInterruptEndpoint(usbInterface, UsbConstants.USB_DIR_IN)
                val interruptOutEndpoint = findInterruptEndpoint(usbInterface, UsbConstants.USB_DIR_OUT)
                if (interruptInEndpoint != null || interruptOutEndpoint != null) {
                    debug.append("HidIn:${interruptInEndpoint?.address ?: -1} HidOut:${interruptOutEndpoint?.address ?: -1} max:${interruptInEndpoint?.maxPacketSize ?: interruptOutEndpoint?.maxPacketSize ?: -1}\n")
                    val probeReport = ByteArray(64)
                    probeReport[0] = 0x00
                    probeReport[1] = 0x01
                    if (interruptOutEndpoint != null) {
                        val written = sendHidReport(connection, interruptOutEndpoint, probeReport, 500)
                        debug.append("WriteProbe:$written\n")
                    }
                    if (interruptInEndpoint != null) {
                        repeat(2) { index ->
                            val report = pollHidInput(connection, interruptInEndpoint, 200)
                            if (report != null) {
                                val hex = report.joinToString(" ") { "%02X".format(it) }
                                debug.append("Poll[$index]=$hex\n")
                            } else {
                                debug.append("Poll[$index]=<none>\n")
                            }
                            Thread.sleep(50)
                        }
                    } else {
                        debug.append("No interrupt IN endpoint\n")
                    }
                } else {
                    debug.append("No interrupt endpoints\n")
                }

                Thread.sleep(100)

                val timeResponse = runProbe(0x01, usbInterface, interruptInEndpoint, interruptOutEndpoint)
                val timeData = parseResponse(timeResponse, 0x01)

                val uidResponse = runProbe(0x03, usbInterface, interruptInEndpoint, interruptOutEndpoint)
                val uidData = parseResponse(uidResponse, 0x03)

                val versionResponse = runProbe(0x07, usbInterface, interruptInEndpoint, interruptOutEndpoint)
                val versionData = parseResponse(versionResponse, 0x07)

                val descriptionResponse = runProbe(0x08, usbInterface, interruptInEndpoint, interruptOutEndpoint)
                val descriptionData = parseResponse(descriptionResponse, 0x08)

                val batteryResponse = runProbe(0x04, usbInterface, interruptInEndpoint, interruptOutEndpoint)
                val batteryData = parseResponse(batteryResponse, 0x04)

                val timestamp = if (timeData != null && timeData.size >= 4) {
                    (timeData[0].toLong() and 0xFF) or
                    ((timeData[1].toLong() and 0xFF) shl 8) or
                    ((timeData[2].toLong() and 0xFF) shl 16) or
                    ((timeData[3].toLong() and 0xFF) shl 24)
                } else 0L

                val deviceTime = if (timestamp == 0L) "Not Set" else {
                    SimpleDateFormat("HH:mm:ss dd/MM/yyyy", Locale.getDefault()).format(Date(timestamp * 1000))
                }

                val uid = uidData?.sliceArray(0..7)?.reversedArray()?.joinToString("") { "%02X".format(it) } ?: ""
                val version = if (versionData != null && versionData.size >= 3) "${versionData[0]}.${versionData[1]}.${versionData[2]}" else "Unknown"
                val description = if (descriptionData != null) String(descriptionData).trim { it <= ' ' } else device.productName ?: "Unknown"
                val batteryState = if (batteryData != null && batteryData.isNotEmpty()) {
                    val state = batteryData[0].toInt() and 0xFF
                    if (state == 0) "< 3.6V" else if (state == 15) "> 4.9V" else "%.1fV".format(3.5 + state.toDouble() / 10.0)
                } else "Unknown"

                val candidateInfo = AudioMothDeviceInfo(
                    deviceId = if (uid.isEmpty()) "VID:$vidStr PID:$pidStr" else uid,
                    firmwareVersion = version,
                    firmwareDescription = description,
                    batteryState = batteryState,
                    deviceTime = deviceTime,
                    rawDevice = device,
                    debugLog = debug.toString()
                )

                if (fallbackDeviceInfo == null) {
                    fallbackDeviceInfo = candidateInfo
                }

                val hasEnoughInfo = uid.isNotEmpty() || versionData != null || descriptionData != null || batteryData != null

                connection.releaseInterface(usbInterface)

                if (hasEnoughInfo) {
                    finalDeviceInfo = candidateInfo
                    break
                }
            }

            finalDeviceInfo ?: fallbackDeviceInfo ?: AudioMothDeviceInfo(deviceId = "No valid HID response", debugLog = debug.toString())
        } catch (e: Exception) {
            Log.e("USB", "Error reading info", e)
            AudioMothDeviceInfo(deviceId = "Error: ${e.message}", debugLog = debug.toString())
        } finally {
            connection.close()
        }
    }

    private fun requestUsbPermission(device: UsbDevice) {
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        val permissionIntent = PendingIntent.getBroadcast(
            this, 
            0, 
            Intent(ACTION_USB_PERMISSION), 
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        )
        
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        val usbReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (ACTION_USB_PERMISSION == intent.action) {
                    synchronized(this) {
                        val grantedDevice: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                        
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        onPermissionResult?.invoke(granted, grantedDevice)
                        
                        if (granted) {
                            Toast.makeText(context, "Reading device info...", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Permission denied", Toast.LENGTH_SHORT).show()
                        }
                    }
                    unregisterReceiver(this)
                }
            }
        }
        
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun syncTime(device: UsbDevice) {
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        val connection = usbManager.openDevice(device) ?: return
        
        try {
            val usbInterface = (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .find { it.interfaceClass == UsbConstants.USB_CLASS_HID }
                ?: return

            if (!connection.claimInterface(usbInterface, true)) return
            connection.setInterface(usbInterface)

            val now = System.currentTimeMillis() / 1000
            val packet = ByteArray(64)
            packet[0] = 0x00
            packet[1] = 0x02 // SET_TIME
            packet[2] = (now and 0xFF).toByte()
            packet[3] = ((now shr 8) and 0xFF).toByte()
            packet[4] = ((now shr 16) and 0xFF).toByte()
            packet[5] = ((now shr 24) and 0xFF).toByte()

            val interruptOutEndpoint = findInterruptEndpoint(usbInterface, UsbConstants.USB_DIR_OUT)
            val wValueFeature = (0x03 shl 8) or 0x00
            var sent = -1
            if (interruptOutEndpoint != null) {
                sent = sendHidReport(connection, interruptOutEndpoint, packet, 500)
            }
            if (sent < 0) {
                val packet2 = ByteArray(64)
                packet2[0] = 0x00
                packet2[1] = 0x02
                packet2[2] = (now and 0xFF).toByte()
                packet2[3] = ((now shr 8) and 0xFF).toByte()
                packet2[4] = ((now shr 16) and 0xFF).toByte()
                packet2[5] = ((now shr 24) and 0xFF).toByte()
                sent = connection.controlTransfer(0x21, 0x09, wValueFeature, usbInterface.id, packet2, 64, 500)
            }
            
            runOnUiThread {
                if (sent >= 0) {
                    Toast.makeText(this@MainActivity, "Time synchronized", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Failed to sync time", Toast.LENGTH_SHORT).show()
                }
            }
            
            connection.releaseInterface(usbInterface)
        } finally {
            connection.close()
        }
    }

    private fun applyConfigViaUsb(
        device: UsbDevice,
        config: AudioMothConfig,
        priorDebugLog: String = ""
    ): ApplyUsbResult {
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        val connection = usbManager.openDevice(device) ?: return ApplyUsbResult(false, priorDebugLog)
        val debug = StringBuilder()
        debug.append("Apply Build:$APP_BUILD_TAG\n")
        debug.append("Apply VID:0x%04X PID:0x%04X\n".format(device.vendorId, device.productId))

        var applied = false
        try {
            val probeInterfaces = (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .filter {
                    (it.interfaceClass == UsbConstants.USB_CLASS_HID || it.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC) &&
                        hasInterruptEndpoints(it)
                }

            for (usbInterface in probeInterfaces) {
                debug.append("TryIntf:${usbInterface.id} class=0x${usbInterface.interfaceClass.toString(16)}\n")
                if (!connection.claimInterface(usbInterface, true)) {
                    debug.append("Claim:Fail\n")
                    continue
                }
                connection.setInterface(usbInterface)

                val interruptInEndpoint = findInterruptEndpoint(usbInterface, UsbConstants.USB_DIR_IN)
                val interruptOutEndpoint = findInterruptEndpoint(usbInterface, UsbConstants.USB_DIR_OUT)
                debug.append("In:${interruptInEndpoint?.address ?: -1} Out:${interruptOutEndpoint?.address ?: -1}\n")

                val packetToWrite = AcousticConfigBuilder.buildUsbAppPacket(config)
                debug.append("ApplyTarget:PreparedPacket\n")

                val writeResult = writeUsbAppPacket(
                    connection = connection,
                    interruptInEndpoint = interruptInEndpoint,
                    interruptOutEndpoint = interruptOutEndpoint,
                    packet = packetToWrite,
                    debug = debug
                )

                val ackOk = writeResult.first
                val echoOk = writeResult.second
                debug.append("ApplyAck:${if (ackOk) "OK" else "Fail"} Echo:${if (echoOk) "OK" else "Unavailable"}\n")

                val applyOk = ackOk

                if (applyOk) {
                    applied = true
                    debug.append("Apply:Success\n")
                    connection.releaseInterface(usbInterface)
                    break
                }

                connection.releaseInterface(usbInterface)
            }

            val mergedDebug = buildString {
                if (priorDebugLog.isNotBlank()) append(priorDebugLog)
                if (isNotEmpty()) append("\n")
                append(debug.toString())
            }

            return ApplyUsbResult(applied, trimDebugLog(mergedDebug))
        } finally {
            connection.close()
        }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    deviceInfo: AudioMothDeviceInfo?,
    onRequestUsbPermission: (UsbDevice) -> Unit,
    onSyncTime: (UsbDevice) -> Unit,
    isApplyingConfig: Boolean,
    onApplyConfig: (UsbDevice, AudioMothConfig) -> Unit
) {
    var currentScreen by remember { mutableStateOf(Screen.HOME) }
    var selectedConfig by remember { mutableStateOf<AudioMothConfig?>(null) }
    var autoLoadedDeviceKey by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val connectedDevice = deviceInfo?.rawDevice
    // Controls the diagnostics dialog opened from the top app bar
    val showTopDiagnosticsDialog = remember { mutableStateOf(false) }

    LaunchedEffect(deviceInfo) {
        if (deviceInfo == null) {
            autoLoadedDeviceKey = null
            return@LaunchedEffect
        }

        val deviceKey = connectedDevice?.deviceId?.toString() ?: deviceInfo.deviceId

        if (autoLoadedDeviceKey != deviceKey) {
            if (selectedConfig == null) {
                selectedConfig = AudioMothConfig()
            }
            currentScreen = Screen.EDIT
            autoLoadedDeviceKey = deviceKey
        }
    }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        uri?.let {
            context.contentResolver.openOutputStream(it)?.use { outputStream ->
                outputStream.write(selectedConfig?.toJsonString()?.toByteArray() ?: byteArrayOf())
            }
            currentScreen = Screen.HOME
        }
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            context.contentResolver.openInputStream(it)?.use { inputStream ->
                val json = inputStream.bufferedReader().use { reader -> reader.readText() }
                AudioMothConfig.fromJsonString(json)?.let { config ->
                    selectedConfig = config
                    currentScreen = Screen.EDIT
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("AudioMoth Config Editor")
                        Text(
                            "Build $APP_BUILD_TAG",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                        )
                    }
                },
                actions = {
                    if (deviceInfo != null) {
                        IconButton(onClick = { showTopDiagnosticsDialog.value = true }) {
                            Icon(Icons.Default.Info, contentDescription = "Device Diagnostics", tint = MaterialTheme.colorScheme.onPrimary)
                        }

                        if (connectedDevice != null) {
                            IconButton(onClick = { onSyncTime(connectedDevice) }) {
                                Icon(Icons.Default.Sync, contentDescription = "Sync Time", tint = MaterialTheme.colorScheme.onPrimary)
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { innerPadding ->
        // Diagnostics dialog invoked from the top app bar
        Box(modifier = Modifier.padding(innerPadding)) {
            if (deviceInfo != null && showTopDiagnosticsDialog.value) {
                DeviceDiagnosticsDialog(
                    info = deviceInfo,
                    onSyncTime = { connectedDevice?.let(onSyncTime) },
                    onDismiss = { showTopDiagnosticsDialog.value = false }
                )
            }
            when (currentScreen) {
                Screen.HOME -> HomeScreen(
                    deviceInfo = deviceInfo,
                    onLoadConfig = {
                        openDocumentLauncher.launch(arrayOf("*/*"))
                    },
                    onNewConfig = {
                        selectedConfig = AudioMothConfig()
                        currentScreen = Screen.EDIT
                    },
                    onTestUsb = {
                        val device = findAudioMothDevice(context)
                        if (device != null) {
                            onRequestUsbPermission(device)
                        } else {
                            Toast.makeText(context, "No AudioMoth HID device detected", Toast.LENGTH_LONG).show()
                        }
                    },
                    onSyncTime = { connectedDevice?.let(onSyncTime) }
                )
                Screen.EDIT -> EditScreen(
                    config = selectedConfig ?: AudioMothConfig(),
                    usbDeviceConnected = connectedDevice != null,
                    isApplyingConfig = isApplyingConfig,
                    deviceInfo = deviceInfo,
                    onSave = { config ->
                        selectedConfig = config
                        val sdf = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())
                        val fileName = "audiomoth${sdf.format(Date())}.config"
                        createDocumentLauncher.launch(fileName)
                    },
                    onApplyUsb = { config ->
                        connectedDevice?.let { onApplyConfig(it, config) }
                    },
                    onCancel = {
                        currentScreen = Screen.HOME
                    }
                )
            }
        }
    }
}

    override fun onDestroy() {
        usbLifecycleReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        usbLifecycleReceiver = null
        super.onDestroy()
    }

}

private fun findAudioMothDevice(context: Context): UsbDevice? {
    val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    val devices = usbManager.deviceList.values
    
    Log.d("USB", "Scanning ${devices.size} USB devices...")
    
    return devices.find { device ->
        val vid = device.vendorId
        val pid = device.productId
        Log.d("USB", "Checking Device: VID=${"0x%04X".format(vid)} PID=${"0x%04X".format(pid)}")

        val hasHidInterface = (0 until device.interfaceCount)
            .any { device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_HID }

        // Match specific AudioMoth VIDs or any generic HID
        val isKnownAudioMoth = (vid == 0x10C4 && pid == 0x0001) || (vid == 0x10C4 && pid == 0x0002) || (vid == 0x04D8 && pid == 0xEF98)
        
        if (hasHidInterface) {
            if (isKnownAudioMoth) {
                Log.d("USB", "Found AudioMoth HID device")
            } else {
                Log.d("USB", "Found generic HID device: VID=${"0x%04X".format(vid)} PID=${"0x%04X".format(pid)}")
            }
        }
        
        hasHidInterface // Accepts any HID, but gives logging preference to AudioMoth
    }
}

enum class Screen {
    HOME, EDIT
}

@Composable
fun HomeScreen(
    deviceInfo: AudioMothDeviceInfo?,
    onLoadConfig: () -> Unit,
    onNewConfig: () -> Unit,
    onTestUsb: () -> Unit,
    onSyncTime: () -> Unit
) {
    val showDiagnosticsDialog = remember { mutableStateOf(false) }

    LaunchedEffect(deviceInfo?.deviceId) {
        if (deviceInfo == null) {
            showDiagnosticsDialog.value = false
        }
    }

    if (deviceInfo != null && showDiagnosticsDialog.value) {
        DeviceDiagnosticsDialog(
            info = deviceInfo,
            onSyncTime = onSyncTime,
            onDismiss = { showDiagnosticsDialog.value = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Top)
    ) {
        Button(
            onClick = onNewConfig,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Create New Configuration")
        }

        Button(
            onClick = onLoadConfig,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Icon(Icons.Default.FileOpen, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Load Configuration from File")
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            onClick = onTestUsb,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Icon(Icons.Default.Usb, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (deviceInfo == null) "Connect & Read AudioMoth" else "Refresh Device Info")
        }

        if (deviceInfo != null) {
            OutlinedButton(
                onClick = { showDiagnosticsDialog.value = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Icon(Icons.Default.BugReport, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Device Diagnostics")
            }
        }
    }
}

@Composable
fun DeviceDiagnosticsDialog(
    info: AudioMothDeviceInfo,
    onSyncTime: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager? }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Device Diagnostics")
                if (info.rawDevice != null) {
                    IconButton(onClick = onSyncTime) {
                        Icon(Icons.Default.Sync, contentDescription = "Sync Time")
                    }
                }
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                DiagnosticRow("Unique ID", info.deviceId)
                DiagnosticRow("Firmware", info.firmwareVersion)
                DiagnosticRow("Description", info.firmwareDescription)
                DiagnosticRow("Battery", info.batteryState)
                DiagnosticRow("Device Time", info.deviceTime)

                if (info.debugLog.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(onClick = {
                        val textToCopy = "AudioMoth Debug Log\nBuild: $APP_BUILD_TAG\n\n${info.debugLog}"
                        val clip = android.content.ClipData.newPlainText("AudioMoth Debug", textToCopy)
                        clipboardManager?.setPrimaryClip(clip)
                        Toast.makeText(context, "Debug log copied", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("Copy")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        info.debugLog,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
fun DiagnosticRow(
    label: String,
    value: String,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = labelColor
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            fontWeight = FontWeight.Bold,
            color = valueColor
        )
    }
}

@Composable
fun CurrentTimeDisplay(isDeviceConnected: Boolean) {
    var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            currentTime = System.currentTimeMillis()
            delay(1000)
        }
    }

    val dateFormat = SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault())
    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    val date = Date(currentTime)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    modifier = Modifier.size(10.dp),
                    shape = RoundedCornerShape(50),
                    color = if (isDeviceConnected) Color(0xFF4CAF50) else Color(0xFFD32F2F)
                ) {}
                Text(
                    text = timeFormat.format(date),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Text(
                text = dateFormat.format(date),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
fun EditScreen(
    config: AudioMothConfig,
    usbDeviceConnected: Boolean,
    isApplyingConfig: Boolean,
    deviceInfo: AudioMothDeviceInfo?,
    onSave: (AudioMothConfig) -> Unit,
    onApplyUsb: (AudioMothConfig) -> Unit,
    onCancel: () -> Unit
) {
    var editingConfig by remember { mutableStateOf(config) }
    val showPreview = remember { mutableStateOf(false) }

    if (showPreview.value) {
        ConfigPreviewDialog(
            config = editingConfig,
            onDismiss = { showPreview.value = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        CurrentTimeDisplay(isDeviceConnected = usbDeviceConnected)

        Spacer(modifier = Modifier.height(8.dp))
        DeviceStatusCard(info = deviceInfo, isDeviceConnected = usbDeviceConnected)
        Spacer(modifier = Modifier.height(8.dp))

        ConfigTabScreen(
            config = editingConfig,
            onConfigChange = { editingConfig = it },
            modifier = Modifier.weight(1f)
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (usbDeviceConnected) {
                Button(
                    onClick = {
                        if (!isApplyingConfig) {
                            onApplyUsb(editingConfig)
                        }
                    },
                    enabled = !isApplyingConfig,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary
                    )
                ) {
                    if (isApplyingConfig) {
                        Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Applying...", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    } else {
                        Icon(Icons.Default.Usb, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Apply", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    }
                }
            }

            Button(
                onClick = { onSave(editingConfig) },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
            ) {
                Text("Save", style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { showPreview.value = true },
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
            ) {
                Text("Preview", style = MaterialTheme.typography.labelLarge)
            }
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
            ) {
                Text("Cancel", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
fun DeviceStatusCard(info: AudioMothDeviceInfo?, isDeviceConnected: Boolean) {
    val titleColor = if (isDeviceConnected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    }
    val labelColor = if (isDeviceConnected) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    }
    val valueColor = if (isDeviceConnected) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(
            containerColor = if (isDeviceConnected) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Connected AudioMoth",
                style = MaterialTheme.typography.titleSmall,
                color = titleColor
            )
            DiagnosticRow(
                label = "Device ID",
                value = info?.deviceId.orEmpty(),
                labelColor = labelColor,
                valueColor = valueColor
            )
            DiagnosticRow(
                label = "Firmware",
                value = formatFirmwareLabel(info),
                labelColor = labelColor,
                valueColor = valueColor
            )
            DiagnosticRow(
                label = "Battery",
                value = info?.batteryState.orEmpty(),
                labelColor = labelColor,
                valueColor = valueColor
            )
        }
    }
}

@Composable
fun ConfigTabScreen(
    config: AudioMothConfig,
    onConfigChange: (AudioMothConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Recording", "Schedule", "Filtering", "Trigger", "Advanced")

    Column(modifier = modifier) {
        ScrollableTabRow(
            selectedTabIndex = selectedTab,
            edgePadding = 0.dp,
            modifier = Modifier.fillMaxWidth(),
            containerColor = Color.Transparent,
            divider = {}
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title, fontSize = 13.sp) }
                )
            }
        }

        Box(modifier = Modifier.weight(1f).padding(top = 8.dp)) {
            when (selectedTab) {
                0 -> RecordingTab(config, onConfigChange)
                1 -> ScheduleTab(config, onConfigChange)
                2 -> FilteringTab(config, onConfigChange)
                3 -> TriggerTab(config, onConfigChange)
                4 -> AdvancedTab(config, onConfigChange)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingTab(
    config: AudioMothConfig,
    onConfigChange: (AudioMothConfig) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        val sampleRates = listOf(384000, 250000, 192000, 96000, 48000)
        var expanded by remember { mutableStateOf(false) }

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            OutlinedTextField(
                value = config.sampleRate.toString(),
                onValueChange = {},
                readOnly = true,
                label = { Text("Sample Rate (Hz)") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                sampleRates.forEach { rate ->
                    DropdownMenuItem(
                        text = { Text(text = rate.toString()) },
                        onClick = {
                            onConfigChange(config.copy(sampleRate = rate))
                            expanded = false
                        }
                    )
                }
            }
        }

        var recordDurationText by remember { mutableStateOf(config.recordDuration.toString()) }
        var sleepDurationText by remember { mutableStateOf(config.sleepDuration.toString()) }
        var gainText by remember { mutableStateOf(config.gain.toString()) }

        OutlinedTextField(
            value = recordDurationText,
            onValueChange = { recordDurationText = it; tryUpdateConfig(it, "recordDuration", config, onConfigChange) },
            label = { Text("Record Duration (s)") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            textStyle = MaterialTheme.typography.bodyMedium
        )

        OutlinedTextField(
            value = sleepDurationText,
            onValueChange = { sleepDurationText = it; tryUpdateConfig(it, "sleepDuration", config, onConfigChange) },
            label = { Text("Sleep Duration (s)") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            textStyle = MaterialTheme.typography.bodyMedium
        )

        OutlinedTextField(
            value = gainText,
            onValueChange = { gainText = it; tryUpdateConfig(it, "gain", config, onConfigChange) },
            label = { Text("Gain") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            textStyle = MaterialTheme.typography.bodyMedium
        )

        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = config.ledEnabled,
                onCheckedChange = { onConfigChange(config.copy(ledEnabled = it)) }
            )
            Text("LED Enabled", style = MaterialTheme.typography.bodyMedium)
        }

        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = config.lowVoltageCutoffEnabled,
                onCheckedChange = { onConfigChange(config.copy(lowVoltageCutoffEnabled = it)) }
            )
            Text("Low Voltage Cutoff", style = MaterialTheme.typography.bodyMedium)
        }

        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = config.dutyEnabled,
                onCheckedChange = { onConfigChange(config.copy(dutyEnabled = it)) }
            )
            Text("Duty Enabled", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun ScheduleTab(
    config: AudioMothConfig,
    onConfigChange: (AudioMothConfig) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        ScheduleTimeline(timePeriods = config.timePeriods)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                val newPeriod = TimePeriod(startMins = 0, endMins = 60)
                onConfigChange(config.copy(timePeriods = (config.timePeriods + newPeriod).sortedBy { it.startMins }))
            }) {
                Icon(Icons.Default.Add, contentDescription = "Add Period", modifier = Modifier.size(24.dp))
            }

            TextButton(onClick = {
                onConfigChange(config.copy(timePeriods = emptyList()))
            }, contentPadding = PaddingValues(0.dp)) {
                Text("Clear All", fontSize = 14.sp)
            }
        }

        Box(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Column {
                config.timePeriods.forEachIndexed { index, period ->
                    PeriodItem(
                        period = period,
                        onDelete = {
                            val newList = config.timePeriods.toMutableList().apply { removeAt(index) }
                            onConfigChange(config.copy(timePeriods = newList))
                        },
                        onUpdate = { updatedPeriod ->
                            val newList = config.timePeriods.toMutableList().apply { this[index] = updatedPeriod }
                            onConfigChange(config.copy(timePeriods = newList.sortedBy { it.startMins }))
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilteringTab(
    config: AudioMothConfig,
    onConfigChange: (AudioMothConfig) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        val displayFilterTypes = listOf("None", "Low", "Band", "High")
        val internalFilterTypes = listOf("none", "low-pass", "band-pass", "high-pass")
        
        var expanded by remember { mutableStateOf(false) }

        val currentDisplayType = when (config.filterType) {
            "low-pass" -> "Low"
            "band-pass" -> "Band"
            "high-pass" -> "High"
            else -> "None"
        }

        Text("Filter Type", style = MaterialTheme.typography.titleSmall)
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            OutlinedTextField(
                value = currentDisplayType,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                displayFilterTypes.forEachIndexed { index, type ->
                    DropdownMenuItem(
                        text = { Text(text = type) },
                        onClick = {
                            val internalValue = internalFilterTypes[index]
                            onConfigChange(config.copy(
                                filterType = internalValue,
                                passFiltersEnabled = internalValue != "none"
                            ))
                            expanded = false
                        }
                    )
                }
            }
        }

        if (config.filterType != "none") {
            Spacer(modifier = Modifier.height(24.dp))
            
            Text("Frequency Selection", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("0 kHz", style = MaterialTheme.typography.labelSmall)
                Text("24 kHz", style = MaterialTheme.typography.labelSmall)
            }

            if (config.filterType == "band-pass") {
                RangeSlider(
                    value = config.lowerFilter.toFloat()..config.higherFilter.toFloat(),
                    onValueChange = { range ->
                        val start = (range.start / 100).toInt() * 100
                        val end = (range.endInclusive / 100).toInt() * 100
                        onConfigChange(config.copy(lowerFilter = start, higherFilter = end))
                    },
                    valueRange = 0f..24000f,
                    steps = 239,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                val sliderValue = if (config.filterType == "low-pass") config.higherFilter.toFloat() else config.lowerFilter.toFloat()
                
                // For High-pass, we want the track to the RIGHT of the thumb to be colored (green/primary).
                // Slider highlights activeTrack (from left to thumb).
                // So for High-pass, we swap active and inactive track colors.
                val sliderColors = if (config.filterType == "high-pass") {
                    SliderDefaults.colors(
                        activeTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                        inactiveTrackColor = MaterialTheme.colorScheme.primary
                    )
                } else {
                    SliderDefaults.colors(
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }

                Slider(
                    value = sliderValue,
                    onValueChange = { value ->
                        val freq = (value / 100).toInt() * 100
                        if (config.filterType == "low-pass") {
                            onConfigChange(config.copy(higherFilter = freq))
                        } else {
                            onConfigChange(config.copy(lowerFilter = freq))
                        }
                    },
                    valueRange = 0f..24000f,
                    steps = 239,
                    modifier = Modifier.fillMaxWidth(),
                    colors = sliderColors
                )
            }
            
            val filterDescription = when (config.filterType) {
                "low-pass" -> "Recording will collect frequencies from 0 to ${config.higherFilter} Hz"
                "high-pass" -> "Recording will collect frequencies from ${config.lowerFilter} Hz to 24000 Hz"
                "band-pass" -> "Recording will collect frequencies from ${config.lowerFilter} Hz to ${config.higherFilter} Hz"
                else -> ""
            }
            Text(
                text = filterDescription,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TriggerTab(
    config: AudioMothConfig,
    onConfigChange: (AudioMothConfig) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        val triggerTypes = listOf("None", "Amplitude", "Frequency")
        var triggerExpanded by remember { mutableStateOf(false) }
        val currentTriggerType = when {
            config.amplitudeThresholdingEnabled -> "Amplitude"
            config.frequencyTriggerEnabled -> "Frequency"
            else -> "None"
        }

        val thresholdSteps = remember {
            val list = mutableListOf<Double>()
            // 0.001% to 0.01% in steps of 0.001% (ratio 0.00001 to 0.0001)
            for (i in 1..10) list.add(i * 0.00001)
            // 0.01% to 0.1% in steps of 0.01% (ratio 0.0001 to 0.001)
            for (i in 2..10) list.add(i * 0.0001)
            // 0.1% to 1% in steps of 0.1% (ratio 0.001 to 0.01)
            for (i in 2..10) list.add(i * 0.001)
            // 1% to 10% in steps of 1% (ratio 0.01 to 0.1)
            for (i in 2..10) list.add(i * 0.01)
            // 10% to 100% in steps of 10% (ratio 0.1 to 1.0)
            for (i in 2..10) list.add(i * 0.1)
            list
        }

        Text("Trigger Type", style = MaterialTheme.typography.titleSmall)
        ExposedDropdownMenuBox(
            expanded = triggerExpanded,
            onExpandedChange = { triggerExpanded = !triggerExpanded },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            OutlinedTextField(
                value = currentTriggerType,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = triggerExpanded) },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium
            )

            ExposedDropdownMenu(
                expanded = triggerExpanded,
                onDismissRequest = { triggerExpanded = false }
            ) {
                triggerTypes.forEach { type ->
                    DropdownMenuItem(
                        text = { Text(text = type) },
                        onClick = {
                            onConfigChange(config.copy(
                                amplitudeThresholdingEnabled = (type == "Amplitude"),
                                frequencyTriggerEnabled = (type == "Frequency")
                            ))
                            triggerExpanded = false
                        }
                    )
                }
            }
        }

        if (config.amplitudeThresholdingEnabled) {
            Spacer(modifier = Modifier.height(16.dp))
            val durations = listOf(0, 1, 2, 5, 10, 15, 30, 60)
            var durationExpanded by remember { mutableStateOf(false) }
            
            Text("Minimum trigger duration (s)", style = MaterialTheme.typography.titleSmall)
            ExposedDropdownMenuBox(
                expanded = durationExpanded,
                onExpandedChange = { durationExpanded = !durationExpanded },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                OutlinedTextField(
                    value = config.minimumAmplitudeThresholdDuration.toString(),
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = durationExpanded) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium
                )
                ExposedDropdownMenu(
                    expanded = durationExpanded,
                    onDismissRequest = { durationExpanded = false }
                ) {
                    durations.forEach { duration ->
                        DropdownMenuItem(
                            text = { Text(duration.toString()) },
                            onClick = {
                                onConfigChange(config.copy(minimumAmplitudeThresholdDuration = duration))
                                durationExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text("Threshold", style = MaterialTheme.typography.titleSmall)
            
            val currentRatio = config.amplitudeThreshold.coerceIn(0.00001, 1.0)
            val sliderValue = thresholdSteps.indexOfFirst { it >= currentRatio }.coerceAtLeast(0).toFloat()

            Slider(
                value = sliderValue,
                onValueChange = { 
                    val index = it.roundToInt().coerceIn(0, thresholdSteps.size - 1)
                    onConfigChange(config.copy(amplitudeThreshold = thresholdSteps[index])) 
                },
                valueRange = 0f..(thresholdSteps.size - 1).toFloat(),
                steps = thresholdSteps.size - 2,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    activeTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    inactiveTrackColor = MaterialTheme.colorScheme.primary
                )
            )
            
            val formattedThreshold = if (config.amplitudeThreshold >= 0.01) {
                String.format(Locale.getDefault(), "%.1f", config.amplitudeThreshold * 100.0)
            } else {
                String.format(Locale.getDefault(), "%.3f", config.amplitudeThreshold * 100.0)
            }

            Text(
                text = "Amplitude Threshold of $formattedThreshold% will be used when generating T.WAV files",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
        } else if (config.frequencyTriggerEnabled) {
            Spacer(modifier = Modifier.height(16.dp))
            
            // Window length selector
            val windowLengths = listOf(16, 32, 64, 128, 256, 512, 1024)
            var windowExpanded by remember { mutableStateOf(false) }
            Text("Window length (samples)", style = MaterialTheme.typography.titleSmall)
            ExposedDropdownMenuBox(
                expanded = windowExpanded,
                onExpandedChange = { windowExpanded = !windowExpanded },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            ) {
                OutlinedTextField(
                    value = config.frequencyTriggerWindowLength.toString(),
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = windowExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium
                )
                ExposedDropdownMenu(
                    expanded = windowExpanded,
                    onDismissRequest = { windowExpanded = false }
                ) {
                    windowLengths.forEach { length ->
                        DropdownMenuItem(
                            text = { Text(length.toString()) },
                            onClick = {
                                onConfigChange(config.copy(frequencyTriggerWindowLength = length))
                                windowExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Centre frequency slider
            Text("Centre frequency", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("0 kHz", style = MaterialTheme.typography.labelSmall)
                Text("24 kHz", style = MaterialTheme.typography.labelSmall)
            }
            Slider(
                value = config.frequencyTriggerCentreFrequency.toFloat(),
                onValueChange = { onConfigChange(config.copy(frequencyTriggerCentreFrequency = it.toInt())) },
                valueRange = 0f..24000f,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Centre Frequency of ${config.frequencyTriggerCentreFrequency} Hz will be used",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Minimum trigger duration selector
            val durations = listOf(0, 1, 2, 5, 10, 15, 30, 60)
            var durationExpanded by remember { mutableStateOf(false) }
            Text("Minimum trigger duration (s)", style = MaterialTheme.typography.titleSmall)
            ExposedDropdownMenuBox(
                expanded = durationExpanded,
                onExpandedChange = { durationExpanded = !durationExpanded },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            ) {
                OutlinedTextField(
                    value = config.minimumFrequencyTriggerDuration.toString(),
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = durationExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium
                )
                ExposedDropdownMenu(
                    expanded = durationExpanded,
                    onDismissRequest = { durationExpanded = false }
                ) {
                    durations.forEach { duration ->
                        DropdownMenuItem(
                            text = { Text(duration.toString()) },
                            onClick = {
                                onConfigChange(config.copy(minimumFrequencyTriggerDuration = duration))
                                durationExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Frequency Trigger Threshold slider
            Text("Threshold", style = MaterialTheme.typography.titleSmall)
            val currentRatio = config.frequencyTriggerThreshold.coerceIn(0.00001, 1.0)
            val sliderValue = thresholdSteps.indexOfFirst { it >= currentRatio }.coerceAtLeast(0).toFloat()

            Slider(
                value = sliderValue,
                onValueChange = { 
                    val index = it.roundToInt().coerceIn(0, thresholdSteps.size - 1)
                    onConfigChange(config.copy(frequencyTriggerThreshold = thresholdSteps[index])) 
                },
                valueRange = 0f..(thresholdSteps.size - 1).toFloat(),
                steps = thresholdSteps.size - 2,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    activeTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    inactiveTrackColor = MaterialTheme.colorScheme.primary
                )
            )
            
            val formattedThreshold = if (config.frequencyTriggerThreshold >= 0.01) {
                String.format(Locale.getDefault(), "%.1f", config.frequencyTriggerThreshold * 100.0)
            } else {
                String.format(Locale.getDefault(), "%.3f", config.frequencyTriggerThreshold * 100.0)
            }

            Text(
                text = "Frequency Threshold of $formattedThreshold% will be used when generating T.WAV files",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
fun AdvancedTab(
    config: AudioMothConfig,
    onConfigChange: (AudioMothConfig) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        // Group 1
        Text("General", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = config.requireAcousticConfig,
                onCheckedChange = { onConfigChange(config.copy(requireAcousticConfig = it)) }
            )
            Text("Always require acoustic chime on switching to CUSTOM", style = MaterialTheme.typography.bodyMedium)
        }
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = config.dailyFolders,
                onCheckedChange = { onConfigChange(config.copy(dailyFolders = it)) }
            )
            Text("Use daily folder for generated WAV files", style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Group 2
        Text("Hardware Settings", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = config.disable48DCFilter,
                onCheckedChange = { onConfigChange(config.copy(disable48DCFilter = it)) }
            )
            Text("Disable 48Hz DC blocking filter", style = MaterialTheme.typography.bodyMedium)
        }
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = config.energySaverModeEnabled,
                onCheckedChange = { onConfigChange(config.copy(energySaverModeEnabled = it)) }
            )
            Text("Enable energy saver mode", style = MaterialTheme.typography.bodyMedium)
        }
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = config.lowGainRangeEnabled,
                onCheckedChange = { onConfigChange(config.copy(lowGainRangeEnabled = it)) }
            )
            Text("Enable low gain range", style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Group 3
        Text("External Modules", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = config.magneticSwitchEnabled,
                onCheckedChange = { onConfigChange(config.copy(magneticSwitchEnabled = it)) }
            )
            Text("Enable magnetic switch for delayed start", style = MaterialTheme.typography.bodyMedium)
        }
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = config.timeSettingFromGPSEnabled,
                onCheckedChange = { onConfigChange(config.copy(timeSettingFromGPSEnabled = it)) }
            )
            Text("Enable GPS for time setting", style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text("Battery", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = config.minimumBatteryVoltage > 0,
                onCheckedChange = { 
                    onConfigChange(config.copy(
                        minimumBatteryVoltage = if (it) 33 else 0
                    )) 
                }
            )
            Text("Enable Battery Level Check", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

fun tryUpdateConfig(value: String, field: String, config: AudioMothConfig, onConfigChange: (AudioMothConfig) -> Unit) {
    val intValue = value.toIntOrNull() ?: return
    when (field) {
        "recordDuration" -> onConfigChange(config.copy(recordDuration = intValue))
        "sleepDuration" -> onConfigChange(config.copy(sleepDuration = intValue))
        "gain" -> onConfigChange(config.copy(gain = intValue))
    }
}

@Composable
fun ScheduleTimeline(timePeriods: List<TimePeriod>) {
    val labelStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
    val primaryColor = MaterialTheme.colorScheme.primary
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Canvas(modifier = Modifier.fillMaxWidth().height(12.dp)) {
            val width = size.width
            val height = size.height
            
            drawRect(color = Color.LightGray.copy(alpha = 0.3f), size = size)
            
            timePeriods.forEach { period ->
                val startX = (period.startMins.toFloat() / 1440f) * width
                val endX = (period.endMins.toFloat() / 1440f) * width
                drawRect(
                    color = primaryColor,
                    topLeft = Offset(startX, 0f),
                    size = Size(endX - startX, height)
                )
            }

            for (hour in 0..24 step 3) {
                val x = (hour * 60f / 1440f) * width
                drawLine(
                    color = onSurfaceVariant.copy(alpha = 0.3f),
                    start = Offset(x, 0f),
                    end = Offset(x, height),
                    strokeWidth = 1.dp.toPx()
                )
            }
        }
        
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 1.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            for (hour in 0..24 step 6) {
                Text(
                    text = String.format(Locale.getDefault(), "%02d:00", hour),
                    style = labelStyle,
                    color = onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeriodItem(
    period: TimePeriod,
    onDelete: () -> Unit,
    onUpdate: (TimePeriod) -> Unit
) {
    val showTimePicker = remember { mutableStateOf(false) }
    var pickingStart by remember { mutableStateOf(true) }

    if (showTimePicker.value) {
        val state = rememberTimePickerState(
            initialHour = (if (pickingStart) period.startMins else period.endMins) / 60,
            initialMinute = (if (pickingStart) period.startMins else period.endMins) % 60,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { showTimePicker.value = false },
            confirmButton = {
                TextButton(onClick = {
                    val newMins = state.hour * 60 + state.minute
                    if (pickingStart) {
                        onUpdate(period.copy(startMins = newMins.coerceAtMost(period.endMins)))
                    } else {
                        onUpdate(period.copy(endMins = newMins.coerceAtLeast(period.startMins)))
                    }
                    showTimePicker.value = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker.value = false }) { Text("Cancel") }
            },
            text = { TimePicker(state = state) }
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatMins(period.startMins),
                style = MaterialTheme.typography.titleMedium,
                fontSize = 16.sp,
                modifier = Modifier.clickable { 
                    pickingStart = true
                    showTimePicker.value = true 
                }
            )
            Text(" — ", style = MaterialTheme.typography.titleMedium)
            Text(
                text = formatMins(period.endMins),
                style = MaterialTheme.typography.titleMedium,
                fontSize = 16.sp,
                modifier = Modifier.clickable { 
                    pickingStart = false
                    showTimePicker.value = true
                }
            )
            
            Spacer(modifier = Modifier.weight(1f))
            
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Delete, 
                    contentDescription = "Delete Period", 
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

fun formatMins(mins: Int): String {
    val h = (mins / 60) % 24
    val m = mins % 60
    return String.format(Locale.getDefault(), "%02d:%02d", h, m)
}

@Composable
fun ConfigPreviewDialog(config: AudioMothConfig, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Configuration Preview") },
        text = {
            Box(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(config.toJsonString())
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
