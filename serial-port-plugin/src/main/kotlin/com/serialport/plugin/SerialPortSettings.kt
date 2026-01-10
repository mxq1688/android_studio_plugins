package com.serialport.plugin

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * 串口配置持久化
 * 保存用户的串口设置和偏好
 */
@Service(Service.Level.PROJECT)
@State(
    name = "SerialPortSettings",
    storages = [Storage("serialPortSettings.xml")]
)
class SerialPortSettings : PersistentStateComponent<SerialPortSettings.State> {
    
    private var state = State()
    
    data class State(
        // 连接设置
        var lastPortName: String = "",
        var lastBaudRate: Int = 9600,
        var dataBits: Int = 8,
        var stopBits: Int = 1,
        var parity: Int = 0,
        var autoReconnect: Boolean = false,
        
        // 显示设置
        var hexMode: Boolean = false,
        var showTimestamp: Boolean = true,
        var autoScroll: Boolean = true,
        
        // 窗口设置
        var windowWidth: Int = 800,
        var windowHeight: Int = 600
    )
    
    override fun getState(): State = state
    
    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
    }
    
    // ========== 连接设置 ==========
    
    fun getLastPortName(): String = state.lastPortName
    fun setLastPortName(portName: String) {
        state.lastPortName = portName
    }
    
    fun getLastBaudRate(): Int = state.lastBaudRate
    fun setLastBaudRate(baudRate: Int) {
        state.lastBaudRate = baudRate
    }
    
    fun getDataBits(): Int = state.dataBits
    fun setDataBits(dataBits: Int) {
        state.dataBits = dataBits
    }
    
    fun getStopBits(): Int = state.stopBits
    fun setStopBits(stopBits: Int) {
        state.stopBits = stopBits
    }
    
    fun getParity(): Int = state.parity
    fun setParity(parity: Int) {
        state.parity = parity
    }
    
    fun getAutoReconnect(): Boolean = state.autoReconnect
    fun setAutoReconnect(enabled: Boolean) {
        state.autoReconnect = enabled
    }
    
    // ========== 显示设置 ==========
    
    fun getHexMode(): Boolean = state.hexMode
    fun setHexMode(enabled: Boolean) {
        state.hexMode = enabled
    }
    
    fun getShowTimestamp(): Boolean = state.showTimestamp
    fun setShowTimestamp(enabled: Boolean) {
        state.showTimestamp = enabled
    }
    
    fun getAutoScroll(): Boolean = state.autoScroll
    fun setAutoScroll(enabled: Boolean) {
        state.autoScroll = enabled
    }
    
    // ========== 窗口设置 ==========
    
    fun getWindowWidth(): Int = state.windowWidth
    fun setWindowWidth(width: Int) {
        state.windowWidth = width
    }
    
    fun getWindowHeight(): Int = state.windowHeight
    fun setWindowHeight(height: Int) {
        state.windowHeight = height
    }
}
