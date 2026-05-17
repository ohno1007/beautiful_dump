package com.beautifuldump

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beautifuldump.data.AppInfo
import com.beautifuldump.dump.DumpResult
import com.beautifuldump.ui.BeautifulDumpTheme
import com.beautifuldump.util.FileManagerIntent

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContent {
                BeautifulDumpTheme {
                    val state by vm.state.collectAsStateWithLifecycle()
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        HomeScreen(
                            state = state,
                            onPickerOpen = vm::openPicker,
                            onPickerClose = vm::closePicker,
                            onSelect = vm::selectApp,
                            onDump = vm::runDump,
                            onMagicChange = vm::setMagic,
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            android.util.Log.e("bd", "setContent failed", t)
            // Fallback to a plain TextView so the app at least opens.
            val tv = android.widget.TextView(this).apply {
                text = buildString {
                    append("Compose 启动失败:\n\n")
                    append(t.stackTraceToString())
                }
                setPadding(40, 80, 40, 40)
                textSize = 12f
            }
            setContentView(android.widget.ScrollView(this).apply { addView(tv) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: UiState,
    onPickerOpen: () -> Unit,
    onPickerClose: () -> Unit,
    onSelect: (AppInfo) -> Unit,
    onDump: () -> Unit,
    onMagicChange: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Beautiful Dump", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppSelectorCard(
                selected = state.selected,
                onClick = onPickerOpen,
            )

            MagicField(state.customMagic, onMagicChange)

            FilledTonalButton(
                onClick = onDump,
                enabled = state.selected != null && !state.running,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                if (state.running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(state.progress ?: "正在 dump…")
                } else {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("一键 Dump", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
            }

            state.error?.let { ErrorCard(it) }
            state.result?.let { ResultCard(it) }

            if (state.log.isNotEmpty()) LogPanel(state.log)
        }
    }

    if (state.pickerOpen) {
        AppPickerSheet(
            apps = state.installedApps,
            onSelect = onSelect,
            onDismiss = onPickerClose,
        )
    }
}

@Composable
private fun AppSelectorCard(selected: AppInfo?, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val icon = selected?.icon
            if (icon != null) {
                val bmp = remember(icon) { icon.toBitmap(96, 96).asImageBitmap() }
                Image(
                    bitmap = bmp,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.Apps, null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    selected?.label ?: "选择应用",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    selected?.packageName ?: "点击从列表中选择目标",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MagicField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text("自定义 metadata magic (可空)") },
        placeholder = { Text("如 deadbeef，留空则用 FAB11BAF") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerSheet(
    apps: List<AppInfo>,
    onSelect: (AppInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(apps, query) {
        if (query.isBlank()) apps
        else apps.filter {
            it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                "选择目标应用",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            SearchBar(query, { query = it })
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(filtered, key = { it.packageName }) { info ->
                    AppListRow(info) { onSelect(info) }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        leadingIcon = { Icon(Icons.Default.Search, null) },
        placeholder = { Text("搜索应用名 / 包名") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun AppListRow(info: AppInfo, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val icon = info.icon
            if (icon != null) {
                val bmp = remember(icon) { icon.toBitmap(80, 80).asImageBitmap() }
                Image(
                    bitmap = bmp,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)),
                )
            } else {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        info.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (info.likelyUnity) {
                        Spacer(Modifier.width(6.dp))
                        AssistChip(
                            onClick = {},
                            label = { Text("Unity", fontSize = 11.sp) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                labelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            ),
                            modifier = Modifier.height(24.dp),
                        )
                    }
                }
                Text(
                    info.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ResultCard(result: DumpResult) {
    val ctx = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Dump 完成 · pid ${result.pid}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                result.outDir,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.height(12.dp))
            for (f in result.files) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(f.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(humanBytes(f.sizeBytes), style = MaterialTheme.typography.bodySmall)
                }
            }
            result.metadataAddr?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    "metadata @ $it",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(
                onClick = { FileManagerIntent.open(ctx, result.outDir) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.FolderOpen, null)
                Spacer(Modifier.width(8.dp))
                Text("打开文件管理器查看")
            }
        }
    }
}

@Composable
private fun ErrorCard(msg: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(8.dp))
            Text(msg, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
private fun LogPanel(lines: List<String>) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .heightIn(max = 220.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            for (line in lines) {
                Text(
                    line,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

private fun humanBytes(n: Long): String {
    if (n < 1024) return "$n B"
    val units = arrayOf("KB", "MB", "GB")
    var v = n.toDouble() / 1024.0
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return "%.1f %s".format(v, units[i])
}
