package com.serialport.plugin.messages

import java.time.Instant

/**
 * 串口消息数据类
 * 参考 Logcat 的 LogcatMessage 设计
 */
data class SerialPortMessage(
    val timestamp: Instant,
    val direction: Direction,
    val level: LogLevel,
    val content: String,
    val rawBytes: ByteArray? = null,
) {
    enum class Direction {
        TX,    // 发送
        RX,    // 接收
        SYS,   // 系统消息
        ERR    // 错误消息
    }

    enum class LogLevel {
        VERBOSE,
        DEBUG,
        INFO,
        WARN,
        ERROR
    }

    /**
     * 计算消息大小（用于缓冲区管理）
     */
    fun getSize(): Int = content.length

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SerialPortMessage

        if (timestamp != other.timestamp) return false
        if (direction != other.direction) return false
        if (level != other.level) return false
        if (content != other.content) return false
        if (rawBytes != null) {
            if (other.rawBytes == null) return false
            if (!rawBytes.contentEquals(other.rawBytes)) return false
        } else if (other.rawBytes != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = timestamp.hashCode()
        result = 31 * result + direction.hashCode()
        result = 31 * result + level.hashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + (rawBytes?.contentHashCode() ?: 0)
        return result
    }
}
