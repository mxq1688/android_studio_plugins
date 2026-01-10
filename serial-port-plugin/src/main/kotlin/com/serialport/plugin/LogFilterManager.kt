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
    val rawData: ByteArray? = null
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
 * 过滤条件数据类
 */
data class FilterCondition(
    val keyword: String = "",
    val isRegex: Boolean = false,
    val excludeKeyword: String = "",
    val minLevel: LogLevel = LogLevel.VERBOSE,
    val showTx: Boolean = true,
    val showRx: Boolean = true,
    val showSystem: Boolean = true
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
    
    /**
     * 设置活动过滤条件
     */
    fun setActiveFilter(filter: FilterCondition) {
        activeFilter = filter
        
        // 编译正则表达式
        compiledRegex = if (filter.isRegex && filter.keyword.isNotEmpty()) {
            try {
                Regex(filter.keyword, RegexOption.IGNORE_CASE)
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
     * 导出所有日志
     */
    fun exportAllLogs(): String {
        return allEntries.joinToString("\n") { entry ->
            "${entry.timestamp}  ${entry.direction}  ${entry.content}"
        }
    }
    
    /**
     * 导出过滤后的日志
     */
    fun exportFilteredLogs(): String {
        return getFilteredEntries().joinToString("\n") { entry ->
            "${entry.timestamp}  ${entry.direction}  ${entry.content}"
        }
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
     * 检查条目是否匹配过滤条件
     */
    private fun matchesFilter(entry: LogEntry): Boolean {
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
        
        // 检查排除关键词
        if (activeFilter.excludeKeyword.isNotEmpty()) {
            if (entry.content.contains(activeFilter.excludeKeyword, ignoreCase = true)) {
                return false
            }
        }
        
        // 检查关键词/正则匹配
        if (activeFilter.keyword.isNotEmpty()) {
            val matches = if (activeFilter.isRegex && compiledRegex != null) {
                compiledRegex!!.containsMatchIn(entry.content)
            } else {
                entry.content.contains(activeFilter.keyword, ignoreCase = true)
            }
            if (!matches) return false
        }
        
        return true
    }
}
