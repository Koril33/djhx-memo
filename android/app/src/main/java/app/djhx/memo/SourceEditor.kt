package app.djhx.memo

import android.graphics.Typeface
import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** 平台 ScrollView 承载同一 EditText，提供原生惯性与边界，并保留选区、撤销和输入法 composing span。 */
@Composable
internal fun SourceEditor(
    text: String,
    enabled: Boolean,
    codeSpans: List<CodeSpan> = emptyList(),
    visible: Boolean = true,
    modifier: Modifier = Modifier,
    findOpen: Boolean = false,
    findQuery: String = "",
    onFindQueryChange: (String) -> Unit = {},
    onFindClose: () -> Unit = {},
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
    val findColor = if (dark) 0x4DFFC107 else 0x66FFC107
    val callback = rememberUpdatedState(onEdit)
    val blurCallback = rememberUpdatedState(onBlur)
    val compositionCallback = rememberUpdatedState(onComposition)
    val findHost = remember { mutableStateOf<SourceFindHost?>(null) }
    val host = findHost.value
    val composing = remember { mutableStateOf(false) }

    // 查询/开关/宿主就绪时，重新执行字面查找；关闭时清空高亮。
    LaunchedEffect(findOpen, findQuery, host) {
        if (findOpen) host?.search(findQuery) else host?.clear()
    }

    Column(modifier) {
        if (findOpen && visible) {
            FindBar(
                query = findQuery,
                host = host,
                onQueryChange = onFindQueryChange,
                onNext = { host?.next() },
                onPrevious = { host?.previous() },
                onClose = {
                    host?.clear()
                    onFindClose()
                }
            )
        }
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { context ->
                val editor = object : EditText(context) {
                    private var enterHandled = false

                    /** 折叠选区、无 composing 且光标位于列表项逻辑行末时，应用一次回车续写。 */
                    private fun handleListEnter(): Boolean {
                        if (!isEnabled) return false
                        val editable = getText()
                        if (android.view.inputmethod.BaseInputConnection.getComposingSpanStart(editable) >= 0) return false
                        if (selectionStart != selectionEnd) return false
                        val content = editable.toString()
                        val cursor = selectionStart.coerceAtLeast(0).coerceAtMost(content.length)
                        val plan = planListEnter(content, cursor) ?: return false
                        editable.replace(plan.replaceStart, plan.replaceEnd, plan.text)
                        android.text.Selection.setSelection(editable, plan.cursor)
                        return true
                    }

                    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
                        if (keyCode == KeyEvent.KEYCODE_ENTER) {
                            if (event.action == KeyEvent.ACTION_DOWN) {
                                enterHandled = handleListEnter()
                                if (enterHandled) return true
                            } else if (event.action == KeyEvent.ACTION_UP && enterHandled) {
                                enterHandled = false
                                return true
                            }
                        }
                        return super.onKeyPreIme(keyCode, event)
                    }

                    override fun onCreateInputConnection(
                        info: android.view.inputmethod.EditorInfo
                    ): android.view.inputmethod.InputConnection? {
                        val input = super.onCreateInputConnection(info) ?: return null
                        val editor = this
                        return object : android.view.inputmethod.InputConnectionWrapper(input, false) {
                            private var keyEnterHandled = false
                            private fun composing() {
                                val active = android.view.inputmethod.BaseInputConnection.getComposingSpanStart(
                                    editor.text
                                ) >= 0
                                composing.value = active
                                compositionCallback.value(active)
                            }
                            override fun setComposingText(value: CharSequence?, newCursorPosition: Int): Boolean =
                                super.setComposingText(value, newCursorPosition).also { composing() }
                            override fun setComposingRegion(start: Int, end: Int): Boolean =
                                super.setComposingRegion(start, end).also { composing() }
                            override fun finishComposingText(): Boolean = super.finishComposingText().also { composing() }
                            override fun commitText(value: CharSequence?, newCursorPosition: Int): Boolean {
                                // 软键盘提交单个换行且非组合态时执行续写；候选确认等其它文本走原生路径。
                                if (value != null && value.length == 1 && value[0] == '\n' && handleListEnter()) {
                                    return true
                                }
                                return super.commitText(value, newCursorPosition).also { composing() }
                            }
                            override fun sendKeyEvent(event: KeyEvent?): Boolean {
                                // IME 转发物理 Enter 的兜底路径；与 onKeyPreIme 通过状态去重，恰好消费一次。
                                val enterDown = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER &&
                                    event.action == KeyEvent.ACTION_DOWN
                                if (enterDown && handleListEnter()) {
                                    keyEnterHandled = true
                                    return true
                                }
                                val enterUp = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER &&
                                    event.action == KeyEvent.ACTION_UP
                                if (enterUp && keyEnterHandled) {
                                    keyEnterHandled = false
                                    return true
                                }
                                keyEnterHandled = false
                                return super.sendKeyEvent(event)
                            }
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
                            composing.value = false
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
                            // 输入法组合期间不重新定位；提交后的正文变化才重算命中。
                            if (!composing.value) findHost.value?.refresh()
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
                if (findHost.value == null) findHost.value = SourceFindHost(editor, viewport, findColor)
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
                    composing.value = false
                    compositionCallback.value(false)
                }
                if (!visible && editor.hasFocus()) {
                    // 预览只隐藏此 View；将组合文字作为当前正文提交，不清空原生撤销历史。
                    composing.value = false
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
                findHost.value?.updateHighlightColor(findColor)
            },
        )
    }
}

/** 原生查找条：输入框 + 命中计数 + 上/下一个 + 关闭；查找引擎复用 SourceFindHost。 */
@Composable
private fun FindBar(
    query: String,
    host: SourceFindHost?,
    onQueryChange: (String) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onClose: () -> Unit
) {
    var value by remember { mutableStateOf(TextFieldValue(query)) }
    val focusRequester = remember { FocusRequester() }
    // 外部注入的查询（如全局搜索跳转）同步到输入框。
    LaunchedEffect(query) {
        if (value.text != query) value = TextFieldValue(query, TextRange(query.length))
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val total = host?.total ?: 0
    val current = host?.current ?: -1
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = { next ->
                value = next
                // 输入法组合期间不触发查找，仅提交后的文字生效。
                if (next.composition == null) onQueryChange(next.text)
            },
            modifier = Modifier.weight(1f).focusRequester(focusRequester),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.find_hint)) },
            leadingIcon = { MemoIcon(R.drawable.ic_ui_search) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onNext() })
        )
        Text(
            stringResource(R.string.find_count, if (total == 0) 0 else current + 1, total),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        IconButton(onClick = onPrevious, enabled = total > 0) {
            Icon(
                painterResource(R.drawable.ic_ui_chevron_down),
                contentDescription = stringResource(R.string.find_previous),
                modifier = Modifier.rotate(180f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onNext, enabled = total > 0) {
            Icon(
                painterResource(R.drawable.ic_ui_chevron_down),
                contentDescription = stringResource(R.string.find_next),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onClose) {
            Icon(
                painterResource(R.drawable.ic_ui_x),
                contentDescription = stringResource(R.string.find_close),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
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
