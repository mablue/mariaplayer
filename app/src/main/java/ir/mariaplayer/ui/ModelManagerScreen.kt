package ir.mariaplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ir.mariaplayer.R
import ir.mariaplayer.models.ModelCatalog
import ir.mariaplayer.models.ModelDownloader
import ir.mariaplayer.asr.AsrModels
import ir.mariaplayer.models.ModelEntry
import kotlinx.coroutines.launch

private sealed interface ModelUiState {
    data object NotDownloaded : ModelUiState
    data class Downloading(val fraction: Float) : ModelUiState
    data object Downloaded : ModelUiState
    data class Error(val msg: String) : ModelUiState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelManagerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloader = remember { ModelDownloader(context) }

    val catalog by produceState<List<ModelEntry>>(initialValue = emptyList()) {
        value = ModelCatalog.load(context)
    }

    var selectedAsrId by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { selectedAsrId = AsrModels.selected(context) }


    val states = remember { mutableStateMapOf<String, ModelUiState>() }
    LaunchedEffect(catalog) {
        catalog.forEach { m ->
            if (states[m.id] == null) {
                states[m.id] = if (downloader.isEntryDownloaded(m))
                    ModelUiState.Downloaded else ModelUiState.NotDownloaded
            }
        }
    }

    var query by remember { mutableStateOf("") }
    var langFilter by remember { mutableStateOf("") }
    val allLangs = remember(catalog) {
        catalog.flatMap { it.languages }.distinct().sorted()
    }
    val visible = remember(catalog, query, langFilter) {
        catalog.filter { m ->
            (langFilter.isEmpty() || langFilter in m.languages) &&
            (query.isBlank() || m.name.contains(query, ignoreCase = true) ||
             m.engine.contains(query, ignoreCase = true))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Models") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                placeholder = { Text("Search model name or engine") },
            )
            LazyRow(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item {
                    FilterChip(
                        selected = langFilter.isEmpty(),
                        onClick = { langFilter = "" },
                        label = { Text("All") },
                    )
                }
                items(allLangs) { lang ->
                    FilterChip(
                        selected = langFilter == lang,
                        onClick = { langFilter = if (langFilter == lang) "" else lang },
                        label = { Text(lang) },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (catalog.isEmpty()) "Catalog empty — check asset models.json"
                        else "No models match filter",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(visible, key = { it.id }) { m ->
                        val st = states[m.id] ?: ModelUiState.NotDownloaded
                        val isAsr = m.engine == "sherpa-onnx"
                        val isDefaultAsr = isAsr && m.id == selectedAsrId
                        ModelRow(
                            model = m,
                            state = st,
                            isDefaultAsr = isDefaultAsr,
                            canSetDefaultAsr = isAsr && st is ModelUiState.Downloaded,
                            onSetDefaultAsr = {
                                scope.launch {
                                    AsrModels.saveSelected(context, m.id)
                                    selectedAsrId = m.id
                                }
                            },
                            onDownload = {
                                if (st is ModelUiState.Downloading) return@ModelRow
                                scope.launch {
                                    states[m.id] = ModelUiState.Downloading(0f)
                                    try {
                                        downloader.downloadEntry(m) { p ->
                                            states[m.id] = when {
                                                p.error != null -> ModelUiState.Error(p.error)
                                                p.done -> ModelUiState.Downloaded
                                                else -> ModelUiState.Downloading(p.fraction)
                                            }
                                        }
                                    } catch (e: Exception) {
                                        states[m.id] = ModelUiState.Error(e.message ?: "Failed")
                                    }
                                }
                            },
                            onDelete = {
                                downloader.delete(m.id)
                                states[m.id] = ModelUiState.NotDownloaded
                            },
                            onRetry = { states[m.id] = ModelUiState.NotDownloaded },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelRow(
    model: ModelEntry,
    state: ModelUiState,
    isDefaultAsr: Boolean,
    canSetDefaultAsr: Boolean,
    onSetDefaultAsr: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(model.name) },
        supportingContent = {
            Column {
                Text(
                    "${model.langLabel} • ${model.sizeMb} MB • ${model.engine}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Music: ${model.musicRating} • ${model.license}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when (state) {
                    is ModelUiState.Downloading -> {
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { state.fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    is ModelUiState.Error -> {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Error: ${state.msg}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    else -> {}
                }
            }
        },
        trailingContent = {
            when (state) {
                ModelUiState.NotDownloaded -> {
                    Button(onClick = onDownload) { Text("Download") }
                }
                is ModelUiState.Downloading -> {
                    CircularProgressIndicator(Modifier.padding(8.dp))
                }
                ModelUiState.Downloaded -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (canSetDefaultAsr) {
                            if (isDefaultAsr) {
                                Text(
                                    "Default",
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(end = 4.dp),
                                )
                            } else {
                                TextButton(onClick = onSetDefaultAsr) {
                                    Text("Set default")
                                }
                            }
                        }
                        TextButton(onClick = onDelete) { Text("Delete") }
                    }
                }
                is ModelUiState.Error -> {
                    Row {
                        TextButton(onClick = onRetry) { Text("Retry") }
                        TextButton(onClick = onDelete) { Text("Delete") }
                    }
                }
            }
        },
    )
}
