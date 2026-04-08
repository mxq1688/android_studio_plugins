package com.serialport.plugin.messages

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes

/**
 * 累积文本片段到文本缓冲区和彩色范围列表
 * 参考 Logcat 的 TextAccumulator 实现
 */
internal class TextAccumulator {
    private val stringBuilder = StringBuilder()

    val text: String
        get() = stringBuilder.toString()

    val textAttributesRanges = mutableListOf<Range<TextAttributes>>()
    val textAttributesKeyRanges = mutableListOf<Range<TextAttributesKey>>()
    val messageRanges = mutableListOf<Range<SerialPortMessage>>()

    fun accumulate(
        text: String,
        textAttributes: TextAttributes? = null,
        textAttributesKey: TextAttributesKey? = null,
    ): TextAccumulator {
        assert(textAttributes == null || textAttributesKey == null) {
            "只能设置 textAttributes 或 textAttributesKey 中的一个"
        }
        val start = stringBuilder.length
        val end = start + text.length
        stringBuilder.append(text)
        if (textAttributes != null) {
            textAttributesRanges.add(Range(start, end, textAttributes))
        } else if (textAttributesKey != null) {
            textAttributesKeyRanges.add(Range(start, end, textAttributesKey))
        }
        return this
    }

    fun getTextLength() = stringBuilder.length

    fun addMessageRange(start: Int, end: Int, message: SerialPortMessage) {
        messageRanges.add(Range(start, end, message))
    }

    internal data class Range<T>(val start: Int, val end: Int, val data: T)
}
