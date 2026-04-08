package com.serialport.plugin.messages

/**
 * 管理串口消息的循环集合，按字节大小限制
 * 参考 Logcat 的 MessageBacklog 实现
 * 
 * 这个缓冲区的目的是允许在需要时进行过滤和重新渲染，
 * 例如当格式化选项更改时。
 */
internal class MessageBacklog(private var maxSize: Int) {

    // 内部消息集合作为内部 ArrayDeque 的副本暴露
    // 注意：所有访问都通过 synchronized(_messages) 进行同步
    private val _messages = ArrayDeque<SerialPortMessage>()

    val messages: List<SerialPortMessage>
        get() = synchronized(_messages) { _messages.toList() }

    private var isEmpty: Boolean = true
    private var size = 0

    init {
        assert(maxSize > 0)
    }

    fun isEmpty() = isEmpty

    fun isNotEmpty() = !isEmpty

    fun addAll(collection: List<SerialPortMessage>) {
        val addedSize = collection.sumOf { it.getSize() }

        // 分为 2 个流程
        //  如果新消息已经大于 maxSize，我们清空缓冲区并只添加适合的消息
        //  否则，我们首先删除会溢出的消息，然后添加新消息
        synchronized(_messages) {
            if (addedSize >= maxSize) {
                _messages.clear()
                size = addedSize
                var currentSize = addedSize
                val i = collection.indexOfFirst {
                    currentSize -= it.getSize()
                    currentSize <= maxSize
                }
                _messages.addAll(collection.subList(i + 1, collection.size))
                size = _messages.sumOf { it.getSize() }
            } else {
                size += addedSize
                while (size > maxSize) {
                    size -= _messages.removeFirst().getSize()
                }
                _messages.addAll(collection)
            }
            isEmpty = false
        }
    }

    fun setMaxSize(newSize: Int) {
        synchronized(_messages) {
            if (newSize < maxSize) {
                while (size > newSize) {
                    size -= _messages.removeFirst().getSize()
                }
            }
            maxSize = newSize
        }
    }

    fun clear() {
        synchronized(_messages) {
            _messages.clear()
            size = 0
            isEmpty = true
        }
    }
}
