package app.djhx.memo

/**
 * 大笔记装饰降级阈值与 UTF-8 字节统计。
 *
 * 阈值与 core 共享代码高亮的 128 KiB 上限保持一致，但 Android 本地 Markdown 正则、查找
 * 逐命中 span 与代码着色应用是独立路径，不受 core 上限保护。达到阈值即视为大笔记，只
 * 简化/暂停昂贵的展示装饰；正文、保存、查找计数与导航语义完全不变。
 */
internal const val LARGE_NOTE_UTF8_BYTES = 128 * 1024

/**
 * 精确统计 [value] 的 UTF-8 编码字节数。代理对按 4 字节计，孤立代理按替换字符 3 字节计，
 * 其余 BMP 与补充平面字符按 RFC 3629 规则计。
 */
internal fun utf8ByteLength(value: CharSequence): Int {
    var bytes = 0
    var index = 0
    while (index < value.length) {
        val code = value[index].code
        when {
            code <= 0x7F -> bytes += 1
            code <= 0x7FF -> bytes += 2
            code in 0xD800..0xDBFF && index + 1 < value.length && value[index + 1].code in 0xDC00..0xDFFF -> {
                bytes += 4
                index++
            }
            else -> bytes += 3
        }
        index++
    }
    return bytes
}

/** 判断 [value] 是否达到大笔记阈值。 */
internal fun isLargeNote(value: CharSequence): Boolean = utf8ByteLengthAtLeast(value, LARGE_NOTE_UTF8_BYTES)

/**
 * 字节计数达到 [threshold] 即返回 true，避免对大正文做整篇 UTF-8 拷贝。
 * 每次输入只扫描到阈值即短路，大笔记上不随按键退化为全量统计。
 */
internal fun utf8ByteLengthAtLeast(value: CharSequence, threshold: Int): Boolean {
    if (threshold <= 0) return true
    var bytes = 0
    var index = 0
    while (index < value.length) {
        val code = value[index].code
        bytes += when {
            code <= 0x7F -> 1
            code <= 0x7FF -> 2
            code in 0xD800..0xDBFF && index + 1 < value.length && value[index + 1].code in 0xDC00..0xDFFF -> {
                index++
                4
            }
            else -> 3
        }
        if (bytes >= threshold) return true
        index++
    }
    return bytes >= threshold
}
