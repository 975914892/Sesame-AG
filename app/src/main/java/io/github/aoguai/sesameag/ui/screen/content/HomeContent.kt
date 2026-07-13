package io.github.aoguai.sesameag.ui.screen.content

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.aoguai.sesameag.ui.MainActivity
import io.github.aoguai.sesameag.ui.compose.CommonAlertDialog
import io.github.aoguai.sesameag.ui.extension.openUrl
import io.github.aoguai.sesameag.ui.permissions.PermissionHealthSnapshot
import io.github.aoguai.sesameag.ui.screen.card.ModuleStatusCard
import io.github.aoguai.sesameag.ui.screen.card.OneWordCard
import io.github.aoguai.sesameag.ui.screen.card.ServicesStatusCard
import io.github.aoguai.sesameag.ui.viewmodel.MainViewModel
import io.github.aoguai.sesameag.util.CommandUtil.ServiceStatus
import io.github.aoguai.sesameag.util.LogChannel
import io.github.aoguai.sesameag.util.OfficialBuildVerifier
import io.github.aoguai.sesameag.util.ToastUtil

@Composable
fun HomeContent(
    hasActiveUser: Boolean,
    moduleStatus: MainViewModel.ModuleStatus,
    serviceStatus: ServiceStatus,
    permissionHealth: PermissionHealthSnapshot,
    oneWord: String,
    isOneWordLoading: Boolean,
    isLegalAccepted: Boolean,
    onLegalAcceptedChange: (Boolean) -> Unit,
    onOneWordClick: () -> Unit,
    onEvent: (MainActivity.MainUiEvent) -> Unit,
) {
    val context = LocalContext.current
    var isServiceCardExpanded by remember { mutableStateOf(false) }
    var showOfficialSignatureDialog by remember { mutableStateOf(false) }

    val isOfficiallySigned =
        remember(context.applicationContext) {
            OfficialBuildVerifier.isOfficiallySigned(context.applicationContext)
        }

    var isStatusCardExpanded by remember { mutableStateOf(false) }
    val legalNoticeUrl = "https://github.com/Sesame-AG/Sesame-AG/blob/dev/LEGAL.md"
    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "本应用开源免费,严禁倒卖",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall,
                )
                if (isOfficiallySigned) {
                    Text(
                        text = "✓ 官方签名构建",
                        modifier =
                            Modifier
                                .padding(top = 4.dp)
                                .clickable { showOfficialSignatureDialog = true },
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall,
                        textDecoration = TextDecoration.Underline,
                    )
                }
            }
        }
        if (!hasActiveUser) {
            item {
                Text(
                    text = "尚未读取到当前目标应用账号。请先打开目标应用并完成登录，再返回模块首页载入账号与配置。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        // 1. 模块状态
        item {
            ModuleStatusCard(
                status = moduleStatus,
                expanded = isStatusCardExpanded,
                onClick = {
                    if (
                        moduleStatus is MainViewModel.ModuleStatus.NotActivated ||
                        moduleStatus is MainViewModel.ModuleStatus.Unsupported
                    ) {
                        isStatusCardExpanded = !isStatusCardExpanded // 此处不可省略
                    }
                },
                onDoubleClick = {
                    if (
                        moduleStatus is MainViewModel.ModuleStatus.NotActivated ||
                        moduleStatus is MainViewModel.ModuleStatus.Unsupported
                    ) {
                        isStatusCardExpanded = !isStatusCardExpanded
                    }
                },
            )
        }

        // 2. 服务权限
        item {
            ServicesStatusCard(
                status = serviceStatus,
                permissionHealth = permissionHealth,
                expanded = isServiceCardExpanded,
                onClick = {
                    onEvent(
                        MainActivity.MainUiEvent.RequestPermissionCheck { canToggleDetails ->
                            if (canToggleDetails) {
                                isServiceCardExpanded = !isServiceCardExpanded // 此处不可省略
                            }
                        },
                    )
                },
                onDoubleClick = { isServiceCardExpanded = !isServiceCardExpanded },
            )
        }

        // 3. LEGAL 说明确认
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = isLegalAccepted,
                    onCheckedChange = onLegalAcceptedChange,
                )
                Text(
                    text = "我已阅读、理解并接受 LICENSE 与 LEGAL 中的相关说明",
                    modifier =
                        Modifier
                            .weight(1f)
                            .clickable { context.openUrl(legalNoticeUrl) },
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    textDecoration = TextDecoration.Underline,
                )
            }
        }

        // 4. 一言
        item {
            OneWordCard( // 提取出的一言卡片组件
                oneWord = oneWord,
                isLoading = isOneWordLoading,
                onClick = onOneWordClick,
                onLongClick = {
                    onEvent(MainActivity.MainUiEvent.OpenLog(LogChannel.DEBUG))
                    ToastUtil.showToast(context, "准备起飞🛫")
                },
            )
        }
    }

    CommonAlertDialog(
        showDialog = showOfficialSignatureDialog,
        onDismissRequest = { showOfficialSignatureDialog = false },
        onConfirm = { showOfficialSignatureDialog = false },
        title = "官方签名构建",
        text = "当前安装 APK 的签名证书与官方发布证书匹配。该标识只验证 APK 的签名来源，不代表下载、转发或售卖渠道获得官方授权；请继续以官方仓库的正式发布记录为准。",
        confirmText = "知道了",
        showCancelButton = false,
    )
}
