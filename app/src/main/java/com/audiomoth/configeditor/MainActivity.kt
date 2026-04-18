package com.audiomoth.configeditor

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audiomoth.configeditor.ui.theme.AudioMothConfigEditorTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

data class AudioMothDeviceInfo(
    val deviceId: String = "Unknown",
    val firmwareVersion: String = "Unknown",
    val batteryState: String = "Unknown",
    val deviceTime: String = "Unknown"
)

class MainActivity : ComponentActivity() {
    private val ACTION_USB_PERMISSION = "com.audiomoth.configeditor.USB_PERMISSION"
    private var onPermissionResult: ((Boolean, UsbDevice?) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.d("USB", "App Started - Scanning devices")
        val initialDevice = findAudioMothDevice(this)
        if (initialDevice != null) {
            Log.d("USB", "Found device on start, requesting permission")
            requestUsbPermission(initialDevice)
        }

        setContent {
            AudioMothConfigEditorTheme {
                var deviceInfo by remember { mutableStateOf<AudioMothDeviceInfo?>(null) }
                
                // Set up the listener for initial permission request
                onPermissionResult = { granted, usbDevice ->
                    if (granted && usbDevice != null) {
                        deviceInfo = readDeviceInfo(usbDevice)
                    }
                }

                MainScreen(
                    deviceInfo = deviceInfo,
                    onRequestUsbPermission = { device ->
                        onPermissionResult = { granted, usbDevice ->
                            if (granted && usbDevice != null) {
                                deviceInfo = readDeviceInfo(usbDevice)
                            } else {
                                deviceInfo = null
                            }
                        }
                        requestUsbPermission(device)
                    }
                )
            }
        }
    }

    private fun readDeviceInfo(device: UsbDevice): AudioMothDeviceInfo {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        Log.d("USB", "Opening device: VID=${"0x%04X".format(device.vendorId)} PID=${"0x%04X".format(device.productId)}")
        
        val connection = usbManager.openDevice(device) ?: return AudioMothDeviceInfo(deviceId = "Failed to open")
        
        return try {
            // Log all interfaces for debugging
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                Log.d("USB", "Interface $i: Class=${intf.interfaceClass} Subclass=${intf.interfaceSubclass} Protocol=${intf.interfaceProtocol}")
            }

            // Find the HID interface explicitly
            val usbInterface = (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .find { it.interfaceClass == UsbConstants.USB_CLASS_HID || it.id == 0 }
                ?: return AudioMothDeviceInfo(deviceId = "No suitable interface")

            val interfaceId = usbInterface.id
            Log.d("USB", "Claiming HID Interface ID: $interfaceId")
            
            // Try to set configuration (often required on Android for some HID devices)
            try {
                if (device.configurationCount > 0) {
                    connection.setConfiguration(device.getConfiguration(0))
                }
            } catch (e: Exception) {
                Log.w("USB", "Failed to set configuration: ${e.message}")
            }

            if (!connection.claimInterface(usbInterface, true)) {
                return AudioMothDeviceInfo(deviceId = "Failed to claim interface")
            }

            val packetSize = 64
            var lastError: String? = null

            fun getReport(commandByte: Byte, commandName: String): ByteArray {
                val response = ByteArray(packetSize)
                
                // Expanded search: Try Report IDs 0, 1, and commandByte
                // Try Report Types 0x03 (Feature) and 0x01 (Input)
                val reportIds = listOf(0x00, 0x01, commandByte.toInt() and 0xFF)
                val reportTypes = listOf(0x03, 0x01)
                
                // Try different Request Types: 0xA1 (Interface), 0xC1 (Endpoint/Other)
                val requestTypes = listOf(0xA1, 0xC1)

                for (reqType in requestTypes) {
                    for (type in reportTypes) {
                        for (id in reportIds.distinct()) {
                            val wValue = (type shl 8) or id
                            
                            // Try both 64-byte and smaller 13-byte requests
                            for (len in listOf(64, 13)) {
                                Log.d("USB", "Trying Direct GET $commandName: reqType=0x${"%02X".format(reqType)}, type=$type, id=$id, len=$len")
                                val received = connection.controlTransfer(reqType, 0x01, wValue, interfaceId, response, len, 500)
                                if (received >= 0) {
                                    Log.d("USB", "Success: Direct GET $commandName (Req 0x${"%02X".format(reqType)}, Type $type, ID $id, Len $len, Received $received)")
                                    val result = if (id == commandByte.toInt() && commandByte != 0.toByte()) {
                                        ByteArray(packetSize).apply { 
                                            this[0] = response[0]
                                            this[1] = commandByte
                                            System.arraycopy(response, 1, this, 2, Math.min(len - 1, packetSize - 2))
                                        }
                                    } else response
                                    return result
                                }
                            }

                            // 2. Try Two-Step (Command in packet)
                            val packet = ByteArray(packetSize)
                            packet[0] = id.toByte()
                            packet[1] = commandByte
                            Log.d("USB", "Trying Two-Step SET $commandName: type=$type, id=$id")
                            val sent = connection.controlTransfer(0x21, 0x09, wValue, interfaceId, packet, 64, 500)
                            if (sent >= 0) {
                                Thread.sleep(50)
                                Log.d("USB", "Trying Two-Step GET $commandName: reqType=0x${"%02X".format(reqType)}, type=$type, id=$id")
                                val received = connection.controlTransfer(reqType, 0x01, wValue, interfaceId, response, 64, 500)
                                if (received >= 0) {
                                    Log.d("USB", "Success: Two-Step $commandName (Req 0x${"%02X".format(reqType)}, Type $type, ID $id, Received $received)")
                                    return response
                                }
                            }
                        }
                    }
                }

                lastError = "Err: $commandName (Stall)"
                return response
            }

            val timeResponse = getReport(0x01, "GET_TIME")
            lastError?.let { return AudioMothDeviceInfo(deviceId = it) }

            val uidResponse = getReport(0x03, "GET_UID")
            lastError?.let { return AudioMothDeviceInfo(deviceId = it) }

            val versionResponse = getReport(0x07, "GET_VERSION")
            lastError?.let { return AudioMothDeviceInfo(deviceId = it) }

            val batteryResponse = getReport(0x04, "GET_BATTERY")
            lastError?.let { return AudioMothDeviceInfo(deviceId = it) }

            connection.releaseInterface(usbInterface)

            // AudioMoth responses: Index 0 = Report ID (0), Index 1 = Command, Index 2+ = Data
            
            // Parse Time (bytes 2-5)
            val timestamp = if (timeResponse[1] == 0x01.toByte()) {
                (timeResponse[2].toLong() and 0xFF) or
                ((timeResponse[3].toLong() and 0xFF) shl 8) or
                ((timeResponse[4].toLong() and 0xFF) shl 16) or
                ((timeResponse[5].toLong() and 0xFF) shl 24)
            } else 0L
            
            val deviceTime = if (timestamp == 0L) "Not Set" else {
                SimpleDateFormat("HH:mm:ss dd/MM/yyyy", Locale.getDefault()).format(Date(timestamp * 1000))
            }

            // Parse UID (bytes 2-9)
            val uid = if (uidResponse[1] == 0x03.toByte()) {
                uidResponse.sliceArray(2..9).reversedArray().joinToString("") { "%02X".format(it) }
            } else ""

            // Parse Version (Bytes 2, 3, 4 = Major, Minor, Patch)
            val version = if (versionResponse[1] == 0x07.toByte()) {
                "${versionResponse[2]}.${versionResponse[3]}.${versionResponse[4]}"
            } else "Unknown"

            val batteryState = if (batteryResponse[1] == 0x04.toByte()) {
                val state = batteryResponse[2].toInt() and 0xFF
                when (state) {
                    0 -> "< 3.3V"
                    1 -> "3.3V - 3.5V"
                    2 -> "3.5V - 3.7V"
                    3 -> "3.7V - 3.9V"
                    4 -> "> 3.9V"
                    else -> "Unknown ($state)"
                }
            } else "Unknown"

            AudioMothDeviceInfo(
                deviceId = if (uid.isEmpty()) "VID:${"0x%04X".format(device.vendorId)} PID:${"0x%04X".format(device.productId)}" else uid,
                firmwareVersion = version,
                batteryState = batteryState,
                deviceTime = deviceTime
            )
        } catch (e: Exception) {
            Log.e("USB", "Error reading info", e)
            AudioMothDeviceInfo(deviceId = "Error: ${e.message}")
        } finally {
            connection.close()
        }
    }

    private fun requestUsbPermission(device: UsbDevice) {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
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
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
        usbManager.requestPermission(device, permissionIntent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    deviceInfo: AudioMothDeviceInfo?,
    onRequestUsbPermission: (UsbDevice) -> Unit
) {
    var currentScreen by remember { mutableStateOf(Screen.HOME) }
    var selectedConfig by remember { mutableStateOf<AudioMothConfig?>(null) }
    val context = LocalContext.current

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
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                            contentDescription = "App Icon",
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("AudioMoth Config Editor")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
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
                    }
                )
                Screen.EDIT -> EditScreen(
                    config = selectedConfig ?: AudioMothConfig(),
                    onSave = { config ->
                        selectedConfig = config
                        val sdf = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())
                        val fileName = "audiomoth${sdf.format(Date())}.config"
                        createDocumentLauncher.launch(fileName)
                    },
                    onCancel = {
                        currentScreen = Screen.HOME
                    }
                )
            }
        }
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

        var isHid = device.deviceClass == UsbConstants.USB_CLASS_HID
        if (!isHid) {
            for (i in 0 until device.interfaceCount) {
                if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_HID) {
                    isHid = true
                    break
                }
            }
        }
        
        // Match specific AudioMoth VIDs or any HID if no specific match
        val isAudioMoth = (vid == 0x10C4 && pid == 0x0001) || (vid == 0x10C4 && pid == 0x0002) || (vid == 0x04D8 && pid == 0xEF98)
        
        if (isAudioMoth) {
            Log.d("USB", "Found AudioMoth matched by VID/PID")
        } else if (isHid) {
            Log.d("USB", "Found generic HID device: VID=${"0x%04X".format(vid)} PID=${"0x%04X".format(pid)}")
        }
        
        isAudioMoth || isHid
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
    onTestUsb: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
    ) {
        if (deviceInfo != null) {
            DeviceDiagnosticCard(deviceInfo)
            Spacer(modifier = Modifier.height(16.dp))
        }

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
    }
}

@Composable
fun DeviceDiagnosticCard(info: AudioMothDeviceInfo) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Device Diagnostics",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            
            Divider(color = MaterialTheme.colorScheme.outlineVariant)
            
            DiagnosticRow("Unique ID", info.deviceId)
            DiagnosticRow("Firmware", info.firmwareVersion)
            DiagnosticRow("Battery", info.batteryState)
            DiagnosticRow("Device Time", info.deviceTime)
        }
    }
}

@Composable
fun DiagnosticRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun CurrentTimeDisplay() {
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
            Text(
                text = timeFormat.format(date),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
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
    onSave: (AudioMothConfig) -> Unit,
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
        CurrentTimeDisplay()

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
            Button(
                onClick = { 
                    val packet = AcousticConfigBuilder.buildPacket(editingConfig)
                    ChimeGenerator.play(packet)
                },
                modifier = Modifier
                    .weight(1.2f)
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary
                ),
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                Icon(Icons.Default.SettingsInputAntenna, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Configure", style = MaterialTheme.typography.labelLarge, maxLines = 1)
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
