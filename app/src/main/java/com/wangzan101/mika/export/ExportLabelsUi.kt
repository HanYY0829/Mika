package com.wangzan101.mika.export

import android.content.Context
import com.wangzan101.mika.R
import com.wangzan101.mika.core.export.ExportLabels

object ExportLabelsUi {
    fun of(context: Context) = ExportLabels(
        user = context.getString(R.string.export_label_user),
        mika = context.getString(R.string.export_label_mika),
        steer = context.getString(R.string.export_label_steer),
        answer = context.getString(R.string.export_label_answer),
        toolSteps = context.getString(R.string.export_label_tool_steps),
        attachments = context.getString(R.string.export_label_attachments),
        screens = context.getString(R.string.export_label_screens),
        files = context.getString(R.string.export_label_files),
        quote = context.getString(R.string.export_label_quote),
        page = context.getString(R.string.export_label_page),
        exportedAt = context.getString(R.string.export_label_exported),
        model = context.getString(R.string.export_label_model),
        unknown = context.getString(R.string.export_label_unknown),
        warnings = context.getString(R.string.export_label_warnings),
        truncated = context.getString(R.string.export_label_truncated),
        missingAsset = context.getString(R.string.export_label_missing_asset),
        tooLarge = context.getString(R.string.export_label_too_large),
        runRecordMissing = context.getString(R.string.export_label_run_record_missing),
        unverified = context.getString(R.string.export_label_unverified),
        notDone = context.getString(R.string.export_label_not_done),
        cancelled = context.getString(R.string.export_label_cancelled),
        toolError = context.getString(R.string.export_label_tool_error),
        step = context.getString(R.string.export_label_step),
    )
}
