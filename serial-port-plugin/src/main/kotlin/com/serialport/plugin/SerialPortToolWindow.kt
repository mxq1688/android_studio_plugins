package com.serialport.plugin

import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBList
import com.intellij.ui.JBColor
import java.awt.*
import java.awt.event.*
import java.io.File
import java.nio.charset.StandardCharsets
import javax.swing.*
import javax.swing.text.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.DefaultListCellRenderer

/**
 * 串口工具窗口工厂
 */
class SerialPortToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val content = SerialPortToolWindow(project)
        val contentFactory = ContentFactory.getInstance()
        val tabContent = contentFactory.createContent(content.getContent(), "", false)
        toolWindow.contentManager.addContent(tabContent)
    }
}

/**
 * 串口工具窗口主界面 - Logcat 风格
 */
class SerialPortToolWindow(private val project: Project) : SerialPortListener, PortChangeListener, LogFilterListener {
    
    private val serialService = project.service<SerialPortService>()
    private val commandManager = project.service<CommandManager>()
    private val filterManager = LogFilterManager()
    
    // UI Components
    private val mainPanel = JPanel(BorderLayout(0, 0))
    
    // 顶部栏组件
    private val portComboBox = JComboBox<String>()
    private val baudRateComboBox = JComboBox(arrayOf(
        "300", "600", "1200", "2400", "4800", "9600", "14400", "19200", 
        "28800", "38400", "56000", "57600", "115200", "128000", "230400", 
        "256000", "460800", "500000", "921600", "1000000", "1500000", "2000000"
    ))
    private val connectButton = JButton()
    
    // Logcat 风格过滤器
    private val filterField = JTextField(40)
    private var filterPopup: JBPopup? = null
    
    // 日志显示区
    private val logArea = JTextPane()
    private val logDocument: StyledDocument = logArea.styledDocument
    
    // 发送区
    private val sendField = JTextField()
    private val hexModeCheckBox = JCheckBox("HEX")
    
    // 状态
    private var isConnected = false
    private var autoScroll = true
    private var isPaused = false  // 暂停日志更新
    private var showTimestamp = true
    private var displayHex = false
    
    // UI 引用 (用于状态同步)
    private var autoScrollBtn: JButton? = null
    private var pauseBtn: JButton? = null
    private var logScrollPane: JBScrollPane? = null
    
    // Logcat 风格文本样式
    private val verboseStyle: Style
    private val debugStyle: Style
    private val infoStyle: Style
    private val warnStyle: Style
    private val errorStyle: Style
    
    // Logcat 风格过滤器语法提示
    private val filterSuggestions = listOf(
        // 消息过滤
        FilterSuggestion("message:", "Log message contains string", "message:error"),
        FilterSuggestion("message=:", "Log message is exactly string", "message=:OK"),
        FilterSuggestion("message~:", "Log message matches regex", "message~:err.*"),
        FilterSuggestion("-message:", "Log message does not contain string", "-message:debug"),
        FilterSuggestion("-message=:", "Log message is not exactly string", "-message=:OK"),
        FilterSuggestion("-message~:", "Log message does not match regex", "-message~:err.*"),
        // 方向过滤
        FilterSuggestion("dir:", "Filter by direction (TX/RX/SYS)", "dir:RX"),
        FilterSuggestion("-dir:", "Exclude direction", "-dir:SYS"),
        // 级别过滤
        FilterSuggestion("level:", "Minimum log level (V/D/I/W/E)", "level:W")
    )
    
    data class FilterSuggestion(val syntax: String, val description: String, val example: String)
    
    init {
        // 初始化 Logcat 风格样式
        val defaultStyle = StyleContext.getDefaultStyleContext().getStyle(StyleContext.DEFAULT_STYLE)
        
        // V - Verbose: 灰色
        verboseStyle = logArea.addStyle("VERBOSE", defaultStyle)
        StyleConstants.setForeground(verboseStyle, JBColor(Color(128, 128, 128), Color(150, 150, 150)))
        
        // D - Debug: 蓝色
        debugStyle = logArea.addStyle("DEBUG", defaultStyle)
        StyleConstants.setForeground(debugStyle, JBColor(Color(0, 102, 204), Color(86, 156, 214)))
        
        // I - Info: 绿色
        infoStyle = logArea.addStyle("INFO", defaultStyle)
        StyleConstants.setForeground(infoStyle, JBColor(Color(0, 128, 0), Color(78, 201, 176)))
        
        // W - Warn: 橙色/黄色
        warnStyle = logArea.addStyle("WARN", defaultStyle)
        StyleConstants.setForeground(warnStyle, JBColor(Color(187, 134, 0), Color(220, 180, 80)))
        
        // E - Error: 红色
        errorStyle = logArea.addStyle("ERROR", defaultStyle)
        StyleConstants.setForeground(errorStyle, JBColor(Color(187, 0, 0), Color(244, 108, 108)))
        
        setupUI()
        setupListeners()
        serialService.addListener(this)
        serialService.addPortChangeListener(this)
        filterManager.addListener(this)
        refreshPortList()
    }
    
    fun getContent(): JComponent = mainPanel
    
    private fun setupUI() {
        mainPanel.background = JBColor.background()
        
        // === 顶部栏 ===
        val topBar = createTopBar()
        mainPanel.add(topBar, BorderLayout.NORTH)
        
        // === 主内容区 ===
        val contentPanel = JPanel(BorderLayout(0, 0))
        
        // 左侧工具栏
        val leftToolbar = createLeftToolbar()
        contentPanel.add(leftToolbar, BorderLayout.WEST)
        
        // 日志显示区
        val logPanel = createLogPanel()
        contentPanel.add(logPanel, BorderLayout.CENTER)
        
        mainPanel.add(contentPanel, BorderLayout.CENTER)
        
        // === 底部发送栏 ===
        val bottomBar = createBottomBar()
        mainPanel.add(bottomBar, BorderLayout.SOUTH)
    }
    
    /**
     * 创建顶部栏 - Logcat 风格
     */
    private fun createTopBar(): JPanel {
        val panel = JPanel(BorderLayout(8, 0))
        panel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
            BorderFactory.createEmptyBorder(6, 8, 6, 8)
        )
        panel.background = JBColor.background()
        
        // 左侧 - 设备选择
        val devicePanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        devicePanel.isOpaque = false
        
        portComboBox.preferredSize = Dimension(220, 28)
        portComboBox.minimumSize = Dimension(180, 28)
        portComboBox.toolTipText = "选择串口 (自动检测新设备)"
        // 设置下拉框渲染器，显示完整名称
        portComboBox.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
            ): Component {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                toolTipText = value?.toString()  // 悬停显示完整名称
                return this
            }
        }
        devicePanel.add(portComboBox)
        
        val refreshBtn = createSmallButton(AllIcons.Actions.Refresh, "刷新串口")
        refreshBtn.addActionListener { refreshPortList() }
        devicePanel.add(refreshBtn)
        
        baudRateComboBox.preferredSize = Dimension(100, 28)
        baudRateComboBox.selectedItem = "115200"
        baudRateComboBox.toolTipText = "波特率 (可手动输入)"
        baudRateComboBox.isEditable = true  // 支持手动输入波特率
        // 输入验证 - 只允许数字
        (baudRateComboBox.editor.editorComponent as? JTextField)?.let { editor ->
            editor.document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = validate()
                override fun removeUpdate(e: DocumentEvent) = validate()
                override fun changedUpdate(e: DocumentEvent) = validate()
                private fun validate() {
                    val text = editor.text
                    if (text.isNotEmpty() && !text.all { it.isDigit() }) {
                        SwingUtilities.invokeLater {
                            editor.text = text.filter { it.isDigit() }
                        }
                    }
                }
            })
        }
        devicePanel.add(baudRateComboBox)
        
        connectButton.preferredSize = Dimension(60, 28)
        updateConnectButton()
        devicePanel.add(connectButton)
        
        panel.add(devicePanel, BorderLayout.WEST)
        
        // 中间 - Logcat 风格过滤器
        val filterPanel = createFilterPanel()
        panel.add(filterPanel, BorderLayout.CENTER)
        
        return panel
    }
    
    /**
     * 创建 Logcat 风格过滤器面板
     */
    private fun createFilterPanel(): JPanel {
        val panel = JPanel(BorderLayout(4, 0))
        panel.isOpaque = false
        
        // 过滤器图标和输入框
        val inputPanel = JPanel(BorderLayout(4, 0))
        inputPanel.isOpaque = false
        inputPanel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor.border(), 1, true),
            BorderFactory.createEmptyBorder(0, 4, 0, 4)
        )
        inputPanel.background = JBColor.background()
        
        val filterIcon = JLabel(AllIcons.General.Filter)
        inputPanel.add(filterIcon, BorderLayout.WEST)
        
        filterField.border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
        filterField.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        filterField.putClientProperty("JTextField.placeholderText", "Filter logcat... (点击查看语法)")
        inputPanel.add(filterField, BorderLayout.CENTER)
        
        // 下拉箭头按钮 (显示语法提示)
        val dropdownBtn = createSmallButton(AllIcons.General.ArrowDown, "显示过滤语法")
        dropdownBtn.addActionListener { showFilterSuggestions() }
        inputPanel.add(dropdownBtn, BorderLayout.EAST)
        
        panel.add(inputPanel, BorderLayout.CENTER)
        
        // 右侧按钮
        val rightPanel = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0))
        rightPanel.isOpaque = false
        
        val clearBtn = createSmallButton(AllIcons.Actions.Close, "清除过滤 (Esc)")
        clearBtn.addActionListener { 
            filterField.text = ""
            applyFilter()
        }
        rightPanel.add(clearBtn)
        
        val helpBtn = createSmallButton(AllIcons.Actions.Help, "过滤语法帮助")
        helpBtn.addActionListener { showFilterHelp() }
        rightPanel.add(helpBtn)
        
        panel.add(rightPanel, BorderLayout.EAST)
        
        return panel
    }
    
    /**
     * 显示过滤器语法下拉提示 (Logcat 风格)
     */
    private fun showFilterSuggestions() {
        val listModel = DefaultListModel<String>()
        filterSuggestions.forEach { suggestion ->
            listModel.addElement("${suggestion.syntax}  →  ${suggestion.description}")
        }
        
        val list = JBList(listModel)
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        
        filterPopup = JBPopupFactory.getInstance()
            .createListPopupBuilder(list)
            .setTitle("过滤语法")
            .setMovable(false)
            .setResizable(false)
            .setRequestFocus(true)
            .setItemChosenCallback { selected ->
                val index = listModel.indexOf(selected)
                if (index >= 0 && index < filterSuggestions.size) {
                    val suggestion = filterSuggestions[index]
                    val currentText = filterField.text
                    val newText = if (currentText.isNotEmpty() && !currentText.endsWith(" ")) {
                        "$currentText ${suggestion.syntax}"
                    } else {
                        "$currentText${suggestion.syntax}"
                    }
                    filterField.text = newText
                    filterField.requestFocus()
                }
            }
            .createPopup()
        
        filterPopup?.showUnderneathOf(filterField)
    }
    
    /**
     * 创建左侧工具栏
     */
    private fun createLeftToolbar(): JPanel {
        val toolbar = JPanel()
        toolbar.layout = BoxLayout(toolbar, BoxLayout.Y_AXIS)
        toolbar.border = BorderFactory.createMatteBorder(0, 0, 0, 1, JBColor.border())
        toolbar.background = JBColor.background()
        toolbar.preferredSize = Dimension(32, 0)
        
        toolbar.add(Box.createVerticalStrut(4))
        
        // 清空日志
        val clearBtn = createToolbarButton(AllIcons.Actions.GC, "清空 (Ctrl+L)")
        clearBtn.addActionListener { filterManager.clearLogs() }
        toolbar.add(clearBtn)
        
        // 暂停/继续日志 (Logcat 风格)
        pauseBtn = createToggleButton(AllIcons.Actions.Pause, "暂停日志", isPaused)
        pauseBtn!!.addActionListener {
            isPaused = !isPaused
            updateToggleButton(pauseBtn!!, isPaused)
            if (!isPaused) {
                // 恢复时刷新显示并滚动到底部
                refreshDisplay()
            }
        }
        toolbar.add(pauseBtn)
        
        // 滚动到底部
        val scrollBtn = createToolbarButton(AllIcons.Actions.MoveDown, "滚动到底部")
        scrollBtn.addActionListener { 
            autoScroll = true
            updateToggleButton(autoScrollBtn!!, autoScroll)
            scrollToBottom() 
        }
        toolbar.add(scrollBtn)
        
        toolbar.add(Box.createVerticalStrut(8))
        toolbar.add(createHorizontalSeparator())
        toolbar.add(Box.createVerticalStrut(8))
        
        // 自动滚动
        autoScrollBtn = createToggleButton(AllIcons.Actions.SynchronizeScrolling, "自动滚动", autoScroll)
        autoScrollBtn!!.addActionListener {
            autoScroll = !autoScroll
            updateToggleButton(autoScrollBtn!!, autoScroll)
        }
        toolbar.add(autoScrollBtn)
        
        // 显示时间戳
        val timestampBtn = createToggleButton(AllIcons.Debugger.Watch, "时间戳", showTimestamp)
        timestampBtn.addActionListener {
            showTimestamp = !showTimestamp
            updateToggleButton(timestampBtn, showTimestamp)
            refreshDisplay()
        }
        toolbar.add(timestampBtn)
        
        // HEX 显示
        val hexBtn = createToggleButton(AllIcons.Debugger.Db_primitive, "HEX显示", displayHex)
        hexBtn.addActionListener {
            displayHex = !displayHex
            updateToggleButton(hexBtn, displayHex)
            refreshDisplay()
        }
        toolbar.add(hexBtn)
        
        toolbar.add(Box.createVerticalStrut(8))
        toolbar.add(createHorizontalSeparator())
        toolbar.add(Box.createVerticalStrut(8))
        
        // 导出
        val exportBtn = createToolbarButton(AllIcons.ToolbarDecorator.Export, "导出日志")
        exportBtn.addActionListener { exportLog() }
        toolbar.add(exportBtn)
        
        // 快捷指令
        val cmdBtn = createToolbarButton(AllIcons.Actions.Lightning, "快捷指令")
        cmdBtn.addActionListener { showCommandDialog() }
        toolbar.add(cmdBtn)
        
        toolbar.add(Box.createVerticalStrut(8))
        toolbar.add(createHorizontalSeparator())
        toolbar.add(Box.createVerticalStrut(8))
        
        // 设置
        val settingsBtn = createToolbarButton(AllIcons.General.Settings, "设置")
        settingsBtn.addActionListener { showSettings() }
        toolbar.add(settingsBtn)
        
        toolbar.add(Box.createVerticalGlue())
        
        return toolbar
    }
    
    private fun createToolbarButton(icon: Icon, tooltip: String): JButton {
        return JButton(icon).apply {
            preferredSize = Dimension(28, 28)
            maximumSize = Dimension(28, 28)
            minimumSize = Dimension(28, 28)
            toolTipText = tooltip
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusPainted = false
            alignmentX = Component.CENTER_ALIGNMENT
        }
    }
    
    private fun createToggleButton(icon: Icon, tooltip: String, selected: Boolean): JButton {
        return JButton(icon).apply {
            preferredSize = Dimension(28, 28)
            maximumSize = Dimension(28, 28)
            minimumSize = Dimension(28, 28)
            toolTipText = tooltip
            isBorderPainted = true
            isContentAreaFilled = false
            isFocusPainted = false
            alignmentX = Component.CENTER_ALIGNMENT
            updateToggleButton(this, selected)
        }
    }
    
    private fun updateToggleButton(button: JButton, selected: Boolean) {
        button.border = if (selected) {
            BorderFactory.createLineBorder(JBColor(Color(70, 130, 180), Color(100, 149, 237)), 2)
        } else {
            BorderFactory.createEmptyBorder(2, 2, 2, 2)
        }
    }
    
    private fun createSmallButton(icon: Icon, tooltip: String): JButton {
        return JButton(icon).apply {
            preferredSize = Dimension(24, 24)
            toolTipText = tooltip
            isBorderPainted = false
            isContentAreaFilled = false
        }
    }
    
    private fun createHorizontalSeparator(): JSeparator {
        return JSeparator(SwingConstants.HORIZONTAL).apply {
            maximumSize = Dimension(28, 1)
        }
    }
    
    /**
     * 创建日志显示区
     */
    private fun createLogPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        
        logArea.isEditable = false
        logArea.font = Font("JetBrains Mono", Font.PLAIN, 12).let { 
            if (it.family == "JetBrains Mono") it else Font(Font.MONOSPACED, Font.PLAIN, 12)
        }
        logArea.background = JBColor(Color(43, 43, 43), Color(43, 43, 43))
        logArea.foreground = JBColor(Color(187, 187, 187), Color(187, 187, 187))
        
        logScrollPane = JBScrollPane(logArea)
        logScrollPane!!.border = BorderFactory.createEmptyBorder()
        
        // 使用鼠标滚轮监听检测用户手动滚动
        logScrollPane!!.addMouseWheelListener { e ->
            if (e.wheelRotation < 0 && autoScroll) {
                // 用户向上滚动（wheelRotation < 0 表示向上）
                autoScroll = false
                autoScrollBtn?.let { updateToggleButton(it, autoScroll) }
            }
        }
        
        // 鼠标拖动滚动条也算手动滚动
        logScrollPane!!.verticalScrollBar.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent?) {
                if (autoScroll) {
                    autoScroll = false
                    autoScrollBtn?.let { updateToggleButton(it, autoScroll) }
                }
            }
        })
        
        panel.add(logScrollPane, BorderLayout.CENTER)
        return panel
    }
    
    /**
     * 创建底部发送栏
     */
    private fun createBottomBar(): JPanel {
        val panel = JPanel(BorderLayout(4, 0))
        panel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor.border()),
            BorderFactory.createEmptyBorder(4, 8, 4, 8)
        )
        panel.background = JBColor.background()
        
        val label = JLabel("TX:")
        label.foreground = JBColor(Color(0, 102, 204), Color(100, 149, 237))
        panel.add(label, BorderLayout.WEST)
        
        sendField.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor.border()),
            BorderFactory.createEmptyBorder(4, 6, 4, 6)
        )
        panel.add(sendField, BorderLayout.CENTER)
        
        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        buttonPanel.isOpaque = false
        
        hexModeCheckBox.toolTipText = "HEX模式发送"
        buttonPanel.add(hexModeCheckBox)
        
        val sendBtn = JButton("发送")
        sendBtn.preferredSize = Dimension(60, 28)
        sendBtn.addActionListener { sendData() }
        buttonPanel.add(sendBtn)
        
        panel.add(buttonPanel, BorderLayout.EAST)
        
        return panel
    }
    
    private fun setupListeners() {
        // 连接按钮
        connectButton.addActionListener {
            if (isConnected) {
                serialService.disconnect()
            } else {
                val portName = portComboBox.selectedItem as? String
                val baudRate = baudRateComboBox.selectedItem.toString().toInt()
                if (portName != null && portName != "无可用串口" && !portName.startsWith("--")) {
                    serialService.connect(portName, baudRate)
                } else {
                    showError("请选择有效串口")
                }
            }
        }
        
        // 发送
        sendField.addActionListener { sendData() }
        
        // 过滤器实时应用
        filterField.document.addDocumentListener(object : DocumentListener {
            private var timer: Timer? = null
            override fun insertUpdate(e: DocumentEvent) = scheduleFilter()
            override fun removeUpdate(e: DocumentEvent) = scheduleFilter()
            override fun changedUpdate(e: DocumentEvent) = scheduleFilter()
            
            private fun scheduleFilter() {
                timer?.stop()
                timer = Timer(200) { applyFilter() }.apply {
                    isRepeats = false
                    start()
                }
            }
        })
        
        // Esc 清除过滤
        filterField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ESCAPE) {
                    filterField.text = ""
                    applyFilter()
                } else if (e.keyCode == KeyEvent.VK_DOWN && filterField.text.isEmpty()) {
                    showFilterSuggestions()
                }
            }
        })
        
        // 快捷键
        setupKeyboardShortcuts()
    }
    
    private fun setupKeyboardShortcuts() {
        val inputMap = mainPanel.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        val actionMap = mainPanel.actionMap
        
        // Ctrl+L 清空
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_L, Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx), "clearLogs")
        actionMap.put("clearLogs", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) = filterManager.clearLogs()
        })
        
        // Ctrl+F 聚焦过滤
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_F, Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx), "focusFilter")
        actionMap.put("focusFilter", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                filterField.requestFocusInWindow()
                filterField.selectAll()
            }
        })
    }
    
    private fun updateConnectButton() {
        if (isConnected) {
            connectButton.text = "断开"
            connectButton.foreground = JBColor.RED
        } else {
            connectButton.text = "连接"
            connectButton.foreground = JBColor(Color(0, 153, 0), Color(80, 200, 80))
        }
    }
    
    private fun refreshPortList() {
        val currentSelection = portComboBox.selectedItem as? String
        
        // 直接使用 jSerialComm 检测串口并输出诊断信息
        try {
            val rawPorts = com.fazecast.jSerialComm.SerialPort.getCommPorts()
            val diagInfo = StringBuilder()
            diagInfo.append("[诊断] 系统: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}\n")
            diagInfo.append("[诊断] Java: ${System.getProperty("java.version")}\n")
            diagInfo.append("[诊断] jSerialComm 检测到 ${rawPorts.size} 个串口\n")
            rawPorts.forEach { port ->
                diagInfo.append("[诊断]   - ${port.systemPortName}: ${port.portDescription ?: "无描述"}\n")
            }
            println(diagInfo.toString())  // 输出到 IDE 控制台
            
            // 如果没有检测到串口，显示诊断信息在日志窗口
            if (rawPorts.isEmpty()) {
                javax.swing.SwingUtilities.invokeLater {
                    val doc = logArea.styledDocument
                    val style = logArea.addStyle("diag", null)
                    javax.swing.text.StyleConstants.setForeground(style, java.awt.Color.ORANGE)
                    doc.insertString(doc.length, diagInfo.toString(), style)
                }
            }
        } catch (e: Exception) {
            println("[诊断] jSerialComm 初始化失败: ${e.message}")
            e.printStackTrace()
        }
        
        val ports = serialService.getPortsWithInfo()
        
        portComboBox.removeAllItems()
        
        if (ports.isEmpty()) {
            portComboBox.addItem("无可用串口 (点击刷新)")
        } else {
            ports.forEach { port ->
                val displayName = if (port.description.isNotEmpty()) {
                    "${port.name} (${port.description})"
                } else {
                    port.name
                }
                portComboBox.addItem(displayName)
            }
            
            // 恢复之前的选择
            if (currentSelection != null) {
                for (i in 0 until portComboBox.itemCount) {
                    if (portComboBox.getItemAt(i)?.contains(currentSelection.split(" ")[0]) == true) {
                        portComboBox.selectedIndex = i
                        break
                    }
                }
            }
        }
    }
    
    private fun sendData() {
        val data = sendField.text
        if (data.isEmpty()) return
        
        val success = if (hexModeCheckBox.isSelected) {
            serialService.sendHexData(data)
        } else {
            // 添加配置的换行符
            val dataWithNewline = data + serialService.getSendNewline()
            serialService.sendData(dataWithNewline)
        }
        
        if (success) sendField.text = ""
    }
    
    private fun applyFilter() {
        val filter = parseFilterExpression(filterField.text)
        filterManager.setActiveFilter(filter)
        refreshDisplay()
    }
    
    private fun parseFilterExpression(expression: String): FilterCondition {
        if (expression.isEmpty()) return FilterCondition()
        
        var keyword = ""
        var isRegex = false
        var isExact = false
        var excludeKeyword = ""
        var excludeIsRegex = false
        var excludeIsExact = false
        var showTx = true
        var showRx = true
        var showSys = true
        var minLevel = LogLevel.VERBOSE
        
        val parts = expression.split("\\s+".toRegex())
        for (part in parts) {
            when {
                // 排除过滤 (Logcat 风格)
                part.startsWith("-message~:") -> { excludeKeyword = part.substringAfter("-message~:"); excludeIsRegex = true }
                part.startsWith("-message=:") -> { excludeKeyword = part.substringAfter("-message=:"); excludeIsExact = true }
                part.startsWith("-message:") -> excludeKeyword = part.substringAfter("-message:")
                // 包含过滤 (Logcat 风格)
                part.startsWith("message~:") -> { keyword = part.substringAfter("message~:"); isRegex = true }
                part.startsWith("message=:") -> { keyword = part.substringAfter("message=:"); isExact = true }
                part.startsWith("message:") -> keyword = part.substringAfter("message:")
                // 方向过滤
                part.startsWith("-dir:") -> {
                    val dir = part.substringAfter("-dir:").uppercase()
                    if (dir == "TX") showTx = false
                    if (dir == "RX") showRx = false
                    if (dir == "SYS") showSys = false
                }
                part.startsWith("dir:") -> {
                    val dir = part.substringAfter("dir:").uppercase()
                    showTx = dir == "TX"
                    showRx = dir == "RX"
                    showSys = dir == "SYS"
                }
                // 级别过滤
                part.startsWith("level:") -> {
                    minLevel = when (part.substringAfter("level:").uppercase()) {
                        "V", "VERBOSE" -> LogLevel.VERBOSE
                        "D", "DEBUG" -> LogLevel.DEBUG
                        "I", "INFO" -> LogLevel.INFO
                        "W", "WARN" -> LogLevel.WARN
                        "E", "ERROR" -> LogLevel.ERROR
                        else -> LogLevel.VERBOSE
                    }
                }
                // 普通关键词
                else -> if (keyword.isEmpty() && part.isNotEmpty()) keyword = part
            }
        }
        
        return FilterCondition(
            keyword = keyword,
            isRegex = isRegex,
            isExact = isExact,
            excludeKeyword = excludeKeyword,
            excludeIsRegex = excludeIsRegex,
            excludeIsExact = excludeIsExact,
            minLevel = minLevel,
            showTx = showTx,
            showRx = showRx,
            showSystem = showSys
        )
    }
    
    private fun refreshDisplay() {
        SwingUtilities.invokeLater {
            try {
                logDocument.remove(0, logDocument.length)
                filterManager.getFilteredEntries().forEach { appendEntryToDisplay(it) }
                if (autoScroll) scrollToBottom()
            } catch (e: Exception) { /* ignore */ }
        }
    }
    
    /**
     * Logcat 风格日志格式:
     * MM-dd HH:mm:ss.SSS  L/TAG  : content
     * 例如: 01-22 10:25:02.962  D/TX   : Hello World
     */
    private fun appendEntryToDisplay(entry: LogEntry) {
        // 根据日志级别选择样式
        val style = when (entry.level) {
            LogLevel.VERBOSE -> verboseStyle
            LogLevel.DEBUG -> debugStyle
            LogLevel.INFO -> infoStyle
            LogLevel.WARN -> warnStyle
            LogLevel.ERROR -> errorStyle
        }
        
        // 级别单字母标签 (Logcat 风格)
        val levelChar = when (entry.level) {
            LogLevel.VERBOSE -> 'V'
            LogLevel.DEBUG -> 'D'
            LogLevel.INFO -> 'I'
            LogLevel.WARN -> 'W'
            LogLevel.ERROR -> 'E'
        }
        
        // 方向标签 (作为 Tag)
        val tag = entry.direction.padEnd(3)
        
        // 内容处理
        val content = if (displayHex && entry.rawData != null) {
            entry.rawData.joinToString(" ") { "%02X".format(it) }
        } else {
            entry.content
        }
        
        // Logcat 格式: MM-dd HH:mm:ss.SSS  L/TAG  : content
        val text = if (showTimestamp) {
            "${entry.timestamp}  $levelChar/$tag : $content\n"
        } else {
            "$levelChar/$tag : $content\n"
        }
        
        try {
            logDocument.insertString(logDocument.length, text, style)
        } catch (e: BadLocationException) { /* ignore */ }
    }
    
    private fun scrollToBottom() {
        logArea.caretPosition = logDocument.length
    }
    
    private fun exportLog() {
        val chooser = JFileChooser()
        chooser.dialogTitle = "导出日志"
        chooser.selectedFile = File("serial_log_${System.currentTimeMillis()}.txt")
        
        if (chooser.showSaveDialog(mainPanel) == JFileChooser.APPROVE_OPTION) {
            try {
                chooser.selectedFile.writeText(filterManager.exportAllLogs(), StandardCharsets.UTF_8)
                showInfo("导出成功: ${chooser.selectedFile.absolutePath}")
            } catch (e: Exception) {
                showError("导出失败: ${e.message}")
            }
        }
    }
    
    private fun showCommandDialog() {
        val dialog = CommandDialog(project, commandManager) { command ->
            sendField.text = command
            sendData()
        }
        dialog.show()
    }
    
    private fun showSettings() {
        val dialog = SettingsDialog(project, serialService) {
            // 设置更新后的回调
            showInfo("设置已更新")
        }
        dialog.show()
    }
    
    private fun showFilterHelp() {
        val help = """
            |过滤语法 (Logcat 风格):
            |
            |  关键词          直接输入关键词过滤
            |  message:xxx    消息包含 xxx
            |  message~:xxx   正则匹配 xxx
            |  -message:xxx   不包含 xxx
            |  dir:TX         只看发送
            |  dir:RX         只看接收
            |  dir:SYS        只看系统
            |  level:WARN     级别 >= WARN
            |
            |组合示例:
            |  dir:RX message:OK
            |  level:ERROR -message:timeout
            |
            |快捷键:
            |  Enter  应用过滤
            |  Esc    清除过滤
            |  ↓      显示语法提示
        """.trimMargin()
        
        JOptionPane.showMessageDialog(mainPanel, help, "过滤帮助", JOptionPane.INFORMATION_MESSAGE)
    }
    
    private fun showInfo(msg: String) = JOptionPane.showMessageDialog(mainPanel, msg, "信息", JOptionPane.INFORMATION_MESSAGE)
    private fun showError(msg: String) = JOptionPane.showMessageDialog(mainPanel, msg, "错误", JOptionPane.ERROR_MESSAGE)
    
    // Logcat 风格时间戳: MM-dd HH:mm:ss.SSS
    private fun getCurrentTime(): String = java.time.LocalDateTime.now()
        .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS"))
    
    // ========== SerialPortListener ==========
    
    override fun onConnectionChanged(connected: Boolean, portName: String) {
        SwingUtilities.invokeLater {
            isConnected = connected
            updateConnectButton()
            
            filterManager.addEntry(LogEntry(
                timestamp = getCurrentTime(),
                direction = "SYS",
                content = if (connected) "✓ 已连接: $portName" else "✗ 已断开: $portName",
                level = LogLevel.INFO
            ))
        }
    }
    
    override fun onDataReceived(data: ByteArray, timestamp: String) {
        filterManager.addEntry(LogEntry(
            timestamp = timestamp,
            direction = "RX",
            content = String(data, StandardCharsets.UTF_8),
            level = LogLevel.DEBUG,
            rawData = data
        ))
    }
    
    override fun onDataSent(data: String, format: DataFormat, timestamp: String) {
        filterManager.addEntry(LogEntry(
            timestamp = timestamp,
            direction = "TX",
            content = data,
            level = LogLevel.DEBUG,
            rawData = data.toByteArray()
        ))
    }
    
    override fun onError(message: String) {
        SwingUtilities.invokeLater {
            filterManager.addEntry(LogEntry(
                timestamp = getCurrentTime(),
                direction = "ERR",
                content = message,
                level = LogLevel.ERROR
            ))
        }
    }
    
    // ========== PortChangeListener (热插拔) ==========
    
    override fun onPortAdded(portName: String) {
        SwingUtilities.invokeLater {
            refreshPortList()
            filterManager.addEntry(LogEntry(
                timestamp = getCurrentTime(),
                direction = "SYS",
                content = "⚡ 检测到新串口: $portName",
                level = LogLevel.INFO
            ))
        }
    }
    
    override fun onPortRemoved(portName: String) {
        SwingUtilities.invokeLater {
            refreshPortList()
            filterManager.addEntry(LogEntry(
                timestamp = getCurrentTime(),
                direction = "SYS",
                content = "⚡ 串口已移除: $portName",
                level = LogLevel.WARN
            ))
        }
    }
    
    // ========== LogFilterListener ==========
    
    override fun onEntryAdded(entry: LogEntry) {
        // 暂停时不更新显示
        if (isPaused) return
        
        SwingUtilities.invokeLater {
            appendEntryToDisplay(entry)
            if (autoScroll) scrollToBottom()
        }
    }
    
    override fun onFilterChanged(filteredEntries: List<LogEntry>) {}
    
    override fun onLogsCleared() {
        SwingUtilities.invokeLater {
            try { logDocument.remove(0, logDocument.length) } catch (e: Exception) {}
        }
    }
}
