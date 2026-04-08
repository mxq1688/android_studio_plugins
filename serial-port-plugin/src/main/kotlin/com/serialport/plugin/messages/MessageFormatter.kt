package com.serialport.plugin.messages

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import java.awt.Color
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 格式化串口消息
 * 参考 Logcat 的 MessageFormatter 实现
 */
internal class MessageFormatter(
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    private var softWrapEnabled = false
    private var showTimestamp = true
    private var displayHex = false
    private var isCompactView = false

    fun setSoftWrapEnabled(value: Boolean) {
        softWrapEnabled = value
    }

    fun setShowTimestamp(value: Boolean) {
        showTimestamp = value
    }

    fun setDisplayHex(value: Boolean) {
        displayHex = value
    }

    fun setCompactView(value: Boolean) {
        isCompactView = value
    }

    fun formatMessages(
        textAccumulator: TextAccumulator,
        messages: List<SerialPortMessage>,
    ) {
        val newline = if (softWrapEnabled) "\n" else "\n"

        for (message in messages) {
            val start = textAccumulator.getTextLength()

            // Logcat 风格格式化：时间戳 + 级别 + 方向标签 + 消息
            if (isCompactView) {
                // Compact View: HH:mm:ss.SSS L/TAG : message
                if (showTimestamp) {
                    val timestamp = message.timestamp.atZone(zoneId).format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
                    textAccumulator.accumulate(
                        "$timestamp ",
                        textAttributes = TextAttributes(JBColor(Color(128, 128, 128), Color(150, 150, 150)), null, null, null, 0)
                    )
                }
                
                val levelText = when (message.level) {
                    SerialPortMessage.LogLevel.VERBOSE -> "V"
                    SerialPortMessage.LogLevel.DEBUG -> "D"
                    SerialPortMessage.LogLevel.INFO -> "I"
                    SerialPortMessage.LogLevel.WARN -> "W"
                    SerialPortMessage.LogLevel.ERROR -> "E"
                }
                val levelColor = when (message.level) {
                    SerialPortMessage.LogLevel.VERBOSE -> JBColor(Color(128, 128, 128), Color(150, 150, 150))
                    SerialPortMessage.LogLevel.DEBUG -> JBColor(Color(0, 102, 204), Color(86, 156, 214))
                    SerialPortMessage.LogLevel.INFO -> JBColor(Color(0, 128, 0), Color(78, 201, 176))
                    SerialPortMessage.LogLevel.WARN -> JBColor(Color(187, 134, 0), Color(220, 180, 80))
                    SerialPortMessage.LogLevel.ERROR -> JBColor(Color(187, 0, 0), Color(244, 108, 108))
                }
                textAccumulator.accumulate(
                    "$levelText/",
                    textAttributes = TextAttributes(levelColor, null, null, null, 0)
                )
                
                val directionText = when (message.direction) {
                    SerialPortMessage.Direction.TX -> "TX"
                    SerialPortMessage.Direction.RX -> "RX"
                    SerialPortMessage.Direction.SYS -> "SYS"
                    SerialPortMessage.Direction.ERR -> "ERR"
                }
                val directionColor = when (message.direction) {
                    SerialPortMessage.Direction.TX -> JBColor(Color(65, 105, 225), Color(100, 149, 237))
                    SerialPortMessage.Direction.RX -> JBColor(Color(34, 139, 34), Color(50, 205, 50))
                    SerialPortMessage.Direction.SYS -> JBColor(Color(128, 128, 128), Color(169, 169, 169))
                    SerialPortMessage.Direction.ERR -> JBColor(Color(220, 20, 60), Color(255, 99, 71))
                }
                textAccumulator.accumulate(
                    "$directionText ",
                    textAttributes = TextAttributes(directionColor, null, null, null, 1)
                )
            } else {
                // Standard View: yyyy-MM-dd HH:mm:ss.SSS  L/TAG  : message
                if (showTimestamp) {
                    val timestamp = message.timestamp.atZone(zoneId).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"))
                    textAccumulator.accumulate(
                        "$timestamp  ",
                        textAttributes = TextAttributes(JBColor(Color(128, 128, 128), Color(150, 150, 150)), null, null, null, 0)
                    )
                }
                
                val levelText = when (message.level) {
                    SerialPortMessage.LogLevel.VERBOSE -> "V"
                    SerialPortMessage.LogLevel.DEBUG -> "D"
                    SerialPortMessage.LogLevel.INFO -> "I"
                    SerialPortMessage.LogLevel.WARN -> "W"
                    SerialPortMessage.LogLevel.ERROR -> "E"
                }
                val levelColor = when (message.level) {
                    SerialPortMessage.LogLevel.VERBOSE -> JBColor(Color(128, 128, 128), Color(150, 150, 150))
                    SerialPortMessage.LogLevel.DEBUG -> JBColor(Color(0, 102, 204), Color(86, 156, 214))
                    SerialPortMessage.LogLevel.INFO -> JBColor(Color(0, 128, 0), Color(78, 201, 176))
                    SerialPortMessage.LogLevel.WARN -> JBColor(Color(187, 134, 0), Color(220, 180, 80))
                    SerialPortMessage.LogLevel.ERROR -> JBColor(Color(187, 0, 0), Color(244, 108, 108))
                }
                textAccumulator.accumulate(
                    "$levelText/",
                    textAttributes = TextAttributes(levelColor, null, null, null, 0)
                )
                
                val directionText = when (message.direction) {
                    SerialPortMessage.Direction.TX -> "TX"
                    SerialPortMessage.Direction.RX -> "RX"
                    SerialPortMessage.Direction.SYS -> "SYS"
                    SerialPortMessage.Direction.ERR -> "ERR"
                }
                val directionColor = when (message.direction) {
                    SerialPortMessage.Direction.TX -> JBColor(Color(65, 105, 225), Color(100, 149, 237))
                    SerialPortMessage.Direction.RX -> JBColor(Color(34, 139, 34), Color(50, 205, 50))
                    SerialPortMessage.Direction.SYS -> JBColor(Color(128, 128, 128), Color(169, 169, 169))
                    SerialPortMessage.Direction.ERR -> JBColor(Color(220, 20, 60), Color(255, 99, 71))
                }
                textAccumulator.accumulate(
                    "$directionText  ",
                    textAttributes = TextAttributes(directionColor, null, null, null, 1)
                )
            }

            // 消息内容 (Logcat 风格：": message")
            textAccumulator.accumulate(": ", textAttributes = null)
            
            val content = if (displayHex && message.rawBytes != null) {
                message.rawBytes.joinToString(" ") { "%02X".format(it) }
            } else {
                message.content
            }

            val defaultColor = when (message.level) {
                SerialPortMessage.LogLevel.VERBOSE -> JBColor(Color(128, 128, 128), Color(150, 150, 150))
                SerialPortMessage.LogLevel.DEBUG -> JBColor(Color(0, 102, 204), Color(86, 156, 214))
                SerialPortMessage.LogLevel.INFO -> JBColor(Color(0, 0, 0), Color(187, 187, 187))
                SerialPortMessage.LogLevel.WARN -> JBColor(Color(187, 134, 0), Color(220, 180, 80))
                SerialPortMessage.LogLevel.ERROR -> JBColor(Color(187, 0, 0), Color(244, 108, 108))
            }

            // 解析 ANSI 颜色并渲染
            formatAnsiContent(textAccumulator, content.replace("\n", newline), defaultColor)

            textAccumulator.accumulate("\n")
            val end = textAccumulator.getTextLength()
            textAccumulator.addMessageRange(start, end - 1, message)
        }
    }

    fun reset() {
        // 重置状态（如果需要）
    }
    
    /**
     * 解析 ANSI 转义序列并渲染带颜色的文本
     * 支持格式: \x1b[<code>m 或 \033[<code>m
     */
    private fun formatAnsiContent(
        textAccumulator: TextAccumulator,
        content: String,
        defaultColor: JBColor
    ) {
        // ANSI 转义序列正则: \x1b[ 或 \033[ 后跟数字和 m
        val ansiPattern = Regex("\u001b\\[([0-9;]*)m")
        
        var currentColor: Color? = defaultColor
        var currentBold = false
        var lastEnd = 0
        
        val matches = ansiPattern.findAll(content)
        
        for (match in matches) {
            // 输出 ANSI 序列之前的文本
            if (match.range.first > lastEnd) {
                val text = content.substring(lastEnd, match.range.first)
                val fontStyle = if (currentBold) 1 else 0  // 1 = Font.BOLD
                textAccumulator.accumulate(
                    text = text,
                    textAttributes = TextAttributes(currentColor, null, null, null, fontStyle)
                )
            }
            
            // 解析 ANSI 代码
            val codes = match.groupValues[1].split(";").mapNotNull { it.toIntOrNull() }
            for (code in codes) {
                when (code) {
                    0 -> { currentColor = defaultColor; currentBold = false }  // 重置
                    1 -> currentBold = true  // 粗体
                    22 -> currentBold = false  // 取消粗体
                    // 标准前景色 (30-37)
                    30 -> currentColor = JBColor(Color(0, 0, 0), Color(0, 0, 0))         // 黑
                    31 -> currentColor = JBColor(Color(205, 49, 49), Color(244, 108, 108))   // 红
                    32 -> currentColor = JBColor(Color(13, 188, 121), Color(78, 201, 176))   // 绿
                    33 -> currentColor = JBColor(Color(229, 229, 16), Color(220, 180, 80))   // 黄
                    34 -> currentColor = JBColor(Color(36, 114, 200), Color(86, 156, 214))   // 蓝
                    35 -> currentColor = JBColor(Color(188, 63, 188), Color(206, 145, 206))  // 紫/品红
                    36 -> currentColor = JBColor(Color(17, 168, 205), Color(97, 214, 214))   // 青
                    37 -> currentColor = JBColor(Color(229, 229, 229), Color(187, 187, 187)) // 白
                    39 -> currentColor = defaultColor  // 默认前景色
                    // 亮色前景色 (90-97)
                    90 -> currentColor = JBColor(Color(102, 102, 102), Color(128, 128, 128)) // 亮黑/灰
                    91 -> currentColor = JBColor(Color(241, 76, 76), Color(255, 128, 128))   // 亮红
                    92 -> currentColor = JBColor(Color(35, 209, 139), Color(128, 255, 128))  // 亮绿
                    93 -> currentColor = JBColor(Color(245, 245, 67), Color(255, 255, 128))  // 亮黄
                    94 -> currentColor = JBColor(Color(59, 142, 234), Color(128, 160, 255))  // 亮蓝
                    95 -> currentColor = JBColor(Color(214, 112, 214), Color(255, 128, 255)) // 亮紫
                    96 -> currentColor = JBColor(Color(41, 184, 219), Color(128, 255, 255))  // 亮青
                    97 -> currentColor = JBColor(Color(255, 255, 255), Color(255, 255, 255)) // 亮白
                }
            }
            
            lastEnd = match.range.last + 1
        }
        
        // 输出剩余的文本
        if (lastEnd < content.length) {
            val text = content.substring(lastEnd)
            val fontStyle = if (currentBold) 1 else 0
            textAccumulator.accumulate(
                text = text,
                textAttributes = TextAttributes(currentColor, null, null, null, fontStyle)
            )
        }
        
        // 如果没有 ANSI 序列，直接输出原始文本
        if (matches.none()) {
            textAccumulator.accumulate(
                text = content,
                textAttributes = TextAttributes(defaultColor, null, null, null, 0)
            )
        }
    }
}
