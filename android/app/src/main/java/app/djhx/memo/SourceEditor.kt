package app.djhx.memo

import android.graphics.Typeface
import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView

/** 平台 ScrollView 承载同一 EditText，提供原生惯性与边界，并保留选区、撤销和输入法 composing span。 */
@Composable
internal fun SourceEditor(
    text: String,
    enabled: Boolean,
    codeSpans: List<CodeSpan> = emptyList(),
    visible: Boolean = true,
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
            val editor = object : EditText(context) {
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
                textSize = 17f
                setLineSpacing(0f, 1.65f)
                typeface = Typeface.DEFAULT
                setTextColor(textColor)
                setHintTextColor(hintColor)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                val inset = (24 * resources.displayMetrics.density).toInt()
                setPadding(inset, inset / 2, inset, inset)
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
                    override fun afterTextChanged(s: Editable?) {
                        s?.let {
                            highlight(it, darkState.value)
                            it.clearCodeColors()
                        }
                        tag = darkState.value to emptyList<CodeSpan>()
                    }
                })
            }
            // 由平台容器统一处理拖动、惯性和边界；EditText 按正文高度布局，保留原生编辑/选区/IME。
            ScrollView(context).apply {
                isFillViewport = true
                // AndroidView 不自动裁切互操作 View；长正文只能绘制在此原生视口内。
                clipChildren = true
                clipToPadding = true
                outlineProvider = android.view.ViewOutlineProvider.BOUNDS
                clipToOutline = true
                isVerticalScrollBarEnabled = true
                addView(
                    editor,
                    android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        },
        update = { viewport ->
            val editor = viewport.getChildAt(0) as EditText
            if (!visible || !enabled) viewport.fling(0)
            viewport.visibility = if (visible) android.view.View.VISIBLE else android.view.View.INVISIBLE
            viewport.isEnabled = enabled && visible
            viewport.importantForAccessibility = if (visible) {
                android.view.View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            } else {
                android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            if (!visible && android.view.inputmethod.BaseInputConnection.getComposingSpanStart(editor.text) >= 0) {
                android.view.inputmethod.BaseInputConnection.removeComposingSpans(editor.text)
                compositionCallback.value(false)
            }
            if (!visible && editor.hasFocus()) {
                // 预览只隐藏此 View；将组合文字作为当前正文提交，不清空原生撤销历史。
                compositionCallback.value(false)
                editor.clearFocus()
                (
                    editor.context.getSystemService(
                        android.content.Context.INPUT_METHOD_SERVICE
                    ) as android.view.inputmethod.InputMethodManager
                    )
                    .hideSoftInputFromWindow(editor.windowToken, 0)
            }
            editor.visibility = if (visible) android.view.View.VISIBLE else android.view.View.INVISIBLE
            editor.importantForAccessibility = if (visible) {
                android.view.View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            } else {
                android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            editor.isEnabled = enabled && visible
            editor.hint = editorHint
            editor.contentDescription = editorDescription
            editor.setTextColor(textColor)
            editor.setHintTextColor(hintColor)
            editor.highlightColor = (accentColor and 0x00FFFFFF) or 0x55000000
            editor.textCursorDrawable?.setTint(accentColor)
            editor.textSelectHandleLeft?.setTint(accentColor)
            editor.textSelectHandleRight?.setTint(accentColor)
            editor.textSelectHandle?.setTint(accentColor)
            // 用户编辑后的 Compose 重组不重设文本；远端更新只在 model 无未提交输入时到达。
            if (editor.text.toString() != text) {
                viewport.fling(0)
                val position = editor.selectionStart.coerceAtLeast(0).coerceAtMost(text.length)
                editor.setText(text)
                editor.setSelection(position)
            }
            val appearance = dark to codeSpans
            if (editor.tag != appearance) {
                highlight(editor.text, dark)
                editor.text.applyCodeColors(codeSpans, dark)
                editor.tag = appearance
            }
        },
    )
}

private class SyntaxColor(color: Int) : ForegroundColorSpan(color)
private class CodeSyntaxColor(color: Int) : ForegroundColorSpan(color)

private fun Editable.clearCodeColors() {
    getSpans(0, length, CodeSyntaxColor::class.java).forEach(::removeSpan)
}

private fun Editable.applyCodeColors(spans: List<CodeSpan>, dark: Boolean) {
    clearCodeColors()
    spans.forEach { span ->
        setSpan(
            CodeSyntaxColor(span.kind.color(dark).toArgb()),
            span.start,
            span.end,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }
}

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
