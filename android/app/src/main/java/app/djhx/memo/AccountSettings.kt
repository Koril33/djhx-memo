package app.djhx.memo

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** 账号切换与本机复制导入是独立动作；导入默认取消，禁止隐式上传本机内容。 */
@Composable
fun AccountSettings(model: MemoModel, onClose: () -> Unit) {
    val presentation = LocalPresentation.current
    val profile = model.state.optJSONObject("profile")
    val profileId = profile?.optString("id")
    var register by remember { mutableStateOf(false) }
    var server by remember(
        profileId
    ) { mutableStateOf(profile?.nullableString("server_url") ?: presentation.serverDraft) }
    var username by remember(profileId) { mutableStateOf(profile?.nullableString("username").orEmpty()) }
    var password by remember(profileId) { mutableStateOf("") }
    var invite by remember(profileId) { mutableStateOf("") }
    var device by remember { mutableStateOf("${Build.MANUFACTURER} ${Build.MODEL}") }
    var importSource by remember { mutableStateOf<JSONObject?>(null) }
    var revoke by remember { mutableStateOf<JSONObject?>(null) }
    val profiles = model.state.optJSONArray("profiles")?.objects().orEmpty()
    val isAccount = profile?.optString("kind") == "account"
    LocalizedDialog(onDismissRequest = onClose) {
        Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().fillMaxHeight(0.95f)) {
            Column(Modifier.padding(16.dp)) {
                Row {
                    Text(
                        stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onClose) { Text(stringResource(R.string.done)) }
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { PresentationSettings() }
                    item {
                        HorizontalDivider()
                        Text(stringResource(R.string.current_space, profileLabel(profile)))
                        Text(stringResource(R.string.space_explanation), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { model.switchProfile("local") }, enabled = !model.busy) {
                            Text(stringResource(R.string.open_local))
                        }
                    }
                    profiles.filter { it.optString("id") != "local" }.forEach { saved ->
                        item(key = "profile-${saved.optString("id")}") {
                            TextButton(
                                onClick = { model.switchProfile(saved.getString("id")) },
                                enabled = !model.busy
                            ) {
                                Text(
                                    stringResource(R.string.switch_space, profileLabel(saved)) +
                                        if (saved.optString("kind") == "recovery") stringResource(R.string.preserved_readonly) else ""
                                )
                            }
                        }
                    }
                    if (isAccount) {
                        item {
                            HorizontalDivider()
                            Text(stringResource(R.string.copy_import), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.import_explanation),
                                style = MaterialTheme.typography.bodySmall
                            )
                            TextButton(
                                onClick = { importSource = JSONObject().put("id", "local").put("kind", "local") },
                                enabled = !model.busy
                            ) {
                                Text(
                                    pluralStringResource(
                                        R.plurals.import_count,
                                        model.state.optInt("local_import_count"),
                                        model.state.optInt("local_import_count")
                                    )
                                )
                            }
                        }
                        profiles.filter { it.optString("kind") == "recovery" }.forEach { recovery ->
                            item(key = "recover-${recovery.optString("id")}") {
                                TextButton(onClick = { importSource = recovery }, enabled = !model.busy) {
                                    Text(stringResource(R.string.copy_recovery, profileLabel(recovery)))
                                }
                            }
                        }
                        item {
                            HorizontalDivider()
                            Text(
                                stringResource(R.string.device_management),
                                style = MaterialTheme.typography.titleMedium
                            )
                            TextButton(
                                onClick = model::loadDevices,
                                enabled = !model.busy
                            ) { Text(stringResource(R.string.refresh_devices)) }
                        }
                        model.devices.forEach { entry ->
                            item(key = "device-${entry.optString("id")}") {
                                Row {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            entry.optString("name").ifEmpty { stringResource(R.string.device) } +
                                                if (entry.optBoolean("current")) stringResource(R.string.current_device) else ""
                                        )
                                        Text(
                                            "${entry.optString(
                                                "id"
                                            )}\n${formattedTime(entry.optString("last_seen_at"))}",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    TextButton(
                                        onClick = { revoke = entry },
                                        enabled = !model.busy && entry.isNull("revoked_at")
                                    ) {
                                        Text(
                                            stringResource(
                                                if (entry.isNull("revoked_at")) R.string.revoke else R.string.revoked
                                            )
                                        )
                                    }
                                }
                            }
                        }
                        item {
                            TextButton(
                                onClick = model::logout,
                                enabled = !model.busy
                            ) { Text(stringResource(R.string.logout)) }
                        }
                    }
                    item {
                        HorizontalDivider()
                        Text(
                            stringResource(if (register) R.string.register_title else R.string.login_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(stringResource(R.string.https_required), style = MaterialTheme.typography.bodySmall)
                        if (BuildConfig.DEBUG) {
                            Text(
                                stringResource(R.string.debug_http),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        OutlinedTextField(
                            server,
                            {
                                server = it
                                presentation.serverDraft = it
                            },
                            label = { Text(stringResource(R.string.server_address)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        OutlinedTextField(
                            username,
                            { username = it },
                            label = { Text(stringResource(R.string.username)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        OutlinedTextField(
                            password,
                            { password = it },
                            label = { Text(stringResource(R.string.password)) },
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
                                label = { Text(stringResource(R.string.invite)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    item {
                        OutlinedTextField(
                            device,
                            { device = it },
                            label = { Text(stringResource(R.string.device_name)) },
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
                            ) { Text(stringResource(if (register) R.string.register_sign_in else R.string.sign_in)) }
                            TextButton(onClick = { register = !register }) {
                                Text(
                                    stringResource(if (register) R.string.existing_account else R.string.register_title)
                                )
                            }
                        }
                    }
                    item {
                        Text(
                            stringResource(R.string.about, BuildConfig.VERSION_NAME),
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
            title = { Text(stringResource(R.string.import_confirm_title)) },
            text = { Text(stringResource(R.string.import_confirm_body, profileLabel(source), profileLabel(profile))) },
            confirmButton = {
                TextButton(onClick = {
                    importSource = null
                    model.importLocal(source.getString("id"))
                }, enabled = !model.busy) { Text(stringResource(R.string.confirm_import)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { importSource = null }
                ) { Text(stringResource(R.string.skip_import)) }
            }
        )
    }
    revoke?.let { entry ->
        AlertDialog(
            onDismissRequest = { revoke = null },
            title = { Text(stringResource(R.string.revoke_title)) },
            text = { Text(stringResource(R.string.revoke_body)) },
            confirmButton = {
                TextButton(onClick = {
                    revoke = null
                    model.revokeDevice(entry.getString("id"))
                }) {
                    Text(stringResource(R.string.confirm_revoke))
                }
            },
            dismissButton = { TextButton(onClick = { revoke = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

@Composable
private fun PresentationSettings() {
    val store = LocalPresentation.current
    val prefs = store.preferences
    Text(stringResource(R.string.display_preferences), style = MaterialTheme.typography.titleMedium)
    PreferenceMenu(
        R.string.language,
        prefs.language,
        listOf("system" to R.string.follow_system, "zh-CN" to R.string.language_zh, "en" to R.string.language_en)
    ) {
        store.update(prefs.copy(language = it))
    }
    PreferenceMenu(
        R.string.appearance,
        prefs.theme,
        listOf("system" to R.string.follow_system, "light" to R.string.theme_light, "dark" to R.string.theme_dark)
    ) {
        store.update(prefs.copy(theme = it))
    }
    PreferenceMenu(
        R.string.time_zone,
        prefs.timeZone,
        listOf(
            "Asia/Shanghai" to R.string.zone_shanghai,
            "UTC" to R.string.zone_utc,
            "system" to R.string.follow_system
        )
    ) {
        store.update(prefs.copy(timeZone = it))
    }
    Text(stringResource(R.string.preferences_explanation), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun PreferenceMenu(label: Int, selected: String, options: List<Pair<String, Int>>, onSelect: (String) -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.first { it.first == selected }.second
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text("${stringResource(label)}: ${stringResource(selectedLabel)}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration) {
                options.forEach { (value, name) ->
                    DropdownMenuItem(text = { Text(stringResource(name)) }, onClick = {
                        expanded = false
                        onSelect(value)
                    })
                }
            }
        }
    }
}
