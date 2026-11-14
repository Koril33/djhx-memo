package app.djhx.memo

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import org.json.JSONObject

private data class AuthDraft(
    val server: String,
    val username: String = "",
    val password: String = "",
    val invite: String = "",
    val device: String = "${Build.MANUFACTURER} ${Build.MODEL}"
)

/** 展示、空间、设备与数据分屏；所有切换和复制仍交由既有 model/core 执行。 */
@Composable
@Suppress("CyclomaticComplexMethod")
fun AccountSettings(model: MemoModel, initialTab: String = "display", onExport: () -> Unit = {}, onClose: () -> Unit) {
    val store = LocalPresentation.current
    val profile = model.state.optJSONObject("profile")
    val profileId = profile?.optString("id")
    val isAccount = profile?.optString("kind") == "account"
    var tab by remember { mutableStateOf(initialTab) }
    var register by remember { mutableStateOf(false) }
    var authVisible by remember { mutableStateOf(false) }
    var reauth by remember { mutableStateOf(false) }
    var reauthProfileId by remember { mutableStateOf<String?>(null) }
    var authRequest by remember { mutableIntStateOf(0) }
    val hasAccounts = model.state.optJSONArray(
        "profiles"
    )?.objects().orEmpty().any { it.optString("kind") == "account" }
    val needsAuth = isAccount && model.state.optJSONObject("sync")?.optString("state") == "auth_required"

    var about by remember { mutableStateOf(false) }
    var draft by remember(profileId) {
        mutableStateOf(
            AuthDraft(
                profile?.nullableString("server_url") ?: store.serverDraft,
                profile?.nullableString("username").orEmpty()
            )
        )
    }
    var importSource by remember { mutableStateOf<JSONObject?>(null) }
    var revoke by remember { mutableStateOf<JSONObject?>(null) }
    var deviceDetails by remember { mutableStateOf<JSONObject?>(null) }
    val profiles = model.state.optJSONArray("profiles")?.objects().orEmpty()
    LaunchedEffect(profileId) {
        // A saved form belongs to its original account; changing spaces abandons it.
        if (reauthProfileId != null && reauthProfileId != profileId) {
            authVisible = false
            reauth = false
            reauthProfileId = null
            authRequest++
            draft = AuthDraft(store.serverDraft)
        }
    }
    LaunchedEffect(tab, isAccount) { if (tab == "devices" && isAccount) model.loadDevices() }
    LocalizedDialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MemoIconButton(
                        R.drawable.ic_ui_arrow_left,
                        stringResource(R.string.close_settings),
                        enabled = !model.busy,
                        onClick = onClose
                    )
                    Text(
                        stringResource(R.string.settings_short),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).padding(start = 8.dp)
                    )
                    MemoIconButton(
                        R.drawable.ic_ui_info,
                        stringResource(R.string.about_app, stringResource(R.string.app_name))
                    ) { about = true }
                }
                SettingsTabs(tab, !model.busy) { tab = it }
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    when (tab) {
                        "display" -> item { PresentationSettings() }
                        "updates" -> item { UpdateSettings(model) }
                        "spaces" -> {
                            item { SectionTitle(R.string.current_space_short) }
                            profiles.forEach { saved ->
                                item(key = "profile-${saved.optString("id")}") {
                                    ProfileRow(
                                        saved,
                                        saved.optString("id") == profileId,
                                        !model.busy
                                    ) { model.switchProfile(saved.getString("id")) }
                                }
                            }
                            if (isAccount) {
                                item {
                                    TextButton(onClick = model::logout, enabled = !model.busy) {
                                        MemoIcon(R.drawable.ic_ui_log_out)
                                        Spacer(Modifier.width(12.dp))
                                        Text(stringResource(R.string.logout_short))
                                    }
                                }
                            }
                            if (needsAuth) {
                                item {
                                    Text(stringResource(R.string.cloud_auth), color = MaterialTheme.colorScheme.error)
                                    Button(enabled = !model.busy, onClick = {
                                        reauth = true
                                        reauthProfileId = profileId
                                        register = false
                                        authVisible = true
                                        authRequest++
                                        draft = AuthDraft(
                                            profile?.nullableString("server_url").orEmpty(),
                                            profile?.nullableString("username").orEmpty()
                                        )
                                    }) { Text(stringResource(R.string.sign_in_again)) }
                                }
                            }
                            if (hasAccounts) {
                                item {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    TextButton(enabled = !model.busy, onClick = {
                                        reauth = false
                                        reauthProfileId = null
                                        register = false
                                        authVisible = true
                                        authRequest++
                                        draft = AuthDraft(store.serverDraft)
                                    }) {
                                        MemoIcon(R.drawable.ic_ui_plus)
                                        Spacer(Modifier.width(12.dp))
                                        Text(stringResource(R.string.add_account))
                                    }
                                }
                            }
                            if (authVisible || (!hasAccounts && profile?.optString("kind") == "local")) {
                                item {
                                    if (hasAccounts) {
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.End
                                        ) {
                                            MemoIconButton(
                                                R.drawable.ic_ui_x,
                                                stringResource(R.string.close),
                                                !model.busy
                                            ) {
                                                authVisible = false
                                                reauth = false
                                                reauthProfileId = null
                                                authRequest++
                                                draft = draft.copy(password = "", invite = "")
                                            }
                                        }
                                    }
                                    AuthFields(
                                        draft,
                                        register,
                                        !model.busy,
                                        reauth,
                                        onDraft = { next ->
                                            if (next.server != draft.server) store.serverDraft = next.server
                                            draft = next
                                        },
                                        onRegister = { if (!model.busy) register = !register },
                                        onSubmit = {
                                            if (reauth && reauthProfileId != profileId) return@AuthFields
                                            val request = ++authRequest
                                            model.authenticate(
                                                register,
                                                draft.server.trim(),
                                                draft.username.trim(),
                                                draft.password,
                                                draft.invite,
                                                draft.device.trim()
                                            ) {
                                                if (request == authRequest) {
                                                    authVisible = false
                                                    reauth = false
                                                    reauthProfileId = null
                                                    draft = draft.copy(password = "", invite = "")
                                                }
                                            }
                                            draft = draft.copy(password = "", invite = "")
                                        }
                                    )
                                }
                            }
                        }
                        "devices" -> {
                            if (!isAccount) {
                                item {
                                    EmptySettings(R.string.sign_in_for_devices) { tab = "spaces" }
                                }
                            } else {
                                item {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            shortProfileLabel(profile),
                                            modifier = Modifier.weight(1f),
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        MemoIconButton(
                                            R.drawable.ic_ui_refresh_cw,
                                            stringResource(R.string.refresh_devices),
                                            !model.busy,
                                            onClick = model::loadDevices
                                        )
                                    }
                                }
                                model.devices.forEach { entry ->
                                    item(key = "device-${entry.optString("id")}") {
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Column(
                                                Modifier.weight(
                                                    1f
                                                ).clickable { deviceDetails = entry }.padding(vertical = 8.dp)
                                            ) {
                                                Text(
                                                    entry.optString("name").ifEmpty { stringResource(R.string.device) } +
                                                        if (entry.optBoolean("current")) stringResource(R.string.current_device) else "",
                                                    style = MaterialTheme.typography.bodyLarge
                                                )
                                                Text(
                                                    formattedTime(entry.optString("last_seen_at")),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            MemoIconButton(
                                                R.drawable.ic_ui_log_out,
                                                stringResource(
                                                    if (entry.isNull(
                                                            "revoked_at"
                                                        )
                                                    ) {
                                                        R.string.revoke
                                                    } else {
                                                        R.string.revoked
                                                    }
                                                ),
                                                !model.busy && entry.isNull("revoked_at")
                                            ) { revoke = entry }
                                        }
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    }
                                }
                            }
                        }
                        "data" -> {
                            item {
                                SectionTitle(R.string.export_short)
                                TextButton(onClick = onExport, enabled = !model.busy) {
                                    MemoIcon(R.drawable.ic_ui_file_output)
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        stringResource(
                                            if (model.trash) R.string.export_trash else R.string.export_space
                                        )
                                    )
                                }
                            }
                            if (isAccount) {
                                item {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    SectionTitle(R.string.copy_import)
                                    TextButton(
                                        onClick = { importSource = JSONObject().put("id", "local").put("kind", "local") },
                                        enabled = !model.busy
                                    ) {
                                        MemoIcon(R.drawable.ic_ui_copy)
                                        Spacer(Modifier.width(12.dp))
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
                                            MemoIcon(R.drawable.ic_ui_rotate_ccw)
                                            Spacer(Modifier.width(12.dp))
                                            Text(stringResource(R.string.copy_recovery, profileLabel(recovery)))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    importSource?.let { source ->
        MemoAlertDialog(
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
        MemoAlertDialog(
            onDismissRequest = { revoke = null },
            title = { Text(stringResource(R.string.revoke_title)) },
            text = { Text(stringResource(R.string.revoke_body)) },
            confirmButton = {
                TextButton(onClick = {
                    revoke = null
                    model.revokeDevice(entry.getString("id"))
                }) { Text(stringResource(R.string.confirm_revoke)) }
            },
            dismissButton = { TextButton(onClick = { revoke = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
    deviceDetails?.let { entry ->
        MemoAlertDialog(
            onDismissRequest = { deviceDetails = null },
            title = { Text(entry.optString("name")) },
            text = { Text("${entry.optString("id")}\n${formattedTime(entry.optString("last_seen_at"))}") },
            confirmButton = { TextButton(onClick = { deviceDetails = null }) { Text(stringResource(R.string.done)) } }
        )
    }
    if (about) {
        MemoAlertDialog(
            onDismissRequest = { about = false },
            title = { Text(stringResource(R.string.app_name)) },
            text = {
                Text(
                    stringResource(R.string.about, stringResource(R.string.app_name), BuildConfig.VERSION_NAME)
                )
            },
            confirmButton = { TextButton(onClick = { about = false }) { Text(stringResource(R.string.done)) } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTabs(selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    val tabs = listOf(
        "display" to R.string.tab_display,
        "spaces" to R.string.tab_spaces,
        "devices" to R.string.tab_devices,
        "data" to R.string.tab_data,
        "updates" to R.string.update_title
    )
    val index = tabs.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    val content: @Composable () -> Unit = {
        tabs.forEach { (value, label) ->
            Tab(
                selected = selected == value,
                enabled = enabled,
                onClick = { onSelect(value) },
                text = { Text(stringResource(label), maxLines = 1) }
            )
        }
    }
    val configuration = LocalConfiguration.current
    // 四个等宽分组在窄屏或大字号时会拆开英文单词，改用原生可横向滚动的单行分组。
    if (configuration.screenWidthDp < 600 || configuration.fontScale > 1.15f) {
        ScrollableTabRow(index, containerColor = MaterialTheme.colorScheme.background, edgePadding = 8.dp) { content() }
    } else {
        TabRow(index, containerColor = MaterialTheme.colorScheme.background) { content() }
    }
}

@Composable
private fun AuthFields(
    draft: AuthDraft,
    register: Boolean,
    enabled: Boolean,
    reauth: Boolean,
    onDraft: (AuthDraft) -> Unit,
    onRegister: () -> Unit,
    onSubmit: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            stringResource(
                if (reauth) R.string.sign_in_again else if (register) R.string.register_title else R.string.login_title
            ),
            style = MaterialTheme.typography.titleMedium
        )
        OutlinedTextField(draft.server, {
            onDraft(draft.copy(server = it))
        }, label = {
            Text(
                stringResource(R.string.server_address)
            )
        }, enabled = enabled, readOnly = reauth, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(draft.username, {
            onDraft(draft.copy(username = it))
        }, label = {
            Text(
                stringResource(R.string.username)
            )
        }, enabled = enabled, readOnly = reauth, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(draft.password, {
            onDraft(draft.copy(password = it))
        }, label = {
            Text(stringResource(R.string.password))
        }, enabled = enabled, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
        if (register) {
            OutlinedTextField(draft.invite, {
                onDraft(draft.copy(invite = it))
            }, label = {
                Text(
                    stringResource(R.string.invite)
                )
            }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        OutlinedTextField(draft.device, {
            onDraft(draft.copy(device = it))
        }, label = {
            Text(
                stringResource(R.string.device_name)
            )
        }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
        Column(Modifier.fillMaxWidth()) {
            Button(
                onClick = onSubmit,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled && draft.server.isNotBlank() && draft.username.isNotBlank() &&
                    draft.password.isNotBlank() && (!register || draft.invite.isNotBlank())
            ) { Text(stringResource(if (register) R.string.register_sign_in else R.string.sign_in)) }
            if (!reauth) {
                TextButton(onClick = onRegister, enabled = enabled) {
                    Text(stringResource(if (register) R.string.existing_account else R.string.register_title))
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(label: Int) {
    Text(
        stringResource(label),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun ProfileRow(profile: JSONObject, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(
            min = 56.dp
        ).selectable(
            selected = selected,
            enabled = enabled,
            role = Role.RadioButton,
            onClick = onClick
        ).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        MemoIcon(if (profile.optString("kind") == "local") R.drawable.ic_ui_file_text else R.drawable.ic_ui_cloud)
        Column(Modifier.weight(1f)) {
            val recovery = profile.optString("kind") == "recovery"
            Text(
                if (recovery) profileLabel(profile) else shortProfileLabel(profile),
                maxLines = if (recovery) 2 else 1,
                overflow = TextOverflow.Ellipsis
            )
            profile.nullableString("server_url")?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (profile.optBoolean(
                    "read_only"
                )
            ) {
                Text(stringResource(R.string.preserved_readonly), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (selected) MemoIcon(R.drawable.ic_ui_check, tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun EmptySettings(label: Int, onAccount: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(label), color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onAccount) { Text(stringResource(R.string.tab_spaces)) }
    }
}

@Composable
private fun PresentationSettings() {
    val store = LocalPresentation.current
    val prefs = store.preferences
    PreferenceMenu(
        R.drawable.ic_ui_globe,
        R.string.language,
        prefs.language,
        listOf("system" to R.string.follow_system, "zh-CN" to R.string.language_zh, "en" to R.string.language_en)
    ) { store.update(prefs.copy(language = it)) }
    PreferenceMenu(
        if (LocalDarkAppearance.current) R.drawable.ic_ui_moon else R.drawable.ic_ui_sun,
        R.string.appearance,
        prefs.theme,
        listOf("system" to R.string.follow_system, "light" to R.string.theme_light, "dark" to R.string.theme_dark)
    ) { store.update(prefs.copy(theme = it)) }
    PreferenceMenu(
        R.drawable.ic_ui_globe,
        R.string.time_zone,
        prefs.timeZone,
        listOf(
            "Asia/Shanghai" to R.string.zone_shanghai,
            "UTC" to R.string.zone_utc,
            "system" to R.string.follow_system
        )
    ) { store.update(prefs.copy(timeZone = it)) }
}

@Composable
private fun PreferenceMenu(
    icon: Int,
    label: Int,
    selected: String,
    options: List<Pair<String, Int>>,
    onSelect: (String) -> Unit
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.first { it.first == selected }.second
    Box {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable {
                expanded = true
            },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            MemoIcon(icon, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(selectedLabel),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            MemoIcon(R.drawable.ic_ui_chevron_down, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration) {
                options.forEach { (value, name) ->
                    DropdownMenuItem(text = {
                        Text(stringResource(name))
                    }, onClick = {
                        expanded = false
                        onSelect(value)
                    })
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
