package app.djhx.memo

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import org.json.JSONObject

/** 账号切换与本机复制导入是独立动作；导入默认取消，禁止隐式上传本机内容。 */
@Composable
fun AccountSettings(model: MemoModel, onClose: () -> Unit) {
    val profile = model.state.optJSONObject("profile")
    val profileId = profile?.optString("id")
    var register by remember { mutableStateOf(false) }
    var server by remember(profileId) { mutableStateOf(profile?.nullableString("server_url").orEmpty()) }
    var username by remember(profileId) { mutableStateOf(profile?.nullableString("username").orEmpty()) }
    var password by remember(profileId) { mutableStateOf("") }
    var invite by remember(profileId) { mutableStateOf("") }
    var device by remember { mutableStateOf("${Build.MANUFACTURER} ${Build.MODEL}") }
    var importSource by remember { mutableStateOf<JSONObject?>(null) }
    var revoke by remember { mutableStateOf<JSONObject?>(null) }
    val profiles = model.state.optJSONArray("profiles")?.objects().orEmpty()
    val isAccount = profile?.optString("kind") == "account"
    Dialog(onDismissRequest = onClose) {
        Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().fillMaxHeight(0.95f)) {
            Column(Modifier.padding(16.dp)) {
                Row {
                    Text("账号与设置", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("完成") }
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        Text("当前空间：${profile?.optString("label") ?: "本机笔记"}")
                        Text("每个服务器与账号独立存储；本机空间内容不会自动上传。", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { model.switchProfile("local") }, enabled = !model.busy) { Text("进入本机笔记") }
                    }
                    profiles.filter { it.optString("id") != "local" }.forEach { saved ->
                        item(key = "profile-${saved.optString("id")}") {
                            TextButton(
                                onClick = { model.switchProfile(saved.getString("id")) },
                                enabled = !model.busy
                            ) {
                                Text(
                                    "切换：${saved.optString(
                                        "label"
                                    )}${if (saved.optString("kind") == "recovery") "（只读保全）" else ""}"
                                )
                            }
                        }
                    }
                    if (isAccount) {
                        item {
                            HorizontalDivider()
                            Text("复制导入", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "只复制未删除内容，产生新 ID，原本机笔记保留。默认暂不导入；已导入的相同代次不会重复。",
                                style = MaterialTheme.typography.bodySmall
                            )
                            TextButton(
                                onClick = { importSource = JSONObject().put("id", "local").put("label", "本机笔记") },
                                enabled = !model.busy
                            ) {
                                Text("导入 ${model.state.optInt("local_import_count")} 条本机笔记")
                            }
                        }
                        profiles.filter { it.optString("kind") == "recovery" }.forEach { recovery ->
                            item(key = "recover-${recovery.optString("id")}") {
                                TextButton(
                                    onClick = { importSource = recovery },
                                    enabled = !model.busy
                                ) { Text("从 ${recovery.optString("label")} 复制恢复") }
                            }
                        }
                        item {
                            HorizontalDivider()
                            Text("设备管理", style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = model::loadDevices, enabled = !model.busy) { Text("刷新设备列表") }
                        }
                        model.devices.forEach { entry ->
                            item(key = "device-${entry.optString("id")}") {
                                Row {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            entry.optString("name", "设备") + if (entry.optBoolean("current")) "（当前设备）" else ""
                                        )
                                        Text(
                                            "${entry.optString("id")}\n${entry.optString("last_seen_at")}",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    TextButton(
                                        onClick = { revoke = entry },
                                        enabled = !model.busy && entry.isNull("revoked_at")
                                    ) { Text(if (entry.isNull("revoked_at")) "吊销" else "已吊销") }
                                }
                            }
                        }
                        item {
                            TextButton(onClick = model::logout, enabled = !model.busy) { Text("注销当前账号（保留本地数据）") }
                        }
                    }
                    item {
                        HorizontalDivider()
                        Text(if (register) "邀请码注册" else "登录账号", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "生产服务器必须使用 HTTPS。${if (BuildConfig.DEBUG) "此调试构建允许 HTTP 本机联调。" else ""}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedTextField(
                            server,
                            { server = it },
                            label = { Text("服务器地址 · https://memo.example.com") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        OutlinedTextField(
                            username,
                            { username = it },
                            label = { Text("用户名") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        OutlinedTextField(
                            password,
                            { password = it },
                            label = { Text("密码") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (register) {
                        item {
                            OutlinedTextField(
                                invite,
                                { invite = it },
                                label = { Text("邀请码") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    item {
                        OutlinedTextField(
                            device,
                            { device = it },
                            label = { Text("设备名称") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        Row {
                            Button(
                                onClick = {
                                    model.authenticate(
                                        register,
                                        server.trim(),
                                        username.trim(),
                                        password,
                                        invite,
                                        device.trim()
                                    )
                                    password = ""
                                    invite = ""
                                },
                                enabled = !model.busy && server.isNotBlank() && username.isNotBlank() &&
                                    password.isNotBlank() && (!register || invite.isNotBlank())
                            ) {
                                Text(if (register) "注册并登录" else "登录")
                            }
                            TextButton(onClick = { register = !register }) { Text(if (register) "已有账号" else "邀请码注册") }
                        }
                    }
                    item {
                        Text(
                            "djhx-memo ${BuildConfig.VERSION_NAME} · Android 10+\n离线数据在应用私有目录；卸载或清除应用数据前请导出。",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
    importSource?.let { source ->
        AlertDialog(
            onDismissRequest = { importSource = null },
            title = { Text("复制导入到当前账号？") },
            text = {
                Text(
                    "来源：${source.optString("label")}\n目标：${profile?.optString("label")}\n复制未删除笔记，原内容保留；导入后将同步到这个账号。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        importSource = null
                        model.importLocal(source.getString("id"))
                    },
                    enabled = !model.busy
                ) { Text("确认复制导入") }
            },
            dismissButton = { TextButton(onClick = { importSource = null }) { Text("暂不导入") } }
        )
    }
    revoke?.let { entry ->
        AlertDialog(
            onDismissRequest = { revoke = null },
            title = { Text("吊销设备凭据？") },
            text = { Text("该设备将停止同步并需重新登录。本地笔记不会删除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        revoke = null
                        model.revokeDevice(entry.getString("id"))
                    }
                ) { Text("确认吊销") }
            },
            dismissButton = { TextButton(onClick = { revoke = null }) { Text("取消") } }
        )
    }
}
