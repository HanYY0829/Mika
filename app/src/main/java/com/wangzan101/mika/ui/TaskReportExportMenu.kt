package com.wangzan101.mika.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

@Composable
internal fun ReportExportMenu(runId: String, report: TaskReport?) {
    val context = LocalContext.current
    val scope = LocalLifecycleOwner.current.lifecycleScope
    var open by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    fun save(uri: Uri?, format: TaskReportExport.Format) {
        if (uri == null || report == null) return
        saving = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    (context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("No destination")).use {
                        TaskReportExport.write(report, format, it)
                    }
                    true
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { false }
            }
            saving = false
            Toast.makeText(context, if (ok) R.string.report_saved else R.string.report_save_failed, Toast.LENGTH_LONG).show()
        }
    }
    val md = rememberLauncherForActivityResult(CreateDocument(TaskReportExport.Format.Markdown.mime)) { save(it, TaskReportExport.Format.Markdown) }
    val word = rememberLauncherForActivityResult(CreateDocument(TaskReportExport.Format.Word.mime)) { save(it, TaskReportExport.Format.Word) }
    val txt = rememberLauncherForActivityResult(CreateDocument(TaskReportExport.Format.Text.mime)) { save(it, TaskReportExport.Format.Text) }
    Box {
        TextButton({ open = true }, enabled = report != null && !saving) { Text(stringResource(R.string.report_export), style = MikaType.Caption) }
        DropdownMenu(open, { open = false }) {
            listOf("Markdown (.md)", "Word (.docx)", "TXT (.txt)").forEachIndexed { index, label ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    open = false
                    val format = TaskReportExport.Format.entries[index]
                    val name = "Mika-${runId}.${format.extension}"
                    when (index) { 0 -> md.launch(name); 1 -> word.launch(name); else -> txt.launch(name) }
                })
            }
        }
    }
}
