package com.serialport.plugin

import com.fazecast.jSerialComm.SerialPort
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import java.io.ByteArrayOutputStream
import java.util.Timer
import java.util.TimerTask

/**
 * 串口通信服务
 * 提供串口连接、数据收发、自动重连、热插拔检测等功能
 */
@Service(Service.Level.PROJECT)
class SerialPortService(private val project: Project) {
    
    private var currentPort: SerialPort? = null
    private var isConnected = false
    private var autoReconnect = false
    private var reconnectThread: Thread? = null
    private val listeners = CopyOnWriteArrayList<SerialPortListener>()
    private var receiveThread: Thread? = null
    
    // 串口参数
    private var baudRate = 115200
    private var dataBits = 8
    private var stopBits = SerialPort.ONE_STOP_BIT
    private var parity = SerialPort.NO_PARITY
    private var flowControl = SerialPort.FLOW_CONTROL_DISABLED
    private var sendNewlineMode = 3  // CR+LF
    private var receiveNewlineMode = 0  // Auto
    
    // 数据接收配置
    private var lineEndingMode = LineEndingMode.AUTO
    private var readTimeout = 50
    private var interByteTimeout = 20
    
    // 串口热插拔检测
    private var portScanTimer: Timer? = null
    private var lastKnownPorts: Set<String> = emptySet()
    private val portChangeListeners = CopyOnWriteArrayList<PortChangeListener>()
    
    /**
     * 行结束符模式
     */
    enum class LineEndingMode {
        NONE, CR, LF, CRLF, AUTO
    }
    
    init {
        // 启动串口热插拔检测
        startPortScanning()
    }
    
    /**
     * 启动串口扫描 (热插拔检测)
     */
    private fun startPortScanning() {
        lastKnownPorts = SerialPort.getCommPorts().map { it.systemPortName }.toSet()
        
        portScanTimer = Timer("SerialPort-Scanner", true)
        portScanTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                checkForPortChanges()
            }
        }, 2000, 2000)  // 每2秒扫描一次
    }
    
    /**
     * 检测串口变化
     */
    private fun checkForPortChanges() {
        try {
            val currentPorts = SerialPort.getCommPorts().map { it.systemPortName }.toSet()
            
            if (currentPorts != lastKnownPorts) {
                val addedPorts = currentPorts - lastKnownPorts
                val removedPorts = lastKnownPorts - currentPorts
                
                lastKnownPorts = currentPorts
                
                // 通知监听器
                if (addedPorts.isNotEmpty() || removedPorts.isNotEmpty()) {
                    portChangeListeners.forEach { listener ->
                        addedPorts.forEach { port -> listener.onPortAdded(port) }
                        removedPorts.forEach { port -> listener.onPortRemoved(port) }
                    }
                }
                
                // 如果当前连接的串口被移除，处理断开
                currentPort?.let { port ->
                    if (port.systemPortName in removedPorts) {
                        handleDisconnection()
                    }
                }
            }
        } catch (e: Exception) {
            // 扫描出错，忽略
        }
    }
    
    /**
     * 添加串口变化监听器
     */
    fun addPortChangeListener(listener: PortChangeListener) {
        portChangeListeners.add(listener)
    }
    
    /**
     * 移除串口变化监听器
     */
    fun removePortChangeListener(listener: PortChangeListener) {
        portChangeListeners.remove(listener)
    }
    
    /**
     * 获取所有可用串口 (USB串口优先排序)
     */
    fun getAvailablePorts(): List<String> {
        return SerialPort.getCommPorts()
            .map { it.systemPortName }
            .sortedWith(compareBy(
                // 优先级：USB > 其他 > Bluetooth
                { name -> 
                    when {
                        name.contains("usb", ignoreCase = true) -> 0
                        name.contains("serial", ignoreCase = true) -> 1
                        name.contains("modem", ignoreCase = true) -> 1
                        name.contains("Bluetooth", ignoreCase = true) -> 9
                        else -> 5
                    }
                },
                { it }  // 同优先级按名称排序
            ))
    }
    
    /**
     * 获取串口详细信息
     */
    fun getPortsWithInfo(): List<PortInfo> {
        return SerialPort.getCommPorts().map { port ->
            PortInfo(
                name = port.systemPortName,
                description = port.portDescription ?: "",
                manufacturer = ""  // jSerialComm doesn't provide manufacturer info
            )
        }
    }
    
    /**
     * 连接串口
     */
    fun connect(portName: String, baudRate: Int = 115200): Boolean {
        try {
            disconnect()
            
            // 提取纯端口名（去除描述信息，如 "COM14 (FT232R USB UART)" -> "COM14"）
            val purePortName = portName.split(" ").firstOrNull() ?: portName
            
            val ports = SerialPort.getCommPorts()
            val port = ports.firstOrNull { it.systemPortName == purePortName }
                ?: throw IOException("Port $purePortName not found. Available ports: ${ports.map { it.systemPortName }}")
            
            this.baudRate = baudRate
            port.baudRate = baudRate
            port.numDataBits = dataBits
            port.numStopBits = stopBits
            port.parity = parity
            
            // 优化超时设置
            port.setComPortTimeouts(
                SerialPort.TIMEOUT_READ_SEMI_BLOCKING,
                readTimeout,
                0
            )
            
            if (!port.openPort()) {
                throw IOException("Failed to open port $portName")
            }
            
            currentPort = port
            isConnected = true
            
            startReceiveThread()
            notifyConnectionChanged(true, portName)
            return true
            
        } catch (e: Exception) {
            notifyError("Connection failed: ${e.message}")
            return false
        }
    }
    
    /**
     * 断开串口
     */
    fun disconnect() {
        autoReconnect = false
        reconnectThread?.interrupt()
        reconnectThread = null
        
        receiveThread?.interrupt()
        receiveThread = null
        
        currentPort?.let { port ->
            val portName = port.systemPortName
            if (port.isOpen) {
                port.closePort()
            }
            notifyConnectionChanged(false, portName)
        }
        
        currentPort = null
        isConnected = false
    }
    
    /**
     * 发送数据 (ASCII)
     */
    fun sendData(data: String): Boolean {
        return try {
            val port = currentPort ?: throw IOException("Port not connected")
            val bytes = data.toByteArray(Charsets.UTF_8)
            port.writeBytes(bytes, bytes.size)
            notifyDataSent(data, DataFormat.ASCII)
            true
        } catch (e: Exception) {
            notifyError("Send failed: ${e.message}")
            false
        }
    }
    
    /**
     * 发送数据 (HEX)
     */
    fun sendHexData(hexString: String): Boolean {
        return try {
            val port = currentPort ?: throw IOException("Port not connected")
            val bytes = hexStringToByteArray(hexString)
            port.writeBytes(bytes, bytes.size)
            notifyDataSent(hexString, DataFormat.HEX)
            true
        } catch (e: Exception) {
            notifyError("Send failed: ${e.message}")
            false
        }
    }
    
    /**
     * 发送数据带换行符
     */
    fun sendDataWithNewline(data: String, newline: String = "\r\n"): Boolean {
        return sendData(data + newline)
    }
    
    /**
     * 设置自动重连
     */
    fun setAutoReconnect(enabled: Boolean) {
        autoReconnect = enabled
        if (enabled && !isConnected) {
            startAutoReconnect()
        }
    }
    
    /**
     * 配置串口参数
     */
    fun configurePort(baudRate: Int, dataBits: Int, stopBits: Int, parity: Int) {
        this.baudRate = baudRate
        this.dataBits = dataBits
        this.stopBits = stopBits
        this.parity = parity
        
        currentPort?.let { port ->
            port.baudRate = baudRate
            port.numDataBits = dataBits
            port.numStopBits = stopBits
            port.parity = parity
        }
    }
    
    /**
     * 设置行结束符模式
     */
    fun setLineEndingMode(mode: LineEndingMode) {
        this.lineEndingMode = mode
    }
    
    /**
     * 添加监听器
     */
    fun addListener(listener: SerialPortListener) {
        listeners.add(listener)
    }
    
    /**
     * 移除监听器
     */
    fun removeListener(listener: SerialPortListener) {
        listeners.remove(listener)
    }
    
    /**
     * 获取连接状态
     */
    fun isConnected(): Boolean = isConnected
    
    /**
     * 获取当前端口名
     */
    fun getCurrentPortName(): String? = currentPort?.systemPortName
    
    /**
     * 停止服务 (清理资源)
     */
    fun dispose() {
        portScanTimer?.cancel()
        portScanTimer = null
        disconnect()
    }
    
    // ========== 私有方法 ==========
    
    private fun startReceiveThread() {
        receiveThread = Thread {
            val readBuffer = ByteArray(4096)
            val accumulateBuffer = ByteArrayOutputStream()
            var lastReadTime = System.currentTimeMillis()
            
            while (isConnected && !Thread.currentThread().isInterrupted) {
                try {
                    val port = currentPort ?: break
                    val available = port.bytesAvailable()
                    
                    if (available > 0) {
                        val numRead = port.readBytes(readBuffer, minOf(available, readBuffer.size))
                        if (numRead > 0) {
                            accumulateBuffer.write(readBuffer, 0, numRead)
                            lastReadTime = System.currentTimeMillis()
                        }
                    } else if (accumulateBuffer.size() > 0) {
                        val elapsed = System.currentTimeMillis() - lastReadTime
                        
                        if (elapsed >= interByteTimeout) {
                            val data = accumulateBuffer.toByteArray()
                            accumulateBuffer.reset()
                            processReceivedData(data)
                        }
                    }
                    
                    if (available <= 0) {
                        Thread.sleep(5)
                    }
                    
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    if (isConnected) {
                        notifyError("Receive error: ${e.message}")
                        handleDisconnection()
                    }
                    break
                }
            }
            
            if (accumulateBuffer.size() > 0) {
                val data = accumulateBuffer.toByteArray()
                processReceivedData(data)
            }
            
        }.apply {
            isDaemon = true
            name = "SerialPort-Receive-Thread"
            start()
        }
    }
    
    private fun processReceivedData(data: ByteArray) {
        when (lineEndingMode) {
            LineEndingMode.NONE -> notifyDataReceived(data)
            else -> splitAndNotify(data)
        }
    }
    
    private fun splitAndNotify(data: ByteArray) {
        val hasNewline = data.any { it == '\n'.code.toByte() || it == '\r'.code.toByte() }
        
        if (!hasNewline) {
            notifyDataReceived(data)
            return
        }
        
        val lines = mutableListOf<ByteArray>()
        var start = 0
        var i = 0
        
        while (i < data.size) {
            when {
                data[i] == '\r'.code.toByte() && i + 1 < data.size && data[i + 1] == '\n'.code.toByte() -> {
                    if (i > start) lines.add(data.copyOfRange(start, i))
                    i += 2
                    start = i
                }
                data[i] == '\r'.code.toByte() || data[i] == '\n'.code.toByte() -> {
                    if (i > start) lines.add(data.copyOfRange(start, i))
                    i++
                    start = i
                }
                else -> i++
            }
        }
        
        if (start < data.size) {
            lines.add(data.copyOfRange(start, data.size))
        }
        
        lines.forEach { line ->
            if (line.isNotEmpty()) notifyDataReceived(line)
        }
    }
    
    private fun startAutoReconnect() {
        val portName = currentPort?.systemPortName ?: return
        
        reconnectThread = Thread {
            while (autoReconnect && !Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(5000)
                    if (!isConnected && autoReconnect) {
                        connect(portName, baudRate)
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) { }
            }
        }.apply {
            isDaemon = true
            name = "SerialPort-AutoReconnect-Thread"
            start()
        }
    }
    
    private fun handleDisconnection() {
        val portName = currentPort?.systemPortName
        isConnected = false
        currentPort?.closePort()
        
        portName?.let { notifyConnectionChanged(false, it) }
        
        if (autoReconnect) startAutoReconnect()
    }
    
    private fun hexStringToByteArray(hexString: String): ByteArray {
        val cleaned = hexString.replace("\\s".toRegex(), "")
        val len = cleaned.length
        val data = ByteArray(len / 2)
        
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(cleaned[i], 16) shl 4)
                    + Character.digit(cleaned[i + 1], 16)).toByte()
        }
        return data
    }
    
    // ========== 通知方法 ==========
    
    private fun notifyConnectionChanged(connected: Boolean, portName: String) {
        listeners.forEach { it.onConnectionChanged(connected, portName) }
    }
    
    private fun notifyDataReceived(data: ByteArray) {
        val timestamp = getCurrentTimestamp()
        listeners.forEach { it.onDataReceived(data, timestamp) }
    }
    
    private fun notifyDataSent(data: String, format: DataFormat) {
        val timestamp = getCurrentTimestamp()
        listeners.forEach { it.onDataSent(data, format, timestamp) }
    }
    
    private fun notifyError(message: String) {
        listeners.forEach { it.onError(message) }
    }
    
    private fun getCurrentTimestamp(): String {
        // Logcat 风格时间戳: MM-dd HH:mm:ss.SSS
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS"))
    }
    
    /**
     * 获取当前配置
     */
    fun getConfig(): SerialConfig {
        return SerialConfig(
            dataBits = dataBits,
            stopBits = stopBits,
            parity = parity,
            flowControl = flowControl,
            sendNewlineMode = sendNewlineMode,
            receiveNewlineMode = receiveNewlineMode
        )
    }
    
    /**
     * 更新配置
     */
    fun updateConfig(
        dataBits: Int = this.dataBits,
        stopBits: Int = this.stopBits,
        parity: Int = this.parity,
        flowControl: Int = this.flowControl,
        sendNewlineMode: Int = this.sendNewlineMode,
        receiveNewlineMode: Int = this.receiveNewlineMode
    ) {
        this.dataBits = dataBits
        this.stopBits = stopBits
        this.parity = parity
        this.flowControl = flowControl
        this.sendNewlineMode = sendNewlineMode
        this.receiveNewlineMode = receiveNewlineMode
        
        // 如果已连接，更新串口参数
        currentPort?.let { port ->
            if (port.isOpen) {
                port.setComPortParameters(baudRate, dataBits, stopBits, parity)
                port.setFlowControl(flowControl)
            }
        }
    }
    
    /**
     * 获取发送换行符
     */
    fun getSendNewline(): String {
        return when (sendNewlineMode) {
            0 -> ""       // 无
            1 -> "\r"     // CR
            2 -> "\n"     // LF
            3 -> "\r\n"   // CR+LF
            else -> ""
        }
    }
}

/**
 * 串口监听器接口
 */
interface SerialPortListener {
    fun onConnectionChanged(connected: Boolean, portName: String)
    fun onDataReceived(data: ByteArray, timestamp: String)
    fun onDataSent(data: String, format: DataFormat, timestamp: String)
    fun onError(message: String)
}

/**
 * 串口变化监听器接口
 */
interface PortChangeListener {
    fun onPortAdded(portName: String)
    fun onPortRemoved(portName: String)
}

/**
 * 串口信息
 */
data class PortInfo(
    val name: String,
    val description: String,
    val manufacturer: String
)

/**
 * 数据格式枚举
 */
enum class DataFormat {
    ASCII, HEX
}

/**
 * 串口配置数据类
 */
data class SerialConfig(
    val dataBits: Int = 8,
    val stopBits: Int = com.fazecast.jSerialComm.SerialPort.ONE_STOP_BIT,
    val parity: Int = com.fazecast.jSerialComm.SerialPort.NO_PARITY,
    val flowControl: Int = com.fazecast.jSerialComm.SerialPort.FLOW_CONTROL_DISABLED,
    val sendNewlineMode: Int = 3,  // CR+LF
    val receiveNewlineMode: Int = 0  // Auto
)
