package app.nestro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.nestro.ui.theme.Background
import app.nestro.ui.theme.Mint
import app.nestro.ui.theme.Surface as NestroSurface
import app.nestro.ui.theme.TextPrimary
import app.nestro.ui.theme.TextSecondary

@Composable
fun NestroApp() {
    var prompt by rememberSaveable { mutableStateOf("") }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Background
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Nestro", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                    Text("本地优先 · 开源", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                }
                Spacer(Modifier.height(18.dp))
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 72.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier.size(76.dp).background(Mint, RoundedCornerShape(24.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("N", color = Background, style = MaterialTheme.typography.headlineLarge)
                    }
                    Spacer(Modifier.height(20.dp))
                    Text("今天想创造点什么？", color = TextPrimary, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "连接你自己的模型，组合专家、技能与知识",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(28.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Suggestion("写作创作") { prompt = "帮我完成一项写作任务" }
                        Suggestion("分析资料") { prompt = "帮我分析这份资料" }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Suggestion("开发代码") { prompt = "帮我规划并编写代码" }
                        Suggestion("构建专家") { prompt = "帮我创建一个专业 AI 专家" }
                    }
                }
            }

            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = { }) { Text("模型未配置", color = Mint) }
                    TextButton(onClick = { }) { Text("专家", color = TextSecondary) }
                    TextButton(onClick = { }) { Text("技能", color = TextSecondary) }
                }
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("先配置模型，再开始对话", color = TextSecondary) },
                    leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = "更多", tint = TextSecondary) },
                    trailingIcon = {
                        Row {
                            IconButton(onClick = { }) { Icon(Icons.Outlined.AttachFile, contentDescription = "附件", tint = TextSecondary) }
                            IconButton(onClick = { }) { Icon(Icons.Outlined.Mic, contentDescription = "语音", tint = TextSecondary) }
                            IconButton(onClick = { }) { Icon(Icons.Outlined.Send, contentDescription = "发送", tint = Mint) }
                        }
                    },
                    shape = RoundedCornerShape(22.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Mint,
                        unfocusedBorderColor = NestroSurface,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = Mint
                    )
                )
                Text(
                    "当前为界面骨架，模型连接与本地数据层尚未接入。",
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp),
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun Suggestion(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = NestroSurface,
        shape = RoundedCornerShape(50)
    ) {
        Text(label, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), color = TextPrimary)
    }
}
