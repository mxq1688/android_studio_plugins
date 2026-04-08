package com.serialport.plugin.messages

import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.ex.DocumentEx
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import org.jetbrains.annotations.VisibleForTesting
import kotlin.math.max

val SERIAL_PORT_MESSAGE_KEY = Key.create<SerialPortMessage>("SerialPortMessage")

/**
 * 将文本追加到文档
 * 参考 Logcat 的 DocumentAppender 实现
 */
internal class DocumentAppender(
    project: Project,
    private val document: DocumentEx,
    private var maxDocumentSize: Int,
) {
    private val markupModel = DocumentMarkupModel.forDocument(document, project, true)

    /**
     * RangeMarker 在 Document 中作为弱引用保存（参见 IntervalTreeImpl#createGetter），
     * 所以我们需要在它们有效时保持它们存活。
     */
    @VisibleForTesting internal val ranges = ArrayDeque<RangeMarker>()

    fun reset() {
        ranges.clear()
    }

    // 注意：此方法应在 EDT（事件分发线程）上调用
    fun appendToDocument(buffer: TextAccumulator) {
        val text = buffer.text
        if (text.length >= maxDocumentSize) {
            document.setText("")
            document.insertString(
                document.textLength,
                text.substring(text.lastIndexOf('\n', text.length - maxDocumentSize) + 1),
            )
        } else {
            document.insertString(document.textLength, text)
            trimToSize()
        }

        // 文档有循环缓冲区，所以在插入文本后需要再次获取 document.textLength
        val offset = document.textLength - text.length
        for (range in buffer.textAttributesRanges) {
            range.applyRange(offset) { start, end, textAttributes ->
                markupModel.addRangeHighlighter(
                    start,
                    end,
                    HighlighterLayer.SYNTAX,
                    textAttributes,
                    HighlighterTargetArea.EXACT_RANGE,
                )
            }
        }
        for (range in buffer.textAttributesKeyRanges) {
            range.applyRange(offset) { start, end, textAttributesKey ->
                markupModel.addRangeHighlighter(
                    textAttributesKey,
                    start,
                    end,
                    HighlighterLayer.SYNTAX,
                    HighlighterTargetArea.EXACT_RANGE,
                )
            }
        }

        for (range in buffer.messageRanges) {
            range.applyRange(offset) { start, end, message ->
                ranges.add(
                    document.createRangeMarker(start, end).apply {
                        putUserData(SERIAL_PORT_MESSAGE_KEY, message)
                    }
                )
            }
        }

        while (!ranges.isEmpty() && !ranges.first().isReallyValid()) {
            ranges.removeFirst()
        }
    }

    fun setMaxDocumentSize(size: Int) {
        maxDocumentSize = size
        trimToSize()
    }

    /** 在行边界处将文档修剪到大小（基于 Document.trimToSize） */
    private fun trimToSize() {
        if (document.textLength > maxDocumentSize) {
            val offset = document.textLength - maxDocumentSize
            document.deleteString(0, document.immutableCharSequence.lastIndexOf('\n', offset) + 1)
        }
    }
}

// 似乎有一个 bug，其中与删除部分完全相同的范围仍然有效但大小为 0
private fun RangeMarker.isReallyValid() = isValid && startOffset < endOffset

private fun <T> TextAccumulator.Range<T>.applyRange(
    offset: Int,
    apply: (start: Int, end: Int, data: T) -> Unit,
) {
    val rangeEnd = offset + end
    if (rangeEnd <= 0) {
        return
    }
    val rangeStart = max(offset + start, 0)
    apply(rangeStart, rangeEnd, data)
}
