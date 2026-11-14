package com.wangzan101.mika.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.sandbox.AndroidSandbox
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.cap.sandbox.SandboxState
import com.wangzan101.mika.core.cap.devicefiles.DeviceFileEntry
import com.wangzan101.mika.settings.DeviceDirectoryStore
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.task.AndroidDeviceFiles
import com.wangzan101.mika.task.DeviceImportStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.catch

@Composable
internal fun DeviceFileBrowser(vm: ChatViewModel, conversationId: String, onClose: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val directories = remember(context) { DeviceDirectoryStore.get(context) }
    val files = remember(context) {
        AndroidDeviceFiles(PhoneServices.of(context).hub, SandboxDirs(context), AndroidSandbox(context, SandboxState.get(context)),
            directories, DeviceImportStore.get(context))
    }
    var path by remember { mutableStateOf<String?>(null) }
    var history by remember { mutableStateOf<List<String?>>(emptyList()) }
    var entries by remember { mutableStateOf<List<DeviceFileEntry>>(emptyList()) }
    var cursor by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var rootDescriptions by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var error by remember { mutableStateOf(false) }
    var request by remember { mutableIntStateOf(0) }
    var append by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showHistory by remember { mutableStateOf(false) }
    val records by remember(context) { DeviceImportStore.get(context).recordsFlow().catch { emit(emptyList()) } }.collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    var activeQuery by remember { mutableStateOf<String?>(null) }
    var truncated by remember { mutableStateOf(false) }
    LaunchedEffect(path, request) {
        loading = true
        error = false
        try {
            val search = activeQuery
            if (search != null && path != null) {
                val result = files.search(com.wangzan101.mika.core.cap.devicefiles.DeviceFileSearchQuery(
                    path!!, search, null, null, null, 5, 500, 3000))
                entries = result.entries
                cursor = null
                truncated = result.truncated
            } else {
                val page = files.list(path, if (append) cursor else null, 128)
                if (path == null) {
                    val authorized = page.entries.map { it.path }.toSet()
                    rootDescriptions = withContext(Dispatchers.IO) {
                        directories.directories().filter { it.path in authorized }.associate { it.path to it.description }
                    }
                }
                entries = if (append) entries + page.entries else page.entries
                cursor = page.nextCursor
                truncated = false
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { error = true }
        loading = false
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.device_browser_title)) },
        text = {
            Column {
                TextButton(onClick = { showHistory = !showHistory }) {
                    Text(stringResource(if (showHistory) R.string.device_browser_show_files else R.string.device_browser_history))
                }
                if (showHistory) {
                    Text(stringResource(R.string.device_browser_history_hint), style = MaterialTheme.typography.bodySmall)
                    LazyColumn(Modifier.heightIn(max = 360.dp).fillMaxWidth()) {
                        items(records.filter { it.conversationId == conversationId }.asReversed(), key = { it.id }) { record ->
                            Column(Modifier.padding(vertical = 8.dp)) {
                                Text(record.sourcePath.substringAfterLast('/'))
                                Text(record.workspacePath, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                } else {
                Text(path ?: stringResource(R.string.device_browser_roots), maxLines = 2)
                if (path != null) {
                    OutlinedTextField(value = query, onValueChange = { query = it.take(255) },
                        label = { Text(stringResource(R.string.device_browser_search)) }, singleLine = true)
                    Row {
                        TextButton(enabled = query.isNotBlank() && !loading, onClick = {
                            loading = true; activeQuery = query.trim(); append = false; request++
                        }) { Text(stringResource(R.string.device_browser_search)) }
                        if (activeQuery != null) TextButton(onClick = {
                            loading = true; activeQuery = null; append = false; request++
                        }) { Text(stringResource(R.string.device_browser_show_files)) }
                    }
                }
                if (truncated) Text(stringResource(R.string.device_browser_truncated), style = MaterialTheme.typography.bodySmall)
                if (history.isNotEmpty()) TextButton(onClick = {
                    loading = true; activeQuery = null; query = ""; path = history.last(); history = history.dropLast(1); append = false; cursor = null
                }) { Text(stringResource(R.string.device_browser_back)) }
                if (error) TextButton(enabled = !loading, onClick = { loading = true; request++ }) { Text(stringResource(R.string.device_browser_retry)) }
                if (!loading && !error && entries.isEmpty()) Text(stringResource(R.string.device_browser_empty))
                LazyColumn(Modifier.heightIn(max = 360.dp).fillMaxWidth()) {
                    items(entries, key = { it.path }) { entry ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = !loading && !error) {
                            if (entry.isDirectory) {
                                loading = true; history = history + listOf(path); activeQuery = null; query = ""; path = entry.path; entries = emptyList(); cursor = null; append = false
                            } else selected = if (entry.path in selected) selected - entry.path else selected + entry.path
                        }.padding(vertical = 8.dp)) {
                            if (!entry.isDirectory) Checkbox(checked = entry.path in selected, enabled = !loading && !error, onCheckedChange = {
                                selected = if (it) selected + entry.path else selected - entry.path
                            })
                            Column(Modifier.weight(1f)) {
                                Text(if (entry.isDirectory) "${entry.name}/" else entry.name)
                                if (path == null) rootDescriptions[entry.path]?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall)
                                }
                                val details = listOfNotNull(entry.size?.takeIf { !entry.isDirectory }?.let { ChatViewModel.sizeLabel(it) },
                                    entry.modifiedAt?.let { java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it)) })
                                if (details.isNotEmpty()) Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (cursor != null && !loading) item {
                        TextButton(onClick = { loading = true; append = true; request++ }) { Text(stringResource(R.string.device_browser_more)) }
                    }
                    if (loading) item { CircularProgressIndicator(Modifier.size(24.dp)) }
                }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = selected.isNotEmpty() && !loading, onClick = {
                if (FeatureFlags.get(context).deviceFiles()) vm.importDeviceAttachments(conversationId, selected.toList(), files::importFile)
                onClose()
            }) { Text(stringResource(R.string.device_browser_add, selected.size)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.device_browser_cancel)) } },
    )
}
