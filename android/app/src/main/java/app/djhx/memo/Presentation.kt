package app.djhx.memo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal const val DEFAULT_SERVER = "https://memo.djhx.site"

/** 本设备展示偏好；不进入笔记数据库、协议或导出。 */
data class DisplayPreferences(
    val language: String = "system",
    val theme: String = "system",
    val timeZone: String = "Asia/Shanghai"
)

/** 使用平台私有偏好持久保存，更新 Compose 状态而不重建 Activity 或编辑器。 */
class PresentationStore(context: Context) {
    private val storage = context.applicationContext.getSharedPreferences("presentation", Context.MODE_PRIVATE)
    var serverDraft: String
        get() = storage.getString("serverDraft", DEFAULT_SERVER) ?: DEFAULT_SERVER
        set(value) { storage.edit().putString("serverDraft", value).apply() }
    var preferences by mutableStateOf(
        DisplayPreferences(
            storage.getString("language", "system").validOr("system", "system", "zh-CN", "en"),
            storage.getString("theme", "system").validOr("system", "system", "light", "dark"),
            storage.getString("timeZone", "Asia/Shanghai").validOr("Asia/Shanghai", "Asia/Shanghai", "UTC", "system")
        )
    )
        private set

    fun update(value: DisplayPreferences) {
        storage.edit().putString("language", value.language).putString("theme", value.theme)
            .putString("timeZone", value.timeZone).apply()
        preferences = value
    }
}

private fun String?.validOr(fallback: String, vararg allowed: String) = if (this in allowed) this!! else fallback

internal fun displayLocale(language: String, system: Locale): Locale = when (language) {
    "zh-CN" -> Locale.SIMPLIFIED_CHINESE
    "en" -> Locale.ENGLISH
    else -> if (system.language == "zh") Locale.SIMPLIFIED_CHINESE else Locale.ENGLISH
}

internal fun displayZone(timeZone: String): ZoneId =
    if (timeZone == "system") ZoneId.systemDefault() else ZoneId.of(timeZone)

internal fun displayDark(theme: String, system: Boolean): Boolean = when (theme) {
    "dark" -> true
    "light" -> false
    else -> system
}

internal fun displayTime(value: String?, locale: Locale, zone: ZoneId): String = try {
    if (value.isNullOrBlank() || value == "null") {
        "—"
    } else {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(locale).withZone(zone).format(Instant.parse(value))
    }
} catch (_: java.time.DateTimeException) { "—" }

internal val LocalPresentation = staticCompositionLocalOf<PresentationStore> { error("PresentationStore missing") }
internal val LocalDisplayLocale = staticCompositionLocalOf { Locale.ENGLISH }
internal val LocalDisplayZone = staticCompositionLocalOf { ZoneId.of("Asia/Shanghai") }
internal val LocalDarkAppearance = staticCompositionLocalOf { false }

@Composable
internal fun MemoPresentation(store: PresentationStore, content: @Composable () -> Unit) {
    val systemConfiguration = LocalConfiguration.current
    val context = LocalContext.current
    val prefs = store.preferences
    val locale = displayLocale(prefs.language, systemConfiguration.locales[0])
    val systemDark = isSystemInDarkTheme()
    val dark = displayDark(prefs.theme, systemDark)
    val configuration = remember(systemConfiguration, locale) {
        Configuration(systemConfiguration).apply { setLocale(locale) }
    }
    // 保留 Activity 作为 ContextWrapper 的 base，供系统文档选择器、Dialog 和返回事件查找宿主。
    val localizedContext = remember(context, configuration) {
        android.view.ContextThemeWrapper(context, R.style.Theme_Memo).apply {
            applyOverrideConfiguration(configuration)
        }
    }
    var systemZone by remember { mutableStateOf(ZoneId.systemDefault()) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { systemZone = ZoneId.systemDefault() }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_TIMEZONE_CHANGED))
        onDispose { context.unregisterReceiver(receiver) }
    }
    val zone = if (prefs.timeZone == "system") systemZone else displayZone(prefs.timeZone)
    val colors = if (dark) {
        darkColorScheme(primary = Color(0xFF91D5BE), secondary = Color(0xFFB4CBBE))
    } else {
        lightColorScheme(primary = Color(0xFF32695B), secondary = Color(0xFF52645D), background = Color(0xFFF8FAF7))
    }
    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalConfiguration provides configuration,
        LocalPresentation provides store,
        LocalDisplayLocale provides locale,
        LocalDisplayZone provides zone,
        LocalDarkAppearance provides dark
    ) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}

/** 固定资源及插值参数，语言改变时重新解析，不缓存已翻译的错误消息。 */
data class UiText(val resource: Int, val arguments: List<Any> = emptyList(), val quantity: Int? = null) {
    @Suppress("SpreadOperator")
    fun resolve(context: Context): String = if (quantity == null) {
        context.getString(resource, *arguments.toTypedArray())
    } else {
        context.resources.getQuantityString(resource, quantity, *arguments.toTypedArray())
    }
}

@Composable
internal fun formattedTime(
    value: String?
): String = displayTime(value, LocalDisplayLocale.current, LocalDisplayZone.current)

/** Dialog 是独立 Android window，显式重新提供最新配置，避免宿主缓存初次打开时的语言。 */
@Composable
internal fun LocalizedDialog(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    Dialog(onDismissRequest = onDismissRequest) {
        CompositionLocalProvider(
            LocalContext provides context,
            LocalConfiguration provides configuration,
            content = content
        )
    }
}

@Composable
internal fun profileLabel(profile: org.json.JSONObject?): String {
    val context = LocalContext.current
    return when (profile?.optString("kind")) {
        "account" -> profile.optString("label")
        "recovery" -> context.getString(
            R.string.recovery_profile,
            profile.optString("username"),
            formattedTime(profile.optString("label").substringAfterLast(" · "))
        )
        else -> context.getString(R.string.local_notes)
    }
}
