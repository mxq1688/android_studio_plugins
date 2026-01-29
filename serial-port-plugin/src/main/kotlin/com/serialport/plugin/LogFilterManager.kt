package com.serialport.plugin

import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.SwingUtilities

/**
 * 日志级别枚举
 */
enum class LogLevel(val priority: Int) {
    VERBOSE(0),
    DEBUG(1),
    INFO(2),
    WARN(3),
    ERROR(4)
}

/**
 * 日志条目数据类
 */
data class LogEntry(
    val timestamp: String,
    val direction: String,  // TX, RX, SYS, ERR
    val content: String,
    val level: LogLevel = LogLevel.DEBUG,
    val rawData: ByteArray? = null,
    val createdTime: Long = System.currentTimeMillis()  // 创建时间戳 (用于 age: 过滤)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as LogEntry
        return timestamp == other.timestamp && direction == other.direction && content == other.content
    }
    
    override fun hashCode(): Int {
        var result = timestamp.hashCode()
        result = 31 * result + direction.hashCode()
        result = 31 * result + content.hashCode()
        return result
    }
}

/**
 * 过滤条件数据类 (Logcat 风格)
 */
data class FilterCondition(
    val keyword: String = "",
    val isRegex: Boolean = false,
    val isExact: Boolean = false,
    val excludeKeyword: String = "",
    val excludeIsRegex: Boolean = false,
    val excludeIsExact: Boolean = false,
    val minLevel: LogLevel = LogLevel.VERBOSE,
    val showTx: Boolean = true,
    val showRx: Boolean = true,
    val showSystem: Boolean = true,
    val maxAgeMs: Long = 0,  // Logcat age: 过滤 (毫秒，0 表示不限制)
    val onlyCrash: Boolean = false,  // Logcat is:crash
    val onlyStacktrace: Boolean = false  // Logcat is:stacktrace
)

/**
 * 日志过滤监听器接口
 */
interface LogFilterListener {
    fun onEntryAdded(entry: LogEntry)
    fun onFilterChanged(filteredEntries: List<LogEntry>)
    fun onLogsCleared()
}

/**
 * 日志过滤管理器
 * 管理日志条目的存储、过滤和通知
 */
class LogFilterManager {
    
    private val allEntries = CopyOnWriteArrayList<LogEntry>()
    private val listeners = CopyOnWriteArrayList<LogFilterListener>()
    private var activeFilter = FilterCondition()
    private var compiledRegex: Regex? = null
    
    // 最大日志条目数
    private val maxEntries = 10000
    
    /**
     * 添加日志条目
     */
    fun addEntry(entry: LogEntry) {
        // 限制最大条目数
        while (allEntries.size >= maxEntries) {
            allEntries.removeAt(0)
        }
        
        allEntries.add(entry)
        
        // 如果条目匹配过滤条件，通知监听器
        if (matchesFilter(entry)) {
            listeners.forEach { it.onEntryAdded(entry) }
        }
    }
    
    // 排除正则
    private var excludeCompiledRegex: Regex? = null
    
    /**
     * 设置活动过滤条件
     */
    fun setActiveFilter(filter: FilterCondition) {
        activeFilter = filter
        
        // 编译包含正则表达式
        compiledRegex = if (filter.isRegex && filter.keyword.isNotEmpty()) {
            try {
                Regex(filter.keyword, RegexOption.IGNORE_CASE)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
        
        // 编译排除正则表达式
        excludeCompiledRegex = if (filter.excludeIsRegex && filter.excludeKeyword.isNotEmpty()) {
            try {
                Regex(filter.excludeKeyword, RegexOption.IGNORE_CASE)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
        
        // 通知过滤条件变化
        val filtered = getFilteredEntries()
        listeners.forEach { it.onFilterChanged(filtered) }
    }
    
    /**
     * 获取过滤后的条目列表
     */
    fun getFilteredEntries(): List<LogEntry> {
        return allEntries.filter { matchesFilter(it) }
    }
    
    /**
     * 清空所有日志
     */
    fun clearLogs() {
        allEntries.clear()
        listeners.forEach { it.onLogsCleared() }
    }
    
    /**
     * 导出所有日志 (Logcat 格式)
     */
    fun exportAllLogs(): String {
        return allEntries.joinToString("\n") { entry ->
            formatLogcatLine(entry)
        }
    }
    
    /**
     * 导出过滤后的日志 (Logcat 格式)
     */
    fun exportFilteredLogs(): String {
        return getFilteredEntries().joinToString("\n") { entry ->
            formatLogcatLine(entry)
        }
    }
    
    /**
     * 格式化为 Logcat 风格: MM-dd HH:mm:ss.SSS  L/TAG  : content
     */
    private fun formatLogcatLine(entry: LogEntry): String {
        val levelChar = when (entry.level) {
            LogLevel.VERBOSE -> 'V'
            LogLevel.DEBUG -> 'D'
            LogLevel.INFO -> 'I'
            LogLevel.WARN -> 'W'
            LogLevel.ERROR -> 'E'
        }
        val tag = entry.direction.padEnd(3)
        return "${entry.timestamp}  $levelChar/$tag : ${entry.content}"
    }
    
    /**
     * 添加监听器
     */
    fun addListener(listener: LogFilterListener) {
        listeners.add(listener)
    }
    
    /**
     * 移除监听器
     */
    fun removeListener(listener: LogFilterListener) {
        listeners.remove(listener)
    }
    
    /**
     * 获取所有日志条目
     */
    fun getAllEntries(): List<LogEntry> = allEntries.toList()
    
    /**
     * 获取日志总数
     */
    fun getTotalCount(): Int = allEntries.size
    
    /**
     * 获取过滤后的日志数
     */
    fun getFilteredCount(): Int = getFilteredEntries().size
    
    // ========== 私有方法 ==========
    
    /**
     * 检查条目是否匹配过滤条件 (Logcat 风格)
     */
    private fun matchesFilter(entry: LogEntry): Boolean {
        // 检查时间范围过滤 (Logcat age: 语法)
        if (activeFilter.maxAgeMs > 0) {
            val age = System.currentTimeMillis() - entry.createdTime
            if (age > activeFilter.maxAgeMs) return false
        }
        
        // 检查 is:crash 过滤 (Logcat 风格)
        if (activeFilter.onlyCrash) {
            val isCrash = entry.level == LogLevel.ERROR || 
                entry.content.contains("crash", ignoreCase = true) ||
                entry.content.contains("exception", ignoreCase = true) ||
                entry.content.contains("fatal", ignoreCase = true)
            if (!isCrash) return false
        }
        
        // 检查 is:stacktrace 过滤 (Logcat 风格)
        if (activeFilter.onlyStacktrace) {
            val isStacktrace = entry.content.contains("at ") ||
                entry.content.contains("Caused by:") ||
                entry.content.startsWith("\t") ||
                entry.content.matches(Regex("^\\s+at\\s+.*"))
            if (!isStacktrace) return false
        }
        
        // 检查方向过滤
        when (entry.direction) {
            "TX" -> if (!activeFilter.showTx) return false
            "RX" -> if (!activeFilter.showRx) return false
            "SYS", "ERR" -> if (!activeFilter.showSystem) return false
        }
        
        // 检查级别过滤
        if (entry.level.priority < activeFilter.minLevel.priority) {
            return false
        }
        
        // 检查排除关键词 (Logcat 风格: -message:, -message=:, -message~:)
        if (activeFilter.excludeKeyword.isNotEmpty()) {
            val shouldExclude = when {
                activeFilter.excludeIsRegex && excludeCompiledRegex != null -> {
                    excludeCompiledRegex!!.containsMatchIn(entry.content)
                }
                activeFilter.excludeIsExact -> {
                    entry.content.equals(activeFilter.excludeKeyword, ignoreCase = true)
                }
                else -> {
                    entry.content.contains(activeFilter.excludeKeyword, ignoreCase = true)
                }
            }
            if (shouldExclude) return false
        }
        
        // 检查包含关键词 (Logcat 风格: message:, message=:, message~:)
        if (activeFilter.keyword.isNotEmpty()) {
            val matches = when {
                activeFilter.isRegex && compiledRegex != null -> {
                    compiledRegex!!.containsMatchIn(entry.content)
                }
                activeFilter.isExact -> {
                    entry.content.equals(activeFilter.keyword, ignoreCase = true)
                }
                else -> {
                    entry.content.contains(activeFilter.keyword, ignoreCase = true)
                }
            }
            if (!matches) return false
        }
        
        return true
    }
    
    companion object {
        /**
         * 解析 Logcat age: 语法 (如 "5m", "1h", "30s")
         * @return 毫秒数，0 表示无效
         */
        fun parseAge(ageStr: String): Long {
            val trimmed = ageStr.trim().lowercase()
            if (trimmed.isEmpty()) return 0
            
            val value = trimmed.dropLast(1).toLongOrNull() ?: return 0
            return when (trimmed.last()) {
                's' -> value * 1000              // 秒
                'm' -> value * 60 * 1000        // 分钟
                'h' -> value * 60 * 60 * 1000   // 小时
                'd' -> value * 24 * 60 * 60 * 1000  // 天
                else -> 0
            }
        }
    }
}
