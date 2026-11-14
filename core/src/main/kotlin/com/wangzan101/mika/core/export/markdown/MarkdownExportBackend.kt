package com.wangzan101.mika.core.export.markdown

import com.wangzan101.mika.core.export.AnswerOutcome
import com.wangzan101.mika.core.export.AssetKind
import com.wangzan101.mika.core.export.ExportAsset
import com.wangzan101.mika.core.export.ExportAssetReader
import com.wangzan101.mika.core.export.ExportBackend
import com.wangzan101.mika.core.export.ExportBlock
import com.wangzan101.mika.core.export.ExportCapability
import com.wangzan101.mika.core.export.ExportDescriptor
import com.wangzan101.mika.core.export.ExportDocument
import com.wangzan101.mika.core.export.ExportKind
import com.wangzan101.mika.core.export.ExportLabels
import com.wangzan101.mika.core.export.ExportPhase
import com.wangzan101.mika.core.export.ExportPresentation
import com.wangzan101.mika.core.export.ExportProgress
import com.wangzan101.mika.core.export.ExportSink
import com.wangzan101.mika.core.export.ToolStep
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MarkdownExportBackend(private val labels: ExportLabels = ExportLabels.English) : ExportBackend {
    override val descriptor = ExportDescriptor(
        id = "core-markdown",
        formatId = "markdown",
        extension = "md",
        mime = "text/markdown",
        kind = ExportKind.SingleFile,
        capabilities = setOf(
            ExportCapability.ToolSteps, ExportCapability.Attachments, ExportCapability.Screens,
            ExportCapability.Timestamps, ExportCapability.ModelName, ExportCapability.Streaming,
            ExportCapability.Background,
        ),
    )

    override suspend fun render(
        document: ExportDocument,
        presentation: ExportPresentation,
        assets: ExportAssetReader,
        sink: ExportSink,
        progress: (ExportProgress) -> Unit,
    ) {
        val total = document.blocks.size
        progress(ExportProgress(ExportPhase.Rendering, 0, total))
        sink.open("document.md").bufferedWriter(Charsets.UTF_8).use { out ->
            out.appendLine("# ${MarkdownText.plain(oneLine(document.meta.title))}")
            out.appendLine()
            out.appendLine("*${labels.exportedAt}: ${time(document.meta.exportedAtMs)}*")
            out.appendLine()
            document.blocks.forEachIndexed { index, block ->
                when (block) {
                    is ExportBlock.UserMessage -> user(out, block)
                    is ExportBlock.SteerMessage -> steer(out, block)
                    is ExportBlock.Answer -> answer(out, block)
                }
                out.appendLine()
                progress(ExportProgress(ExportPhase.Rendering, index + 1, total))
            }
            if (document.warnings.isNotEmpty()) {
                out.appendLine("> ${labels.warnings}:")
                document.warnings.forEach {
                    val line = when (it.code) {
                        "run_record_missing" -> labels.runRecordMissing
                        "asset_missing", "asset_unreadable" -> labels.missingAsset.format(it.detail)
                        "asset_too_large", "asset_total_too_large" -> labels.tooLarge.format(it.detail)
                        "timestamp_unknown" -> "${labels.unknown}: ${it.detail}"
                        else -> it.detail
                    }
                    out.appendLine("> - ${MarkdownText.plain(line)}")
                }
            }
        }
    }

    private fun user(out: Appendable, block: ExportBlock.UserMessage) {
        out.appendLine("## ${labels.user}${block.atMs?.let { " · ${time(it)}" }.orEmpty()}")
        block.quote?.let { q ->
            out.appendLine("> **${labels.quote}:**")
            q.text.lines().forEach { out.appendLine("> ${MarkdownText.plain(it)}") }
            out.appendLine()
        }
        block.page?.let { p ->
            val title = MarkdownText.plain(oneLine(p.title.ifBlank { p.url }))
            val destination = MarkdownText.destination(p.url)
            out.appendLine(if (destination != null) "> ${labels.page}: [$title]($destination)" else "> ${labels.page}: $title")
            p.selection?.takeIf { it.isNotBlank() }?.lines()?.forEach { out.appendLine("> | ${MarkdownText.plain(it)}") }
            out.appendLine()
        }
        block.text.takeIf { it.isNotBlank() }?.let { out.appendLine(MarkdownText.plain(it)); out.appendLine() }
        for (screen in block.screens) image(out, screen)
        if (block.attachments.isNotEmpty()) {
            out.appendLine("### ${labels.attachments}")
            block.attachments.forEach { if (it.kind == AssetKind.Image) image(out, it) else file(out, it) }
            out.appendLine()
        }
    }

    private fun steer(out: Appendable, block: ExportBlock.SteerMessage) {
        out.appendLine("### ${labels.steer}")
        block.interruptedText?.takeIf { it.isNotBlank() }?.let { out.appendLine("> ${MarkdownText.plain(it)}"); out.appendLine() }
        block.finishedText?.takeIf { it.isNotBlank() }?.let { out.appendLine("> ${MarkdownText.plain(it)}"); out.appendLine() }
        block.text.takeIf { it.isNotBlank() }?.let { out.appendLine(MarkdownText.plain(it)); out.appendLine() }
        for (asset in block.assets) {
            if (asset.kind == AssetKind.UserScreen || asset.kind == AssetKind.Image) image(out, asset) else file(out, asset)
        }
    }

    private fun answer(out: Appendable, block: ExportBlock.Answer) {
        out.appendLine("## ${labels.mika}${block.atMs?.let { " · ${time(it)}" }.orEmpty()}")
        block.model?.takeIf { it.isNotBlank() }?.let { out.appendLine("*${labels.model}: ${MarkdownText.plain(oneLine(it))}*"); out.appendLine() }
        when (val o = block.outcome) {
            AnswerOutcome.Succeeded -> Unit
            is AnswerOutcome.Unverified -> {
                out.appendLine("> ${labels.unverified.format(MarkdownText.plain(o.note.orEmpty()))}")
                o.unconfirmed.forEach { out.appendLine("> - ${MarkdownText.plain(it)}") }
                out.appendLine()
            }
            is AnswerOutcome.Failed -> {
                out.appendLine("> ${labels.notDone.format(MarkdownText.plain(o.reason))}")
                out.appendLine()
            }
            is AnswerOutcome.Cancelled -> {
                out.appendLine("> ${labels.cancelled}")
                o.summary?.let { out.appendLine("> ${MarkdownText.plain(it)}") }
                out.appendLine()
            }
        }
        block.markdown.takeIf { it.isNotBlank() }?.let { out.appendLine(MarkdownText.safeHtml(it)); out.appendLine() }
        if (block.toolSteps.isNotEmpty()) {
            out.appendLine("### ${labels.toolSteps}")
            block.toolSteps.forEach { step(out, it) }
            out.appendLine()
        }
        if (block.files.isNotEmpty()) {
            out.appendLine("### ${labels.files}")
            block.files.forEach { file(out, it) }
            out.appendLine()
        }
    }

    private fun step(out: Appendable, step: ToolStep) {
        val tool = step.tool?.takeIf { it.isNotBlank() }?.let { " ${MarkdownText.codeSpan(it)}" }.orEmpty()
        val duration = if (step.durationMs > 0) " (${step.durationMs} ms)" else ""
        val error = if (step.isError) " · ${labels.toolError}" else ""
        out.appendLine("**${String.format(Locale.ROOT, labels.step, step.step)}**$tool$duration$error")
        step.note?.takeIf { it.isNotBlank() }?.let { out.appendLine(MarkdownText.plain(it)); out.appendLine() }
        step.argsJson?.takeIf { it.isNotBlank() }?.let { code(out, "json", it) }
        step.result?.takeIf { it.isNotBlank() }?.let { code(out, "text", it) }
    }

    private fun code(out: Appendable, language: String, text: String) {
        val fence = MarkdownText.codeFence(text)
        out.appendLine("$fence$language")
        out.appendLine(text)
        out.appendLine(fence)
        out.appendLine()
    }

    private fun image(out: Appendable, asset: ExportAsset) {
        out.appendLine("![${MarkdownText.plain(oneLine(asset.fileName))}](${MarkdownText.assetPath(asset.fileName)})")
        out.appendLine()
    }

    private fun file(out: Appendable, asset: ExportAsset) {
        out.appendLine("- [${MarkdownText.plain(oneLine(asset.fileName))}](${MarkdownText.assetPath(asset.fileName)})")
    }

    private fun time(ms: Long) = FORMAT.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

    private fun oneLine(text: String) = text.replace('\n', ' ').replace('\r', ' ').trim()

    private companion object {
        val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
    }
}
