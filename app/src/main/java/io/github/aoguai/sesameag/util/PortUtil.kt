package io.github.aoguai.sesameag.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import io.github.aoguai.sesameag.data.Config
import io.github.aoguai.sesameag.util.settingsTransfer.ResolvedSettingsImport
import io.github.aoguai.sesameag.util.settingsTransfer.SettingsTransferApplyResult
import io.github.aoguai.sesameag.util.settingsTransfer.SettingsTransferApplier
import io.github.aoguai.sesameag.util.settingsTransfer.SettingsTransferExportMode
import io.github.aoguai.sesameag.util.settingsTransfer.SettingsTransferExporter
import io.github.aoguai.sesameag.util.maps.CooperateMap
import io.github.aoguai.sesameag.util.maps.IdMapManager
import io.github.aoguai.sesameag.util.maps.UserMap
/**
 * Utility class for handling import and export operations.
 */
object PortUtil {

    @JvmStatic
    fun handleExport(
        context: Context,
        uri: Uri?,
        userId: String?,
        exportMode: SettingsTransferExportMode
    ): Boolean {
        if (uri == null) {
            ToastUtil.makeText("未选择保存位置", Toast.LENGTH_SHORT).show()
            return false
        }
        try {
            val outputStream = context.contentResolver.openOutputStream(uri)
            if (outputStream == null) {
                ToastUtil.makeText("导出失败：无法写入所选位置", Toast.LENGTH_SHORT).show()
                return false
            }

            outputStream.use { output ->
                val json = SettingsTransferExporter.exportJson(userId, exportMode)
                output.write(json.toByteArray(Charsets.UTF_8))
                output.flush()
                ToastUtil.makeText("导出成功", Toast.LENGTH_SHORT).show()
                return true
            }
        } catch (e: Throwable) {
            Log.printStackTrace(e)
            ToastUtil.makeText("导出失败，请稍后重试", Toast.LENGTH_SHORT).show()
            return false
        }
        return false
    }

    @JvmStatic
    fun readImportText(context: Context, uri: Uri?): String? {
        if (uri == null) {
            ToastUtil.showToast(context, "导入失败：未选择文件")
            return null
        }
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                ToastUtil.showToast(context, "导入失败：无法读取所选文件")
                return null
            }
            inputStream.use { input ->
                return input.readBytes().toString(Charsets.UTF_8)
            }
        } catch (e: Throwable) {
            Log.printStackTrace(e)
            ToastUtil.showToast(context, "导入失败，请检查文件后重试")
            return null
        }
        return null
    }

    @JvmStatic
    fun applyImport(context: Context, userId: String?, resolvedImport: ResolvedSettingsImport): SettingsTransferApplyResult {
        val result = SettingsTransferApplier.apply(userId, resolvedImport)
        if (!result.success) {
            return result
        }
        notifyConfigReload(context, userId)
        return result
    }

    @JvmStatic
    fun notifyConfigReload(context: Context, userId: String?) {
        if (userId.isNullOrEmpty()) {
            return
        }
        try {
            val intent = Intent("com.eg.android.AlipayGphone.sesame.restart")
            intent.putExtra("userId", userId)
            intent.putExtra("configReload", true)
            context.sendBroadcast(intent)
        } catch (th: Throwable) {
            Log.printStackTrace(th)
        }
    }

    @JvmStatic
    fun restartActivity(context: Context) {
        val activity = context as? Activity ?: return
        val intent = activity.intent
        activity.finish()
        activity.startActivity(intent)
    }

    @JvmStatic
    fun save(context: Context, userId: String?) {
        try {
            if (Config.isModify(userId) && Config.save(userId, false)) {
                ToastUtil.showToastWithDelay("保存成功！", 100)
                notifyConfigReload(context, userId)
            }
            if (!userId.isNullOrEmpty()) {
                UserMap.save(userId)
                IdMapManager.getInstance(CooperateMap::class.java).save(userId)
            }
        } catch (th: Throwable) {
            Log.printStackTrace(th)
        }
    }
}

