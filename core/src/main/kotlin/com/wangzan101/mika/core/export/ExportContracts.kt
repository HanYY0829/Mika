package com.wangzan101.mika.core.export

/**
 * 一次导出的选择:对话编号、勾了哪些消息块、内容选项。
 * [allSelected] 为真时 [blocks] 不参与判断(默认全选);空集且 [allSelected] 为假表示什么也不要。
 */
data class ExportSelection(
    val conversationId: String,
    val blocks: Set<ExportBlockId> = emptySet(),
    val allSelected: Boolean = true,
)

@JvmInline
value class ExportBlockId(val value: String)

/** 消息块的稳定编号;对话是追加写的,轮次和插话位置在存下来以后都不再变。 */
object ExportIds {
    fun user(turn: Int) = ExportBlockId("u$turn")
    fun steer(turn: Int, index: Int) = ExportBlockId("m$turn-$index")
    fun answer(turn: Int) = ExportBlockId("r$turn")
    fun files(turn: Int) = ExportBlockId("f$turn")
}

enum class ExportKind { SingleFile, ZipBundle }

enum class ExportCapability {
    ToolSteps, Attachments, Screens, Timestamps, ModelName,
    Preview, Share, Paging, Streaming, Background, RequiresActivity,
}

/** 用户能看到的格式;同一个 [id] 可以注册多个后端引擎。 */
data class ExportFormat(
    val id: String,
    val nameKey: String,
    val mime: String,
    val extension: String,
    val defaultKind: ExportKind = ExportKind.SingleFile,
)

data class ExportDescriptor(
    val id: String,
    val formatId: String,
    val extension: String,
    val mime: String,
    val kind: ExportKind,
    val capabilities: Set<ExportCapability> = emptySet(),
    val presentationKeys: Set<String> = emptySet(),
    val maxBlocksPerVolume: Int? = null,
)

data class ExportContent(
    val includeToolSteps: Boolean = false,
    val includeAttachments: Boolean = true,
    val includeScreens: Boolean = true,
    val includeTimestamps: Boolean = false,
    val includeModel: Boolean = false,
    val includeSystemPrompt: Boolean = false,
    val redaction: RedactionLevel = RedactionLevel.Secrets,
)

data class ExportPresentation(
    val theme: ExportTheme = ExportTheme.Auto,
    val pageSize: PageSize = PageSize.A4,
    val imageQuality: Int = 85,
)

enum class ExportTheme { Auto, Light, Dark }
enum class PageSize { A4, Letter }
enum class RedactionLevel { Secrets, SecretsAndPii }

data class ExportWarning(val code: String, val detail: String)

enum class AssetKind { Image, File, UserScreen }

/** 平台无关的资产来源;由 app 侧的 [ExportAssetReader] 打开。 */
sealed interface AssetRequest {
    data class Workspace(val path: String) : AssetRequest
    data class UserScreen(val screenId: String) : AssetRequest
    data class ContentUri(val uri: String) : AssetRequest
}

/** 只带元数据,不把字节放进 AST。 */
data class ExportAsset(
    val id: String,
    val kind: AssetKind,
    val fileName: String,
    val mime: String,
    val sizeBytes: Long,
    val request: AssetRequest,
)

data class QuotedText(val from: QuoteSource, val text: String)

enum class QuoteSource { User, Mika }

data class PageRef(val url: String, val title: String, val selection: String?)

data class ExportFileRef(
    val name: String,
    val sizeBytes: Long,
    val request: AssetRequest,
)

sealed interface AnswerOutcome {
    data object Succeeded : AnswerOutcome
    data class Unverified(val note: String?, val unconfirmed: List<String>) : AnswerOutcome
    data class Failed(val reason: String) : AnswerOutcome
    data class Cancelled(val summary: String?) : AnswerOutcome
}

data class ToolStep(
    val step: Int,
    val note: String?,
    val tool: String?,
    val argsJson: String?,
    val result: String?,
    val isError: Boolean,
    val durationMs: Long,
    val model: String?,
)

sealed interface ExportBlock {
    val id: ExportBlockId

    data class UserMessage(
        override val id: ExportBlockId,
        val text: String,
        val quote: QuotedText?,
        val page: PageRef?,
        val attachments: List<ExportAsset>,
        val screens: List<ExportAsset>,
        val atMs: Long?,
    ) : ExportBlock

    data class SteerMessage(
        override val id: ExportBlockId,
        val text: String,
        val interruptedText: String?,
        val finishedText: String?,
        val assets: List<ExportAsset> = emptyList(),
    ) : ExportBlock

    data class Answer(
        override val id: ExportBlockId,
        val markdown: String,
        val outcome: AnswerOutcome,
        val atMs: Long?,
        val model: String?,
        val toolSteps: List<ToolStep>,
        val files: List<ExportAsset>,
    ) : ExportBlock
}

data class ExportMeta(
    val conversationId: String,
    val title: String,
    val exportedAtMs: Long,
    val appVersion: String,
    val language: String,
)

data class ExportDocument(
    val meta: ExportMeta,
    val blocks: List<ExportBlock>,
    val assets: List<ExportAsset>,
    val warnings: List<ExportWarning> = emptyList(),
)
