package app.djhx.memo

import android.graphics.Typeface
import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.widget.EditText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView

/** 原生输入组件：只调整自有颜色 span，保留选区、撤销与输入法 composing span。 */
@Composable
fun SourceEditor(
    text: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onBlur: () -> Unit = {
    },
    onComposition: (Boolean) -> Unit = {},
    onEdit: (String) -> Unit
) {
    val dark = LocalDarkAppearance.current
    val editorHint = stringResource(R.string.editor_hint)
    val editorDescription = stringResource(R.string.editor_accessibility)
    val darkState = rememberUpdatedState(dark)
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val accentColor = MaterialTheme.colorScheme.primary.toArgb()
    val callback = rememberUpdatedState(onEdit)
    val blurCallback = rememberUpdatedState(onBlur)
    val compositionCallback = rememberUpdatedState(onComposition)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            object : EditText(context) {
                override fun onCreateInputConnection(
                    info: android.view.inputmethod.EditorInfo
                ): android.view.inputmethod.InputConnection? {
                    val input = super.onCreateInputConnection(info) ?: return null
                    val editor = this
                    return object : android.view.inputmethod.InputConnectionWrapper(input, false) {
                        private fun composing() {
                            compositionCallback.value(
                                android.view.inputmethod.BaseInputConnection.getComposingSpanStart(editor.text) >= 0
                            )
                        }
                        override fun setComposingText(value: CharSequence?, newCursorPosition: Int): Boolean =
                            super.setComposingText(value, newCursorPosition).also { composing() }
                        override fun setComposingRegion(start: Int, end: Int): Boolean =
                            super.setComposingRegion(start, end).also { composing() }
                        override fun finishComposingText(): Boolean = super.finishComposingText().also { composing() }
                        override fun commitText(value: CharSequence?, newCursorPosition: Int): Boolean =
                            super.commitText(value, newCursorPosition).also { composing() }
                    }
                }
            }.apply {
                gravity = Gravity.TOP or Gravity.START
                textSize = 16f
                typeface = Typeface.MONOSPACE
                setTextColor(textColor)
                setHintTextColor(hintColor)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setPadding(16, 16, 16, 16)
                setHorizontallyScrolling(false)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                hint = editorHint
                contentDescription = editorDescription
                onFocusChangeListener = android.view.View.OnFocusChangeListener { _, focused ->
                    if (!focused) {
                        compositionCallback.value(false)
                        blurCallback.value()
                    }
                }
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        callback.value(s?.toString().orEmpty())
                    }
                    override fun afterTextChanged(s: Editable?) { s?.let { highlight(it, darkState.value) } }
                })
            }
        },
        update = { editor ->
            editor.isEnabled = enabled
            editor.hint = editorHint
            editor.contentDescription = editorDescription
            editor.setTextColor(textColor)
            editor.setHintTextColor(hintColor)
            editor.highlightColor = (accentColor and 0x00FFFFFF) or 0x55000000
            editor.textCursorDrawable?.setTint(accentColor)
            editor.textSelectHandleLeft?.setTint(accentColor)
            editor.textSelectHandleRight?.setTint(accentColor)
            editor.textSelectHandle?.setTint(accentColor)
            if (editor.tag != dark) {
                highlight(editor.text, dark)
                editor.tag = dark
            }
            // 用户编辑后的 Compose 重组不重设文本；远端更新只在 model 无未提交输入时到达。
            if (editor.text.toString() != text) {
                val position = editor.selectionStart.coerceAtLeast(0).coerceAtMost(text.length)
                editor.setText(text)
                editor.setSelection(position)
            }
        },
    )
}

private class SyntaxColor(color: Int) : ForegroundColorSpan(color)

private fun highlight(editable: Editable, dark: Boolean) {
    editable.getSpans(0, editable.length, SyntaxColor::class.java).forEach { editable.removeSpan(it) }
    val patterns = listOf(
        Regex("(?m)^ {0,3}#{1,6} .*$") to (if (dark) 0xFF91D5BE else 0xFF236650).toInt(),
        Regex("(?m)^\\s*(?:[-+*]|[0-9]+[.)])\\s+(?:\\[[ xX]\\])?") to (if (dark) 0xFFDCC48C else 0xFF815822).toInt(),
        Regex("\\[[^\\]\\n]+\\]\\([^\\n)]*\\)") to (if (dark) 0xFFA9C7F7 else 0xFF305FA2).toInt(),
        Regex("(?m)^\\s*(?:```|~~~).*$") to (if (dark) 0xFFD8ADEC else 0xFF835A9C).toInt(),
    )
    patterns.forEach { (pattern, color) ->
        pattern.findAll(editable).forEach { match ->
            editable.setSpan(
                SyntaxColor(color),
                match.range.first,
                match.range.last + 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }
}
