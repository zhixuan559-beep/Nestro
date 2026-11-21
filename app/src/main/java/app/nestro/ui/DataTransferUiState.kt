package app.nestro.ui

import android.net.Uri
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf

/** UI state for local backup/restore and voice feedback. */
@Stable
internal class DataTransferUiState {
    val backupStatus: MutableState<String> =
        mutableStateOf("备份不包含 API Key，恢复后仍需在本机配置模型。")
    val pendingBackupUri: MutableState<Uri?> = mutableStateOf(null)
    val showBackupRestoreConfirm: MutableState<Boolean> = mutableStateOf(false)
    val voiceStatus: MutableState<String> =
        mutableStateOf("语音由系统识别服务转写后发送；API Key 与对话记录保存在本机。")
}
