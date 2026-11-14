package com.wangzan101.mika.core.export

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

enum class ExportPhase { Preparing, Rendering, Packaging, Delivering }

data class ExportProgress(
    val phase: ExportPhase,
    val done: Int,
    val total: Int,
    val bytesWritten: Long = 0,
)

/** 后端往暂存目录里写条目;路径由 [ExportNames.entryPath] 校验。 */
interface ExportSink {
    fun open(path: String): OutputStream
}

class DirectoryExportSink(private val root: File) : ExportSink {
    override fun open(path: String): OutputStream {
        val safe = ExportNames.entryPath(path)
        val file = File(root, safe)
        file.parentFile?.mkdirs()
        return FileOutputStream(file)
    }
}

interface ExportBackend {
    val descriptor: ExportDescriptor

    suspend fun render(
        document: ExportDocument,
        presentation: ExportPresentation,
        assets: ExportAssetReader,
        sink: ExportSink,
        progress: (ExportProgress) -> Unit,
    )
}

/** 用户按格式选,运行时按后端跑;同一 formatId 可以有多个引擎。 */
class ExportRegistry(
    private val formats: List<ExportFormat>,
    private val backends: List<ExportBackend>,
) {
    init {
        require(formats.map { it.id }.toSet().size == formats.size) { "duplicate format id" }
        for (format in formats) {
            require(backends.any { it.descriptor.formatId == format.id }) { "no backend for ${format.id}" }
        }
    }

    fun formats(): List<ExportFormat> = formats

    fun format(id: String): ExportFormat? = formats.firstOrNull { it.id == id }

    fun defaultBackend(formatId: String): ExportBackend =
        backends.firstOrNull { it.descriptor.formatId == formatId } ?: error("no backend for $formatId")

    fun descriptors(): List<ExportDescriptor> = backends.map { it.descriptor }
}
