package app.djhx.memo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

internal enum class CodeKind(private val light: Long, private val dark: Long) {
    KEYWORD(0xFF805986, 0xFFD5A6DE),
    STRING(0xFFA35449, 0xFFE9A49A),
    COMMENT(0xFF58665C, 0xFFA6BAAC),
    NUMBER(0xFF41765B, 0xFF8AD8AD),
    NAME(0xFF4C7883, 0xFF9CCBD6);

    fun color(darkTheme: Boolean) = Color(if (darkTheme) dark else light)
}

internal data class CodeSpan(val start: Int, val end: Int, val kind: CodeKind)

internal class CodeHighlights(result: JSONObject) {
    val sourceSpans = result.getJSONArray("source_spans").codeSpans()
    private val blocks = result.getJSONArray("blocks").objects().associate { block ->
        (block.getString("language") to block.getString("code").removeSuffix("\n")) to
            block.getJSONArray("spans").codeSpans()
    }

    fun previewSpans(info: String, code: String): List<CodeSpan> {
        val language = info.trimStart().takeWhile { !it.isWhitespace() }.lowercase()
        return blocks[language to code].orEmpty()
    }
}

private fun JSONArray.codeSpans(): List<CodeSpan> = objects().map {
    CodeSpan(it.getInt("start"), it.getInt("end"), CodeKind.valueOf(it.getString("kind").uppercase()))
}

@Composable
internal fun rememberCodeHighlights(source: String, model: MemoModel): CodeHighlights? {
    var highlights by remember(source) { mutableStateOf<CodeHighlights?>(null) }
    LaunchedEffect(source, model) {
        if ("```" !in source && "~~~" !in source) return@LaunchedEffect
        delay(120)
        highlights = try {
            model.highlightCode(source)
        } catch (_: CoreFailure) {
            null
        }
    }
    return highlights
}
