package com.audiomoth.configeditor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        Text(
            text = "AudioMoth Config Editor",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 32.dp)
        )

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
            .padding(16.dp)
    ) {
        Text(
            text = "Edit Configuration",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        ConfigTabScreen(
            config = editingConfig,
            onConfigChange = { editingConfig = it }
        )

        Spacer(modifier = Modifier.weight(1f))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            OutlinedButton(onClick = onCancel) {
                Text("Cancel")
            }

            OutlinedButton(onClick = { showPreview = true }) {
                Text("Preview")
            }

            Button(onClick = { onSave(editingConfig) }) {
                Text("Save")
            }
        }
    }
}

@Composable
fun ConfigTabScreen(
    config: AudioMothConfig,
    onConfigChange: (AudioMothConfig) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Recording", "Schedule", "Filtering", "Advanced")

    Column {
        ScrollableTabRow(
            selectedTabIndex = selectedTab,
            edgePadding = 0.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) }
                )
            }
        }

        Box(modifier = Modifier.weight(1f).padding(top = 16.dp)) {
            when (selectedTab) {
                0 -> RecordingTab(config, onConfigChange)
                1 -> ScheduleTab(config, onConfigChange)
                2 -> Text("Filtering Settings (Coming Soon)")
                3 -> Text("Advanced Settings (Coming Soon)")
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
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        val sampleRates = listOf(384000, 250000, 192000, 96000)
        var expanded by remember { mutableStateOf(false) }

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            OutlinedTextField(
                value = config.sampleRate.toString(),
                onValueChange = {},
                readOnly = true,
                label = { Text("Sample Rate (Hz)") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth()
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
            label = { Text("Record Duration (seconds)") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        )

        OutlinedTextField(
            value = sleepDurationText,
            onValueChange = { sleepDurationText = it; tryUpdateConfig(it, "sleepDuration", config, onConfigChange) },
            label = { Text("Sleep Duration (seconds)") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        )

        OutlinedTextField(
            value = gainText,
            onValueChange = { gainText = it; tryUpdateConfig(it, "gain", config, onConfigChange) },
            label = { Text("Gain") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        )

        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Checkbox(
                checked = config.ledEnabled,
                onCheckedChange = { onConfigChange(config.copy(ledEnabled = it)) }
            )
            Text("LED Enabled", modifier = Modifier.align(Alignment.CenterVertically))
        }

        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Checkbox(
                checked = config.lowVoltageCutoffEnabled,
                onCheckedChange = { onConfigChange(config.copy(lowVoltageCutoffEnabled = it)) }
            )
            Text("Low Voltage Cutoff", modifier = Modifier.align(Alignment.CenterVertically))
        }

        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Checkbox(
                checked = config.dutyEnabled,
                onCheckedChange = { onConfigChange(config.copy(dutyEnabled = it)) }
            )
            Text("Duty Enabled", modifier = Modifier.align(Alignment.CenterVertically))
        }
    }
}

@Composable
fun ScheduleTab(
    config: AudioMothConfig,
    onConfigChange: (AudioMothConfig) -> Unit
) {
    var selectedPeriodIndex by remember { mutableStateOf<Int?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        ScheduleTimeline(timePeriods = config.timePeriods)

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = {
                val newPeriod = TimePeriod(startMins = 0, endMins = 60)
                onConfigChange(config.copy(timePeriods = (config.timePeriods + newPeriod).sortedBy { it.startMins }))
            }) {
                Icon(Icons.Default.Add, contentDescription = "Add Period")
            }

            IconButton(
                onClick = {
                    selectedPeriodIndex?.let { index ->
                        val newList = config.timePeriods.toMutableList().apply { removeAt(index) }
                        onConfigChange(config.copy(timePeriods = newList))
                        selectedPeriodIndex = null
                    }
                },
                enabled = selectedPeriodIndex != null
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Remove Selected")
            }

            Button(onClick = {
                onConfigChange(config.copy(timePeriods = emptyList()))
                selectedPeriodIndex = null
            }) {
                Text("Clear All")
            }
        }

        Box(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Column {
                config.timePeriods.forEachIndexed { index, period ->
                    PeriodItem(
                        period = period,
                        isSelected = selectedPeriodIndex == index,
                        onSelect = { selectedPeriodIndex = index },
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

@Composable
fun ScheduleTimeline(timePeriods: List<TimePeriod>) {
    val outlineColor = MaterialTheme.colorScheme.outline
    val scheduledColor = Color.Red
    val textColor = MaterialTheme.colorScheme.onSurface

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            val width = size.width
            val height = size.height
            val minutesInDay = 24 * 60f

            // Draw outline
            drawRect(
                color = outlineColor,
                topLeft = Offset(0f, 0f),
                size = Size(width, height),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
            )

            // Draw scheduled periods
            timePeriods.forEach { period ->
                val startX = (period.startMins / minutesInDay) * width
                val endX = (period.endMins / minutesInDay) * width
                drawRect(
                    color = scheduledColor,
                    topLeft = Offset(startX, 0f),
                    size = Size(endX - startX, height)
                )
            }

            // Draw hour markers
            val markers = listOf(6, 12, 18)
            markers.forEach { hour ->
                val x = (hour * 60 / minutesInDay) * width
                drawLine(
                    color = outlineColor,
                    start = Offset(x, 0f),
                    end = Offset(x, height),
                    strokeWidth = 1.dp.toPx()
                )
            }
        }
        
        // Marker labels
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("00:00", style = MaterialTheme.typography.labelSmall)
            Text("06:00", style = MaterialTheme.typography.labelSmall)
            Text("12:00", style = MaterialTheme.typography.labelSmall)
            Text("18:00", style = MaterialTheme.typography.labelSmall)
            Text("24:00", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun PeriodItem(
    period: TimePeriod,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onUpdate: (TimePeriod) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onSelect() },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                TimePickerField(
                    label = "Start Time",
                    minutes = period.startMins,
                    onMinutesChange = { onUpdate(period.copy(startMins = it)) }
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                TimePickerField(
                    label = "End Time",
                    minutes = period.endMins,
                    onMinutesChange = { onUpdate(period.copy(endMins = it)) }
                )
            }
        }
    }
}

@Composable
fun TimePickerField(
    label: String,
    minutes: Int,
    onMinutesChange: (Int) -> Unit
) {
    var hour by remember(minutes) { mutableIntStateOf(minutes / 60) }
    var minute by remember(minutes) { mutableIntStateOf(minutes % 60) }

    Column {
        Text(text = label, style = MaterialTheme.typography.labelSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = hour.toString().padStart(2, '0'),
                onValueChange = {
                    val h = it.toIntOrNull()?.coerceIn(0, 23) ?: hour
                    onMinutesChange(h * 60 + minute)
                },
                modifier = Modifier.width(60.dp),
                singleLine = true
            )
            Text(" : ", style = MaterialTheme.typography.bodyLarge)
            OutlinedTextField(
                value = minute.toString().padStart(2, '0'),
                onValueChange = {
                    val m = it.toIntOrNull()?.coerceIn(0, 59) ?: minute
                    onMinutesChange(hour * 60 + m)
                },
                modifier = Modifier.width(60.dp),
                singleLine = true
            )
        }
    }
}

private fun tryUpdateConfig(
    value: String,
    field: String,
    config: AudioMothConfig,
    onConfigChange: (AudioMothConfig) -> Unit
) {
    value.toIntOrNull()?.let { intValue ->
        val newConfig = when (field) {
            "recordDuration" -> config.copy(recordDuration = intValue)
            "sleepDuration" -> config.copy(sleepDuration = intValue)
            "gain" -> config.copy(gain = intValue)
            else -> config
        }
        onConfigChange(newConfig)
    }
}

@Composable
fun ConfigPreviewDialog(
    config: AudioMothConfig,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Configuration Preview") },
        text = {
            Text(
                text = config.toJsonString(),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            )
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("OK")
            }
        }
    )
}

@Composable
fun AudioMothConfigEditorTheme(content: @Composable () -> Unit) {
    val greenScheme = lightColorScheme(
        primary = Color(0xFF2E7D32),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFA5D6A7),
        onPrimaryContainer = Color(0xFF003300),
        secondary = Color(0xFF388E3C),
        onSecondary = Color.White,
        surface = Color(0xFFF1F8E9),
        onSurface = Color(0xFF1B5E20)
    )

    MaterialTheme(
        colorScheme = greenScheme,
        content = content
    )
}
