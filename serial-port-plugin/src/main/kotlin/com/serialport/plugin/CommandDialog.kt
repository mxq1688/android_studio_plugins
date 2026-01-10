package com.serialport.plugin

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import java.awt.*
import javax.swing.*
import javax.swing.event.ListSelectionEvent

/**
 * 快捷指令管理对话框
 */
class CommandDialog(
    private val project: Project,
    private val commandManager: CommandManager,
    private val onCommandSelected: (String) -> Unit
) : DialogWrapper(project) {
    
    private val commandListModel = DefaultListModel<String>()
    private val commandList = JBList(commandListModel)
    
    private val nameField = JTextField(20)
    private val commandField = JTextField(30)
    private val hexCheckBox = JCheckBox("HEX格式")
    private val descriptionField = JTextField(30)
    
    private val addButton = JButton("添加")
    private val updateButton = JButton("更新")
    private val deleteButton = JButton("删除")
    private val executeButton = JButton("执行")
    
    private var selectedCommandName: String? = null
    
    init {
        title = "快捷指令管理"
        init()
        loadCommands()
        setupListeners()
        updateButtonStates()
    }
    
    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout(10, 10))
        mainPanel.preferredSize = Dimension(700, 450)
        
        // 左侧列表
        val listPanel = createListPanel()
        mainPanel.add(listPanel, BorderLayout.WEST)
        
        // 右侧编辑区
        val editPanel = createEditPanel()
        mainPanel.add(editPanel, BorderLayout.CENTER)
        
        return mainPanel
    }
    
    private fun createListPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(200, 400)
        
        panel.add(JLabel("已保存的指令:"), BorderLayout.NORTH)
        
        commandList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        val scrollPane = JBScrollPane(commandList)
        panel.add(scrollPane, BorderLayout.CENTER)
        
        // 底部按钮
        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        deleteButton.isEnabled = false
        executeButton.isEnabled = false
        buttonPanel.add(deleteButton)
        buttonPanel.add(executeButton)
        panel.add(buttonPanel, BorderLayout.SOUTH)
        
        return panel
    }
    
    private fun createEditPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints()
        gbc.insets = Insets(5, 5, 5, 5)
        gbc.anchor = GridBagConstraints.WEST
        
        // 名称
        gbc.gridx = 0
        gbc.gridy = 0
        panel.add(JLabel("名称:"), gbc)
        
        gbc.gridx = 1
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        panel.add(nameField, gbc)
        
        // 命令
        gbc.gridx = 0
        gbc.gridy = 1
        gbc.fill = GridBagConstraints.NONE
        gbc.weightx = 0.0
        panel.add(JLabel("命令:"), gbc)
        
        gbc.gridx = 1
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        panel.add(commandField, gbc)
        
        // HEX模式
        gbc.gridx = 1
        gbc.gridy = 2
        panel.add(hexCheckBox, gbc)
        
        // 描述
        gbc.gridx = 0
        gbc.gridy = 3
        gbc.fill = GridBagConstraints.NONE
        gbc.weightx = 0.0
        panel.add(JLabel("描述:"), gbc)
        
        gbc.gridx = 1
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        panel.add(descriptionField, gbc)
        
        // 按钮
        gbc.gridx = 1
        gbc.gridy = 4
        gbc.fill = GridBagConstraints.NONE
        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        buttonPanel.add(addButton)
        buttonPanel.add(updateButton)
        panel.add(buttonPanel, gbc)
        
        // 说明文本
        gbc.gridx = 0
        gbc.gridy = 5
        gbc.gridwidth = 2
        gbc.fill = GridBagConstraints.BOTH
        gbc.weighty = 1.0
        
        val helpText = JTextArea(
            """使用说明:
            |1. 输入命令名称和命令内容
            |2. 选择HEX格式(如需要)
            |3. 点击"添加"保存命令
            |4. 从左侧列表选择命令后可"更新"或"删除"
            |5. 点击"执行"发送选中的命令
            |
            |示例:
            |名称: 查询版本
            |命令: AT+VER?
            |描述: 查询模块版本号
            """.trimMargin()
        )
        helpText.isEditable = false
        helpText.background = panel.background
        helpText.font = Font("Monospaced", Font.PLAIN, 11)
        panel.add(JBScrollPane(helpText), gbc)
        
        return panel
    }
    
    private fun setupListeners() {
        // 列表选择
        commandList.addListSelectionListener { e: ListSelectionEvent ->
            if (!e.valueIsAdjusting) {
                val selected = commandList.selectedValue
                if (selected != null) {
                    loadCommandToForm(selected)
                    selectedCommandName = selected
                } else {
                    selectedCommandName = null
                }
                updateButtonStates()
            }
        }
        
        // 添加
        addButton.addActionListener {
            val name = nameField.text.trim()
            val command = commandField.text.trim()
            
            if (name.isEmpty() || command.isEmpty()) {
                JOptionPane.showMessageDialog(
                    contentPanel,
                    "名称和命令不能为空",
                    "错误",
                    JOptionPane.ERROR_MESSAGE
                )
                return@addActionListener
            }
            
            val success = commandManager.addCommand(
                name,
                command,
                hexCheckBox.isSelected,
                descriptionField.text.trim()
            )
            
            if (success) {
                loadCommands()
                clearForm()
                JOptionPane.showMessageDialog(
                    contentPanel,
                    "命令已添加",
                    "成功",
                    JOptionPane.INFORMATION_MESSAGE
                )
            } else {
                JOptionPane.showMessageDialog(
                    contentPanel,
                    "命令名称已存在",
                    "错误",
                    JOptionPane.ERROR_MESSAGE
                )
            }
        }
        
        // 更新
        updateButton.addActionListener {
            val oldName = selectedCommandName
            if (oldName == null) {
                JOptionPane.showMessageDialog(
                    contentPanel,
                    "请先选择要更新的命令",
                    "错误",
                    JOptionPane.ERROR_MESSAGE
                )
                return@addActionListener
            }
            
            val name = nameField.text.trim()
            val command = commandField.text.trim()
            
            if (name.isEmpty() || command.isEmpty()) {
                JOptionPane.showMessageDialog(
                    contentPanel,
                    "名称和命令不能为空",
                    "错误",
                    JOptionPane.ERROR_MESSAGE
                )
                return@addActionListener
            }
            
            val success = commandManager.updateCommand(
                oldName,
                name,
                command,
                hexCheckBox.isSelected,
                descriptionField.text.trim()
            )
            
            if (success) {
                loadCommands()
                selectedCommandName = name
                JOptionPane.showMessageDialog(
                    contentPanel,
                    "命令已更新",
                    "成功",
                    JOptionPane.INFORMATION_MESSAGE
                )
            } else {
                JOptionPane.showMessageDialog(
                    contentPanel,
                    "更新失败,可能是新名称已存在",
                    "错误",
                    JOptionPane.ERROR_MESSAGE
                )
            }
        }
        
        // 删除
        deleteButton.addActionListener {
            val name = selectedCommandName
            if (name == null) return@addActionListener
            
            val confirm = JOptionPane.showConfirmDialog(
                contentPanel,
                "确定要删除命令 \"$name\" 吗?",
                "确认删除",
                JOptionPane.YES_NO_OPTION
            )
            
            if (confirm == JOptionPane.YES_OPTION) {
                commandManager.deleteCommand(name)
                loadCommands()
                clearForm()
                selectedCommandName = null
                updateButtonStates()
            }
        }
        
        // 执行
        executeButton.addActionListener {
            val name = selectedCommandName ?: return@addActionListener
            val cmd = commandManager.getCommand(name) ?: return@addActionListener
            
            // 关闭对话框并执行命令
            close(OK_EXIT_CODE)
            onCommandSelected(cmd.command)
        }
    }
    
    private fun loadCommands() {
        commandListModel.clear()
        commandManager.getAllCommands().forEach {
            commandListModel.addElement(it.name)
        }
    }
    
    private fun loadCommandToForm(name: String) {
        val command = commandManager.getCommand(name) ?: return
        nameField.text = command.name
        commandField.text = command.command
        hexCheckBox.isSelected = command.isHex
        descriptionField.text = command.description
    }
    
    private fun clearForm() {
        nameField.text = ""
        commandField.text = ""
        hexCheckBox.isSelected = false
        descriptionField.text = ""
        commandList.clearSelection()
    }
    
    private fun updateButtonStates() {
        val hasSelection = selectedCommandName != null
        deleteButton.isEnabled = hasSelection
        executeButton.isEnabled = hasSelection
        updateButton.isEnabled = hasSelection
    }
}
