package com.wangzan101.mika.core.export

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ExportManifest(
    @SerialName("format") val format: String = "mika.conversation-export",
    val version: Int = 1,
    val appVersion: String,
    val exportedAt: String,
    val conversation: ManifestConversation,
    val selection: ManifestSelection,
    val content: ManifestContent,
    val counts: ManifestCounts,
    val entries: List<ManifestEntry>,
    val warnings: List<ManifestWarning> = emptyList(),
)

@Serializable data class ManifestConversation(val id: String, val title: String)
@Serializable data class ManifestSelection(val turns: Int, val blocks: Int, val allSelected: Boolean)
@Serializable data class ManifestContent(
    val toolSteps: Boolean,
    val attachments: Boolean,
    val screens: Boolean,
    val timestamps: Boolean,
    val model: Boolean,
    val redaction: String,
)
@Serializable data class ManifestCounts(val blocks: Int, val toolSteps: Int, val assets: Int)
@Serializable data class ManifestEntry(val path: String, val bytes: Long, val sha256: String)
@Serializable data class ManifestWarning(val code: String, val detail: String)

object ExportManifestJson {
    val json = Json { prettyPrint = true; encodeDefaults = true }
    fun encode(manifest: ExportManifest): String = json.encodeToString(manifest)
}
