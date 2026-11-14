package com.wangzan101.mika.core.export

/**
 * 导出文档里给人看的文字。core 取不到 Android 资源,由 app 用当前语言构造后传进来;
 * JSON 后端尽量不用这些标签,保持机器可读的稳定英文键。
 */
data class ExportLabels(
    val user: String,
    val mika: String,
    val steer: String,
    val answer: String,
    val toolSteps: String,
    val attachments: String,
    val screens: String,
    val files: String,
    val quote: String,
    val page: String,
    val exportedAt: String,
    val model: String,
    val unknown: String,
    val warnings: String,
    val truncated: String,
    val missingAsset: String,
    val tooLarge: String,
    val runRecordMissing: String,
    val unverified: String,
    val notDone: String,
    val cancelled: String,
    val toolError: String,
    val step: String,
) {
    companion object {
        /** 测试和没有本地化时的英文兜底。 */
        val English = ExportLabels(
            user = "User", mika = "Mika", steer = "Message during the task", answer = "Answer",
            toolSteps = "Tool steps", attachments = "Attachments", screens = "Screenshots", files = "Files",
            quote = "Quoted", page = "From page", exportedAt = "Exported", model = "Model", unknown = "Unknown",
            warnings = "Warnings", truncated = "truncated, original length %d", missingAsset = "Missing: %s", tooLarge = "Too large: %s", runRecordMissing = "Run record no longer available",
            unverified = "Not verified: %s", notDone = "Not done: %s", cancelled = "Cancelled", toolError = "Error",
            step = "Step %d",
        )
    }
}
