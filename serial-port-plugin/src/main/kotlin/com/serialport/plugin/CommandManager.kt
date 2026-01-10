package com.serialport.plugin

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * 快捷指令管理器
 * 负责保存和管理常用串口命令
 */
@Service(Service.Level.PROJECT)
@State(
    name = "SerialPortCommands",
    storages = [Storage("serialPortCommands.xml")]
)
class CommandManager : PersistentStateComponent<CommandManager.State> {
    
    private var state = State()
    
    data class State(
        var commands: MutableList<SerialCommand> = mutableListOf()
    )
    
    data class SerialCommand(
        var name: String = "",
        var command: String = "",
        var isHex: Boolean = false,
        var description: String = ""
    )
    
    override fun getState(): State = state
    
    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
    }
    
    /**
     * 获取所有命令
     */
    fun getAllCommands(): List<SerialCommand> {
        return state.commands.toList()
    }
    
    /**
     * 添加命令
     */
    fun addCommand(name: String, command: String, isHex: Boolean, description: String = ""): Boolean {
        // 检查是否已存在同名命令
        if (state.commands.any { it.name == name }) {
            return false
        }
        
        state.commands.add(SerialCommand(name, command, isHex, description))
        return true
    }
    
    /**
     * 更新命令
     */
    fun updateCommand(oldName: String, name: String, command: String, isHex: Boolean, description: String = ""): Boolean {
        val index = state.commands.indexOfFirst { it.name == oldName }
        if (index == -1) return false
        
        // 如果改名,检查新名称是否冲突
        if (oldName != name && state.commands.any { it.name == name }) {
            return false
        }
        
        state.commands[index] = SerialCommand(name, command, isHex, description)
        return true
    }
    
    /**
     * 删除命令
     */
    fun deleteCommand(name: String): Boolean {
        return state.commands.removeIf { it.name == name }
    }
    
    /**
     * 获取指定命令
     */
    fun getCommand(name: String): SerialCommand? {
        return state.commands.find { it.name == name }
    }
    
    /**
     * 清空所有命令
     */
    fun clearAll() {
        state.commands.clear()
    }
}
