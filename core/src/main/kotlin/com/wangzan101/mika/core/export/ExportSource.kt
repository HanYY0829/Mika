package com.wangzan101.mika.core.export

import java.io.InputStream

/** 快照输入:app 侧把对话文件、运行记录和工作区资产映射成这几类记录。 */
interface ExportSource {
    suspend fun turns(conversationId: String): List<ExportTurnRecord>
    suspend fun run(runId: String): ExportRunRecord?
    suspend fun resolve(request: AssetRequest): ExportAsset?
}

data class ExportTurnRecord(
    val index: Int,
    val userText: String,
    val outcome: String,
    val status: String,
    val note: String? = null,
    val unconfirmed: List<String> = emptyList(),
    val runId: String? = null,
    val atMs: Long? = null,
    val steers: List<String> = emptyList(),
    val interruptedReplies: List<String> = emptyList(),
    val finishedReplies: List<String> = emptyList(),
    val files: List<ExportFileRef> = emptyList(),
)

data class ExportRunRecord(
    val model: String,
    val startedAtMs: Long,
    val steps: List<ToolStep>,
)

/** 渲染时按需打开资产字节;core 测试用内存实现。 */
interface ExportAssetReader {
    suspend fun open(asset: ExportAsset): InputStream?
}
