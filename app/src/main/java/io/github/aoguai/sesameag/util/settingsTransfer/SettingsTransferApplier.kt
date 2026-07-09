package io.github.aoguai.sesameag.util.settingsTransfer

import io.github.aoguai.sesameag.data.Config
import io.github.aoguai.sesameag.entity.friend.FriendCenterConfig
import io.github.aoguai.sesameag.model.ConfigPortScope
import io.github.aoguai.sesameag.model.Model
import io.github.aoguai.sesameag.util.Files
import io.github.aoguai.sesameag.util.JsonUtil
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.friend.FriendRepository
import java.io.File

object SettingsTransferApplier {
    private const val TAG = "SettingsTransferApplier"

    private data class FileBackup(
        val file: File,
        val existed: Boolean,
        val content: String
    )

    fun apply(userId: String?, resolvedImport: ResolvedSettingsImport): SettingsTransferApplyResult {
        val safeUserId = userId?.trim().orEmpty()
        val beforeGeneric = SettingsTransferExporter.snapshotCurrentConfig(ConfigPortScope.GENERIC)
        val beforePrivate = SettingsTransferExporter.snapshotCurrentConfig(ConfigPortScope.ACCOUNT_PRIVATE)
        val fileBackups = captureFileBackups(safeUserId, resolvedImport)

        return try {
            applyScopeSnapshot(ConfigPortScope.GENERIC, resolvedImport.genericConfig, resetScope = true)
            if (resolvedImport.canApplyPrivate) {
                applyScopeSnapshot(
                    ConfigPortScope.ACCOUNT_PRIVATE,
                    resolvedImport.privateConfig ?: ConfigTransferSnapshot(),
                    resetScope = true
                )
            }
            if (safeUserId.isNotEmpty() && resolvedImport.canApplyPrivate) {
                resolvedImport.friendCenter?.let {
                    require(FriendRepository.save(safeUserId, copyFriendCenter(it))) { "保存好友中心失败" }
                }
            }
            Config.sanitizeFriendSelectionFieldsForUser(userId)
            require(Config.save(userId, true)) { "保存配置失败" }

            if (safeUserId.isNotEmpty()) {
                resolvedImport.customSettings?.let { require(writeCustomSettings(safeUserId, it)) { "保存自定义设置失败" } }
                if (resolvedImport.format == SettingsTransferSourceFormat.PACKAGE_V1) {
                    require(
                        SettingsTransferCacheRegistry.writeCaches(
                            safeUserId,
                            resolvedImport.genericCaches,
                            ConfigPortScope.GENERIC
                        )
                    ) { "写入通用缓存失败" }
                    if (resolvedImport.canApplyPrivate) {
                        require(
                            SettingsTransferCacheRegistry.writeCaches(
                                safeUserId,
                                resolvedImport.privateCaches,
                                ConfigPortScope.ACCOUNT_PRIVATE
                            )
                        ) { "写入账号私有缓存失败" }
                    }
                }
            }

            SettingsTransferApplyResult(true, buildSuccessMessage())
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "apply import failed", t)
            restoreFiles(fileBackups)
            if (safeUserId.isNotEmpty()) {
                runCatching { FriendRepository.load(safeUserId) }.onFailure {
                    Log.printStackTrace(TAG, "reload friend center after rollback failed", it)
                }
            }
            restoreConfig(userId, beforeGeneric, beforePrivate)
            SettingsTransferApplyResult(false, buildFailureMessage(t))
        }
    }

    private fun applyScopeSnapshot(
        scope: ConfigPortScope,
        snapshot: ConfigTransferSnapshot,
        resetScope: Boolean
    ) {
        if (resetScope) {
            resetScopeFields(scope)
        }
        snapshot.modelFields.forEach { (modelCode, fieldMap) ->
            val modelConfig = Model.getModelConfigMap()[modelCode] ?: return@forEach
            fieldMap.forEach { (fieldCode, configValue) ->
                val field = modelConfig.getModelField(fieldCode) ?: return@forEach
                if (field.configPortScope != scope) {
                    return@forEach
                }
                if (configValue == null) {
                    field.reset()
                } else {
                    field.setConfigValue(configValue)
                    require(field.getConfigValue() == configValue) {
                        "配置字段 $modelCode.$fieldCode 内容无效"
                    }
                }
            }
        }
    }

    private fun resetScopeFields(scope: ConfigPortScope) {
        Model.getModelConfigMap().values.forEach { modelConfig ->
            modelConfig.fields.values.forEach { field ->
                if (field.configPortScope == scope) {
                    field.reset()
                }
            }
        }
    }

    private fun writeCustomSettings(userId: String, customSettings: LinkedHashMap<String, Any?>): Boolean {
        val file = Files.getCustomSetFile(userId) ?: return false
        return Files.write2File(JsonUtil.formatJson(customSettings), file)
    }

    private fun captureFileBackups(userId: String, resolvedImport: ResolvedSettingsImport): List<FileBackup> {
        val backupFiles = linkedSetOf<File>()
        val configFile = if (userId.isBlank()) Files.getDefaultConfigV2File() else Files.getConfigV2File(userId)
        backupFiles.add(configFile)
        if (userId.isNotBlank()) {
            if (resolvedImport.customSettings != null) {
                backupFiles.add(File(Files.getUserConfigDir(userId), "customset.json"))
            }
            if (resolvedImport.format == SettingsTransferSourceFormat.PACKAGE_V1) {
                backupFiles.addAll(
                    SettingsTransferCacheRegistry.targetFilesForScope(userId, ConfigPortScope.GENERIC)
                )
                if (resolvedImport.canApplyPrivate) {
                    backupFiles.addAll(
                        SettingsTransferCacheRegistry.targetFilesForScope(userId, ConfigPortScope.ACCOUNT_PRIVATE)
                    )
                    if (resolvedImport.friendCenter != null) {
                        backupFiles.add(File(Files.getUserConfigDir(userId), "friendCenter.json"))
                    }
                }
            }
        }
        return backupFiles.map { file ->
            FileBackup(file = file, existed = file.exists(), content = Files.readFromFile(file))
        }
    }

    private fun restoreFiles(backups: List<FileBackup>) {
        backups.forEach { backup ->
            runCatching {
                if (backup.existed) {
                    Files.write2File(backup.content, backup.file)
                } else if (backup.file.exists()) {
                    backup.file.delete()
                }
            }.onFailure {
                Log.printStackTrace(TAG, "restore file failed: ${backup.file.absolutePath}", it)
            }
        }
    }

    private fun restoreConfig(
        userId: String?,
        genericSnapshot: ConfigTransferSnapshot,
        privateSnapshot: ConfigTransferSnapshot
    ) {
        runCatching {
            applyScopeSnapshot(ConfigPortScope.GENERIC, genericSnapshot, resetScope = true)
            applyScopeSnapshot(ConfigPortScope.ACCOUNT_PRIVATE, privateSnapshot, resetScope = true)
            Config.sanitizeFriendSelectionFieldsForUser(userId)
        }.onFailure {
            Log.printStackTrace(TAG, "restore config failed", it)
        }
    }

    private fun copyFriendCenter(friendCenter: FriendCenterConfig): FriendCenterConfig {
        val raw = JsonUtil.formatJson(friendCenter, false)
        return JsonUtil.parseObject(raw, FriendCenterConfig::class.java)
    }

    private fun buildSuccessMessage(): String {
        return "导入成功"
    }

    private fun buildFailureMessage(throwable: Throwable): String {
        val message = throwable.message.orEmpty()
        return when {
            message.contains("保存") || message.contains("写入") -> "导入失败，保存设置时出错"
            message.contains("内容无效") || message.contains("无效") -> "导入失败，文件中有无法识别的设置"
            else -> "导入失败，请检查文件后重试"
        }
    }

}
