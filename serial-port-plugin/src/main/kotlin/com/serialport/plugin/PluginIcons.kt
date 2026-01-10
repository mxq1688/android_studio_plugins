package com.serialport.plugin

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * 插件图标定义
 */
object PluginIcons {
    /**
     * 串口工具窗口图标 (13x13)
     * 自动适配亮色/暗色主题
     */
    @JvmField
    val SERIAL_PORT: Icon = IconLoader.getIcon("/icons/serialPort.svg", PluginIcons::class.java)
    
    /**
     * 连接状态图标
     */
    @JvmField  
    val CONNECTED: Icon = IconLoader.getIcon("/icons/serialPort.svg", PluginIcons::class.java)
    
    @JvmField
    val DISCONNECTED: Icon = IconLoader.getIcon("/icons/serialPort.svg", PluginIcons::class.java)
}
