package com.audiomoth.configeditor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audiomoth.configeditor.ui.theme.AudioMothConfigEditorTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AudioMothConfigEditorTheme {
                MainScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
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
                    onLoadConfig = {
                        openDocumentLauncher.launch(arrayOf("*/*"))
                    },
                    onNewConfig = {
                        selectedConfig = AudioMothConfig()
                        currentScreen = Screen.EDIT
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

enum class Screen {
    HOME, EDIT
}

@Composable
fun HomeScreen(
    onLoadConfig: () -> Unit,
    onNewConfig: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Button(
            onClick = onNewConfig,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("Create New Configuration")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onLoadConfig,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("Load Configuration from File")
        }
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
    var showPreview by remember { mutableStateOf(false) }

    if (showPreview) {
        ConfigPreviewDialog(
            config = editingConfig,
            onDismiss = { showPreview = false }
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
                onClick = { showPreview = true },
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
    val tabs = listOf("Recording", "Schedule", "Filtering", "Advanced")

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
                3 -> AdvancedTab(config, onConfigChange)
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
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = config.passFiltersEnabled,
                onCheckedChange = { onConfigChange(config.copy(passFiltersEnabled = it)) }
            )
            Text("Enable Pass Filters", style = MaterialTheme.typography.bodyMedium)
        }

        if (config.passFiltersEnabled) {
            val filterTypes = listOf("none", "low-pass", "high-pass", "band-pass")
            var expanded by remember { mutableStateOf(false) }

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                OutlinedTextField(
                    value = config.filterType,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Filter Type") },
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
                    filterTypes.forEach { type ->
                        DropdownMenuItem(
                            text = { Text(text = type) },
                            onClick = {
                                onConfigChange(config.copy(filterType = type))
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AdvancedTab(
    config: AudioMothConfig,
    onConfigChange: (AudioMothConfig) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = config.batteryLevelCheckEnabled,
                onCheckedChange = { onConfigChange(config.copy(batteryLevelCheckEnabled = it)) }
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
    var showTimePicker by remember { mutableStateOf(false) }
    var pickingStart by remember { mutableStateOf(true) }

    if (showTimePicker) {
        val state = rememberTimePickerState(
            initialHour = (if (pickingStart) period.startMins else period.endMins) / 60,
            initialMinute = (if (pickingStart) period.startMins else period.endMins) % 60,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val newMins = state.hour * 60 + state.minute
                    if (pickingStart) {
                        onUpdate(period.copy(startMins = newMins.coerceAtMost(period.endMins)))
                    } else {
                        onUpdate(period.copy(endMins = newMins.coerceAtLeast(period.startMins)))
                    }
                    showTimePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
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
                    showTimePicker = true 
                }
            )
            Text(" — ", style = MaterialTheme.typography.titleMedium)
            Text(
                text = formatMins(period.endMins),
                style = MaterialTheme.typography.titleMedium,
                fontSize = 16.sp,
                modifier = Modifier.clickable { 
                    pickingStart = false
                    showTimePicker = true 
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
