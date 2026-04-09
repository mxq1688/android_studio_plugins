package com.serialport.plugin

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBList
import com.intellij.ui.JBColor
import com.intellij.ui.EditorTextField
import com.intellij.ide.ui.laf.darcula.ui.DarculaTextBorder
import com.intellij.util.ui.components.BorderLayoutPanel
import com.serialport.plugin.messages.*
import com.serialport.plugin.util.createSerialPortEditor
import com.serialport.plugin.util.isCaretAtBottom
import com.serialport.plugin.util.isScrollAtBottom
import kotlinx.coroutines.*
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.*
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
class SerialPortToolWindow(private val project: Project) : SerialPortListener, PortChangeListener, LogFilterListener, Disposable {
    
    private val serialService = project.service<SerialPortService>()
    private val commandManager = project.service<CommandManager>()
    private val filterManager = LogFilterManager()
    
    // UI Components
    private val mainPanel = JPanel(BorderLayout(0, 0))
    
    // EditorEx 和相关组件 (Logcat 风格)
    private val editor: EditorEx = createSerialPortEditor(project, this)
    private val document = editor.document
    private val documentAppender: DocumentAppender
    private val messageBacklog: MessageBacklog
    private val messageFormatter: MessageFormatter
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // 缓冲区大小 (默认 1MB)
    private val bufferSize = 1024 * 1024
    
    // 顶部栏组件
    private val portComboBox = JComboBox<String>()
    private val baudRateComboBox = JComboBox(arrayOf(
        "300", "600", "1200", "2400", "4800", "9600", "14400", "19200", 
        "28800", "38400", "56000", "57600", "115200", "128000", "230400", 
        "256000", "460800", "500000", "921600", "1000000", "1500000", "2000000"
    ))
    private val connectButton = JButton()
    
    // Logcat 风格过滤器 - 使用 EditorTextField
    private lateinit var filterTextField: EditorTextField
    private var filterPopup: JBPopup? = null
    private var filterMatchCase = false
    
    // 过滤器定时器 (用于延迟应用过滤和显示建议)
    private var filterTimer: Timer? = null
    private var suggestionTimer: Timer? = null
    
    // 过滤器历史记录 (Logcat 风格)
    private val filterHistory = mutableListOf<String>()
    private val maxFilterHistory = 20
    
    // 过滤器收藏 (Logcat 风格 - 星标功能)
    private val filterFavorites = mutableListOf<String>()
    private var favoriteBtn: JButton? = null  // 星标按钮引用
    
    // 日志级别下拉框 (Logcat 风格)
    private val logLevelComboBox = JComboBox(arrayOf("Verbose", "Debug", "Info", "Warn", "Error"))
    
    // 发送区
    private val sendField = JTextField()
    private val hexModeCheckBox = JCheckBox("HEX")
    
    // 状态
    private var isConnected = false
    private var autoScroll = true
    private var isPaused = false  // 暂停日志更新
    private var showTimestamp = true
    private var displayHex = false
    private var isCompactView = false  // Logcat: Standard View vs Compact View
    
    // UI 引用 (用于状态同步)
    private var autoScrollBtn: JButton? = null
    private var pauseBtn: JButton? = null
    // removed logScrollPane since EditorEx has its own scroll pane
    private var statusLabel: JLabel? = null  // 状态栏统计显示
    
    // 滚动状态管理 (参考 Logcat)
    private var ignoreCaretAtBottom = false
    private var caretLine = 0
    
    // 搜索栏 (Ctrl+F)
    private var searchBar: JPanel? = null
    private val searchField = JTextField(20)
    private var searchResultLabel: JLabel? = null
    private var searchResults = mutableListOf<Int>()  // 搜索结果位置
    private var currentSearchIndex = -1
    private var caseSensitive = false
    private var wholeWord = false
    private var useRegex = false
    
    // 搜索历史记录
    private val searchHistory = mutableListOf<String>()
    private val maxSearchHistory = 20
    
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
        FilterSuggestion("level:", "Minimum log level (V/D/I/W/E)", "level:W"),
        // 时间范围过滤 (Logcat age: 语法)
        FilterSuggestion("age:", "Filter by time range (s/m/h/d)", "age:5m"),
        // 特殊过滤 (Logcat is: 语法)
        FilterSuggestion("is:crash", "Show only crash/error logs", "is:crash"),
        FilterSuggestion("is:stacktrace", "Show only stacktrace lines", "is:stacktrace"),
        // 逻辑运算符
        FilterSuggestion("&", "AND operator (combine conditions)", "dir:RX & level:E"),
        FilterSuggestion("|", "OR operator (match any)", "dir:TX | dir:RX")
    )
    
    data class FilterSuggestion(val syntax: String, val description: String, val example: String)
    
    init {
        // 初始化消息处理组件
        documentAppender = DocumentAppender(project, document, bufferSize)
        messageBacklog = MessageBacklog(bufferSize)
        messageFormatter = MessageFormatter(ZoneId.systemDefault())
        
        // 初始化编辑器设置
        initEditorSettings()
        
        setupUI()
        setupListeners()
        serialService.addListener(this)
        serialService.addPortChangeListener(this)
        filterManager.addListener(this)
        refreshPortList()
    }
    
    /**
     * 初始化编辑器设置
     */
    private fun initEditorSettings() {
        editor.settings.isUseSoftWraps = false
        messageFormatter.setSoftWrapEnabled(false)
        
        // 监听光标位置变化
        editor.caretModel.addCaretListener(object : com.intellij.openapi.editor.event.CaretListener {
            override fun caretPositionChanged(event: com.intellij.openapi.editor.event.CaretEvent) {
                caretLine = event.newPosition.line
            }
        })
        
        // 初始化滚动到底部状态处理
        initScrollToEndStateHandling()
    }
    
    /**
     * 初始化滚动到底部状态处理 (参考 Logcat)
     */
    private fun initScrollToEndStateHandling() {
        val mouseListener = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                updateScrollToEndState(true)
            }
            
            override fun mouseDragged(e: MouseEvent) {
                updateScrollToEndState(false)
            }
            
            override fun mouseWheelMoved(e: MouseWheelEvent) {
                if (e.isShiftDown) return // 忽略水平滚动
                updateScrollToEndState(false)
            }
        }
        val scrollPane = editor.scrollPane
        scrollPane.addMouseWheelListener(mouseListener)
        scrollPane.verticalScrollBar.addMouseListener(mouseListener)
        scrollPane.verticalScrollBar.addMouseMotionListener(mouseListener)
    }
    
    /**
     * 更新滚动到底部状态 (参考 Logcat)
     */
    private fun updateScrollToEndState(useImmediatePosition: Boolean) {
        val scrollAtBottom = editor.isScrollAtBottom(useImmediatePosition)
        val caretAtBottom = editor.isCaretAtBottom()
        if (!scrollAtBottom && caretAtBottom) {
            ignoreCaretAtBottom = true
        }
        
        if (autoScroll != scrollAtBottom) {
            autoScroll = scrollAtBottom
            autoScrollBtn?.let { updateToggleButton(it, autoScroll) }
        }
    }
    
    override fun dispose() {
        coroutineScope.cancel()
        com.intellij.openapi.editor.EditorFactory.getInstance().releaseEditor(editor)
    }
    
    fun getContent(): JComponent = mainPanel
    
    private fun setupUI() {
        mainPanel.background = JBColor.background()
        
        // === 顶部栏 (Logcat 风格：设备 + 过滤器) ===
        val topBar = createLogcatTopBar()
        mainPanel.add(topBar, BorderLayout.NORTH)
        
        // === 主内容区 ===
        val contentPanel = JPanel(BorderLayout(0, 0))
        
        // 左侧垂直工具栏 (Logcat 风格)
        val leftToolbar = createLogcatLeftToolbar()
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
     * 创建 Logcat 风格顶部栏 (设备选择 + 过滤器)
     * 使用 GroupLayout 布局，参考 LogcatHeaderPanel
     */
    private fun createLogcatTopBar(): JPanel {
        val panel = JPanel()
        panel.background = JBColor.background()
        panel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
            BorderFactory.createEmptyBorder(4, 8, 4, 8)
        )
        
        // 配置组件
        portComboBox.preferredSize = Dimension(200, 28)
        portComboBox.minimumSize = Dimension(150, 28)
        portComboBox.toolTipText = "选择串口"
        portComboBox.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
            ): Component {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                toolTipText = value?.toString()
                return this
            }
        }
        
        baudRateComboBox.preferredSize = Dimension(96, 28)
        baudRateComboBox.selectedItem = "115200"
        baudRateComboBox.toolTipText = "波特率"
        baudRateComboBox.isEditable = true
        (baudRateComboBox.editor.editorComponent as? JTextField)?.let { editor ->
            editor.document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = validate()
                override fun removeUpdate(e: DocumentEvent) = validate()
                override fun changedUpdate(e: DocumentEvent) = validate()
                private fun validate() {
                    val text = editor.text
                    if (text.isNotEmpty() && !text.all { it.isDigit() }) {
                        SwingUtilities.invokeLater { editor.text = text.filter { it.isDigit() } }
                    }
                }
            })
        }
        
        connectButton.preferredSize = Dimension(56, 28)
        updateConnectButton()
        
        // 顶部保留串口核心控制（设备 + 波特率 + 连接）
        val devicePanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        devicePanel.isOpaque = false
        devicePanel.add(portComboBox)
        devicePanel.add(baudRateComboBox)
        connectButton.isFocusPainted = false
        connectButton.margin = Insets(2, 8, 2, 8)
        devicePanel.add(connectButton)
        
        // 创建 Logcat 风格的过滤器输入框 (使用 BorderLayoutPanel + EditorTextField)
        val filterPanel = createLogcatFilterTextField()
        
        // 使用 GroupLayout 布局
        val layout = GroupLayout(panel)
        layout.autoCreateContainerGaps = true
        layout.autoCreateGaps = true
        
        val helpIcon = JLabel(AllIcons.General.ContextHelp).apply {
            toolTipText = "Filter syntax help"
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent?) = showFilterHelp()
            })
        }

        layout.setHorizontalGroup(
            layout.createSequentialGroup()
                .addComponent(devicePanel, GroupLayout.PREFERRED_SIZE, GroupLayout.DEFAULT_SIZE, GroupLayout.PREFERRED_SIZE)
                .addComponent(filterPanel, com.intellij.util.ui.JBUI.scale(420), GroupLayout.DEFAULT_SIZE, GroupLayout.DEFAULT_SIZE)
                .addComponent(helpIcon)
        )
        
        layout.setVerticalGroup(
            layout.createParallelGroup(GroupLayout.Alignment.CENTER)
                .addComponent(devicePanel, GroupLayout.PREFERRED_SIZE, GroupLayout.DEFAULT_SIZE, GroupLayout.PREFERRED_SIZE)
                .addComponent(filterPanel, GroupLayout.PREFERRED_SIZE, GroupLayout.DEFAULT_SIZE, GroupLayout.PREFERRED_SIZE)
                .addComponent(helpIcon)
        )
        
        panel.layout = layout
        return panel
    }
    
    /**
     * 创建 Logcat 风格左侧垂直工具栏
     */
    /**
     * 将 LogEntry 转换为 SerialPortMessage
     */
    private fun LogEntry.toSerialPortMessage(): SerialPortMessage {
        val direction = when (this.direction) {
            "TX" -> SerialPortMessage.Direction.TX
            "RX" -> SerialPortMessage.Direction.RX
            "SYS" -> SerialPortMessage.Direction.SYS
            "ERR" -> SerialPortMessage.Direction.ERR
            else -> SerialPortMessage.Direction.SYS
        }
        
        val level = when (this.level) {
            LogLevel.VERBOSE -> SerialPortMessage.LogLevel.VERBOSE
            LogLevel.DEBUG -> SerialPortMessage.LogLevel.DEBUG
            LogLevel.INFO -> SerialPortMessage.LogLevel.INFO
            LogLevel.WARN -> SerialPortMessage.LogLevel.WARN
            LogLevel.ERROR -> SerialPortMessage.LogLevel.ERROR
        }
        
        // 解析时间戳
        val instant = try {
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            java.time.LocalDateTime.parse(this.timestamp, formatter)
                .atZone(ZoneId.systemDefault())
                .toInstant()
        } catch (e: Exception) {
            Instant.ofEpochMilli(this.createdTime)
        }
        
        return SerialPortMessage(
            timestamp = instant,
            direction = direction,
            level = level,
            content = this.content,
            rawBytes = this.rawData
        )
    }
    
    /**
     * 创建 Logcat 风格左侧工具栏 (参考截图布局)
     */
    private fun createLogcatLeftToolbar(): JPanel {
        val toolbar = JPanel()
        toolbar.layout = BoxLayout(toolbar, BoxLayout.Y_AXIS)
        // Logcat 风格的边框：右侧边框
        toolbar.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 0, 1, JBColor.border()),
            BorderFactory.createEmptyBorder(4, 2, 4, 2)
        )
        toolbar.background = JBColor.background()
        toolbar.preferredSize = Dimension(32, 0)
        
        toolbar.add(Box.createVerticalStrut(4))
        
        // === 第一组：基本操作 ===
        
        // 1. 清空日志
        val clearBtn = createLeftToolbarButton(AllIcons.Actions.GC, "清空 (Ctrl+L)")
        clearBtn.addActionListener { filterManager.clearLogs() }
        toolbar.add(clearBtn)
        
        // 2. 搜索 (展开/折叠搜索栏)
        val searchToggleBtn = createLeftToolbarButton(AllIcons.General.ArrowRight, "搜索 (Ctrl+F)")
        searchToggleBtn.addActionListener { 
            if (searchBar?.isVisible == true) {
                hideSearchBar()
            } else {
                showSearchBar()
            }
        }
        toolbar.add(searchToggleBtn)
        
        // 3. 暂停/继续
        pauseBtn = createLeftToolbarToggleButton(AllIcons.Actions.Pause, "暂停日志", isPaused)
        pauseBtn!!.addActionListener {
            isPaused = !isPaused
            updateToggleButton(pauseBtn!!, isPaused)
            if (!isPaused) refreshDisplay()
        }
        toolbar.add(pauseBtn)
        
        // 4. 刷新串口
        val refreshBtn = createLeftToolbarButton(AllIcons.Actions.Refresh, "刷新串口")
        refreshBtn.addActionListener { refreshPortList() }
        toolbar.add(refreshBtn)
        
        // 5. 滚动到底部 (自动滚动开关)
        autoScrollBtn = createLeftToolbarToggleButton(AllIcons.RunConfigurations.Scroll_down, "滚动到底部/自动滚动", autoScroll)
        autoScrollBtn!!.addActionListener {
            if (autoScroll) {
                // 当前是开启状态，点击则关闭自动滚动
                autoScroll = false
                updateToggleButton(autoScrollBtn!!, false)
                
                // 将光标上移一行，打破 isCaretAtBottom 状态 (参考 Logcat)
                val lastLine = maxOf(0, editor.document.lineCount - 1)
                val currentPos = editor.caretModel.logicalPosition
                val newPos = com.intellij.openapi.editor.LogicalPosition(
                    maxOf(0, minOf(currentPos.line, lastLine - 1)), 
                    currentPos.column
                )
                editor.caretModel.moveToLogicalPosition(newPos)
            } else {
                // 当前是关闭状态，点击则开启并滚到底部
                autoScroll = true
                updateToggleButton(autoScrollBtn!!, true)
                scrollToBottom()
            }
        }
        toolbar.add(autoScrollBtn)
        
        // 6. 向上翻页
        val pageUpBtn = createLeftToolbarButton(AllIcons.Actions.MoveUp, "向上翻页")
        pageUpBtn.addActionListener { 
            autoScroll = false
            updateToggleButton(autoScrollBtn!!, autoScroll)
            val scrollBar = editor.scrollPane.verticalScrollBar
            scrollBar.value = maxOf(0, scrollBar.value - scrollBar.blockIncrement)
        }
        toolbar.add(pageUpBtn)
        
        // 7. 向下翻页
        val pageDownBtn = createLeftToolbarButton(AllIcons.Actions.MoveDown, "向下翻页")
        pageDownBtn.addActionListener { 
            val scrollBar = editor.scrollPane.verticalScrollBar
            scrollBar.value = minOf(scrollBar.maximum - scrollBar.visibleAmount, scrollBar.value + scrollBar.blockIncrement)
        }
        toolbar.add(pageDownBtn)
        
        toolbar.add(Box.createVerticalStrut(4))
        toolbar.add(createHorizontalSeparator())
        toolbar.add(Box.createVerticalStrut(4))
        
        // === 第二组：显示选项 ===
        
        // 8. 软换行/自动换行 (JTextPane 不支持直接设置，用滚动条策略代替)
        var softWrap = true
        val wrapBtn = createLeftToolbarToggleButton(AllIcons.Actions.ToggleSoftWrap, "软换行", softWrap)
        wrapBtn.addActionListener {
            softWrap = !softWrap
            updateToggleButton(wrapBtn, softWrap)
            editor.settings.isUseSoftWraps = softWrap
        }
        toolbar.add(wrapBtn)
        
        // 9. 时间戳
        val timestampBtn = createLeftToolbarToggleButton(AllIcons.Debugger.Watch, "时间戳", showTimestamp)
        timestampBtn.addActionListener {
            showTimestamp = !showTimestamp
            updateToggleButton(timestampBtn, showTimestamp)
            refreshDisplay()
        }
        toolbar.add(timestampBtn)
        
        // 10. HEX 显示
        val hexBtn = createLeftToolbarToggleButton(AllIcons.Debugger.Db_primitive, "HEX显示", displayHex)
        hexBtn.addActionListener {
            displayHex = !displayHex
            updateToggleButton(hexBtn, displayHex)
            refreshDisplay()
        }
        toolbar.add(hexBtn)
        
        // 11. 视图切换 (Logcat: Standard/Compact View)
        val viewBtn = createLeftToolbarToggleButton(AllIcons.Actions.PreviewDetails, "Standard/Compact 视图切换", isCompactView)
        viewBtn.toolTipText = "切换视图模式 (Standard ↔ Compact)"
        viewBtn.addActionListener {
            isCompactView = !isCompactView
            updateToggleButton(viewBtn, isCompactView)
            refreshDisplay()
        }
        toolbar.add(viewBtn)
        
        toolbar.add(Box.createVerticalStrut(4))
        toolbar.add(createHorizontalSeparator())
        toolbar.add(Box.createVerticalStrut(4))
        
        // === 第三组：工具 ===
        
        // 12. 过滤设置
        val filterBtn = createLeftToolbarButton(AllIcons.General.Filter, "过滤设置")
        filterBtn.addActionListener { showFilterSuggestions() }
        toolbar.add(filterBtn)
        
        // 12. 复制全部
        val copyBtn = createLeftToolbarButton(AllIcons.Actions.Copy, "复制全部日志")
        copyBtn.addActionListener { 
            editor.selectionModel.setSelection(0, document.textLength)
            editor.selectionModel.copySelectionToClipboard()
            editor.selectionModel.removeSelection()
        }
        toolbar.add(copyBtn)
        
        // 13. 导出/保存
        val exportBtn = createLeftToolbarButton(AllIcons.Actions.MenuSaveall, "导出日志")
        exportBtn.addActionListener { exportLog() }
        toolbar.add(exportBtn)
        
        toolbar.add(Box.createVerticalStrut(4))
        toolbar.add(createHorizontalSeparator())
        toolbar.add(Box.createVerticalStrut(4))
        
        // === 第四组：串口特有 ===
        
        // 14. 快捷指令
        val cmdBtn = createLeftToolbarButton(AllIcons.Actions.Lightning, "快捷指令")
        cmdBtn.addActionListener { showCommandDialog() }
        toolbar.add(cmdBtn)
        
        // 15. 设置
        val settingsBtn = createLeftToolbarButton(AllIcons.General.Settings, "设置")
        settingsBtn.addActionListener { showSettings() }
        toolbar.add(settingsBtn)
        
        toolbar.add(Box.createVerticalGlue())
        
        return toolbar
    }
    
    private fun createLeftToolbarButton(icon: Icon, tooltip: String): JButton {
        return JButton(icon).apply {
            preferredSize = Dimension(28, 28)
            maximumSize = Dimension(28, 28)
            minimumSize = Dimension(28, 28)
            toolTipText = tooltip
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusPainted = false
            alignmentX = Component.CENTER_ALIGNMENT
            // Logcat 风格的按钮悬停效果
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    background = JBColor(Color(60, 63, 65), Color(50, 52, 54))
                }
                override fun mouseExited(e: MouseEvent) {
                    background = JBColor.background()
                }
            })
        }
    }
    
    private fun createLeftToolbarToggleButton(icon: Icon, tooltip: String, selected: Boolean): JButton {
        return JButton(icon).apply {
            preferredSize = Dimension(28, 28)
            maximumSize = Dimension(28, 28)
            minimumSize = Dimension(28, 28)
            toolTipText = tooltip
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusPainted = false
            alignmentX = Component.CENTER_ALIGNMENT
            updateToggleButton(this, selected)
            // Logcat 风格的按钮悬停效果
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    if (!selected) {
                        background = JBColor(Color(60, 63, 65), Color(50, 52, 54))
                    }
                }
                override fun mouseExited(e: MouseEvent) {
                    if (!selected) {
                        background = JBColor.background()
                    }
                }
            })
        }
    }
    
    private fun createHorizontalSeparator(): JSeparator {
        return JSeparator(SwingConstants.HORIZONTAL).apply {
            maximumSize = Dimension(28, 1)
            preferredSize = Dimension(28, 1)
            background = JBColor.border()
        }
    }
    
    private fun createToolbarIconButton(icon: Icon, tooltip: String): JButton {
        return JButton(icon).apply {
            preferredSize = Dimension(24, 24)
            maximumSize = Dimension(24, 24)
            toolTipText = tooltip
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusPainted = false
        }
    }
    
    private fun createToolbarToggleButton(icon: Icon, tooltip: String, selected: Boolean): JButton {
        return JButton(icon).apply {
            preferredSize = Dimension(24, 24)
            maximumSize = Dimension(24, 24)
            toolTipText = tooltip
            isBorderPainted = true
            isContentAreaFilled = false
            isFocusPainted = false
            updateToggleButton(this, selected)
        }
    }
    
    private fun createVerticalSeparator(): JSeparator {
        return JSeparator(SwingConstants.VERTICAL).apply {
            preferredSize = Dimension(1, 20)
            maximumSize = Dimension(1, 20)
        }
    }
    
    
    /**
     * 创建 Logcat 风格的过滤器输入框 (参考 FilterTextField)
     */
    private fun createLogcatFilterTextField(): BorderLayoutPanel {
        // 创建 EditorTextField (使用 PlainTextFileType)
        filterTextField = EditorTextField(project, com.intellij.openapi.fileTypes.PlainTextFileType.INSTANCE)
        filterTextField.setPlaceholder("Press Ctrl+Space to see suggestions")
        filterTextField.setShowPlaceholderWhenFocused(true)
        filterTextField.border = com.intellij.util.ui.JBUI.Borders.empty()
        
        // 左侧过滤图标（Logcat 风格）
        val filterIcon = createInlineButton(AllIcons.General.Filter, "Filter syntax", filterTextField)
        filterIcon.border = com.intellij.util.ui.JBUI.Borders.empty(0, 5, 0, 4)
        filterIcon.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = showFilterSuggestions()
        })
        
        // 清除按钮 (参考 Logcat ClearButton)
        val clearButton = createInlineButton(AllIcons.Actions.Close, "Clear filter", filterTextField)
        clearButton.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                filterTextField.text = ""
                // 立即应用空过滤器
                applyFilter()
            }
        })
        
        val caseBtn = JButton("Cc").apply {
            toolTipText = "Match case"
            preferredSize = Dimension(28, 20)
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusPainted = false
            foreground = JBColor.GRAY
            border = com.intellij.util.ui.JBUI.Borders.empty(0, 3)
        }
        caseBtn.addActionListener {
            filterMatchCase = !filterMatchCase
            caseBtn.foreground = if (filterMatchCase) {
                JBColor(Color(88, 157, 246), Color(88, 157, 246))
            } else {
                JBColor.GRAY
            }
            applyFilter()
        }
        
        // 右侧按钮面板
        val buttonPanel = createInlinePanel(clearButton, caseBtn)
        
        // 更新收藏按钮状态和按钮可见性
        filterTextField.addDocumentListener(object : com.intellij.openapi.editor.event.DocumentListener {
            override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                val hasFilter = event.document.text.isNotEmpty()
                clearButton.isVisible = hasFilter
            }
        })
        
        // 创建 BorderLayoutPanel (Logcat 风格)
        val filterPanel = object : BorderLayoutPanel() {
            override fun updateUI() {
                super.updateUI()
                // 更新背景色以匹配主题
                background = JBColor.background()
            }
        }
        
        filterPanel.addToLeft(filterIcon)
        filterPanel.addToCenter(filterTextField)
        filterPanel.addToRight(buttonPanel)
        
        // Logcat 风格的边框
        filterPanel.border = BorderFactory.createLineBorder(JBColor.border(), 1)
        
        return filterPanel
    }
    
    private fun createInlineButton(icon: Icon, tooltip: String, textField: EditorTextField): JLabel {
        return object : JLabel(icon) {
            init {
                isOpaque = false
                toolTipText = tooltip
            }
            
            override fun updateUI() {
                background = textField.background
                super.updateUI()
            }
        }
    }
    
    private fun createInlinePanel(vararg children: JComponent): JPanel {
        return object : JPanel(null) {
            init {
                layout = BoxLayout(this, BoxLayout.LINE_AXIS)
                isOpaque = false
                border = com.intellij.util.ui.JBUI.Borders.empty(0, 2)
                children.forEach {
                    it.border = com.intellij.util.ui.JBUI.Borders.empty(0, 2)
                    add(it)
                }
            }
            
            override fun updateUI() {
                super.updateUI()
            }
        }
    }
    
    private inner class FilterTextFieldBorder : DarculaTextBorder() {
        override fun isFocused(c: Component?): Boolean {
            return filterTextField.editor?.contentComponent?.hasFocus() == true
        }
    }
    
    /**
     * 显示过滤器语法下拉提示 (Logcat 风格)
     */
    /**
     * 显示过滤建议 (Logcat 风格 - 图1的历史记录列表)
     */
    /**
     * 切换收藏状态 (Logcat 星标功能)
     */
    private fun toggleFavorite() {
        val currentFilter = filterTextField.text.trim()
        if (currentFilter.isEmpty()) return
        
        if (filterFavorites.contains(currentFilter)) {
            filterFavorites.remove(currentFilter)
            favoriteBtn?.icon = AllIcons.Nodes.NotFavoriteOnHover
        } else {
            filterFavorites.add(0, currentFilter)
            favoriteBtn?.icon = AllIcons.Nodes.Favorite
        }
    }
    
    /**
     * 显示过滤历史记录 (Logcat 风格 - 简洁列表)
     * 样式参考截图：[过滤文本] [匹配数] [🗑删除]
     */
    // 历史记录使用时间戳
    private val filterUsageTime = mutableMapOf<String, Long>()
    
    private fun showFilterSuggestions() {
        showFilterSuggestionsWithSearch("")
    }
    
    private fun showFilterSuggestionsWithSearch(searchQuery: String) {
        filterPopup?.cancel()
        
        // 即使没有历史记录也显示空状态
        if (filterHistory.isEmpty() && filterFavorites.isEmpty()) {
            showEmptyHistoryPopup()
            return
        }
        
        // Logcat 风格颜色
        val bgColor = JBColor(Color(49, 51, 53), Color(43, 43, 43))
        val selectedColor = JBColor(Color(45, 91, 138), Color(45, 91, 138))
        val hoverColor = JBColor(Color(62, 80, 103), Color(55, 72, 95))
        val textColor = JBColor(Color(212, 212, 212), Color(187, 187, 187))
        val dimTextColor = JBColor(Color(140, 140, 140), Color(120, 120, 120))
        val headerColor = JBColor(Color(152, 195, 121), Color(106, 135, 89))
        val linkColor = JBColor(Color(104, 151, 187), Color(88, 157, 246))
        val searchBgColor = JBColor(Color(60, 63, 65), Color(50, 52, 54))
        
        // 数据结构
        data class FilterItem(val text: String, val isFavorite: Boolean, val isHeader: Boolean, val filterText: String = "")
        
        // 根据搜索过滤
        val filteredFavorites = if (searchQuery.isEmpty()) filterFavorites 
            else filterFavorites.filter { it.contains(searchQuery, ignoreCase = true) }
        val filteredHistory = if (searchQuery.isEmpty()) filterHistory.filter { !filterFavorites.contains(it) }
            else filterHistory.filter { !filterFavorites.contains(it) && it.contains(searchQuery, ignoreCase = true) }
        
        val allItems = mutableListOf<FilterItem>()
        
        // 添加收藏区域
        if (filteredFavorites.isNotEmpty()) {
            allItems.add(FilterItem("★ Favorites", false, true))
            filteredFavorites.forEach { allItems.add(FilterItem(it, true, false, it)) }
        }
        
        // 添加历史区域
        if (filteredHistory.isNotEmpty()) {
            allItems.add(FilterItem("⏱ Recent", false, true))
            filteredHistory.forEach { allItems.add(FilterItem(it, false, false, it)) }
        }
        
        // 创建下拉面板
        val popupPanel = JPanel(BorderLayout())
        popupPanel.background = bgColor
        popupPanel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor(Color(65, 65, 65), Color(55, 55, 55))),
            BorderFactory.createEmptyBorder(0, 0, 0, 0)
        )
        
        // 顶部搜索框
        val searchPanel = JPanel(BorderLayout(6, 0))
        searchPanel.background = searchBgColor
        searchPanel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor(Color(65, 65, 65), Color(55, 55, 55))),
            BorderFactory.createEmptyBorder(6, 10, 6, 10)
        )
        
        val searchIcon = JLabel(AllIcons.Actions.Search)
        searchPanel.add(searchIcon, BorderLayout.WEST)
        
        val popupSearchField = JTextField(searchQuery)
        popupSearchField.font = Font("JetBrains Mono", Font.PLAIN, 12)
        popupSearchField.background = searchBgColor
        popupSearchField.foreground = textColor
        popupSearchField.border = BorderFactory.createEmptyBorder(2, 4, 2, 4)
        popupSearchField.caretColor = textColor
        
        // 搜索框提示文字
        if (searchQuery.isEmpty()) {
            popupSearchField.foreground = dimTextColor
            popupSearchField.text = "Search filters..."
            popupSearchField.addFocusListener(object : FocusAdapter() {
                override fun focusGained(e: FocusEvent?) {
                    if (popupSearchField.text == "Search filters...") {
                        popupSearchField.text = ""
                        popupSearchField.foreground = textColor
                    }
                }
                override fun focusLost(e: FocusEvent?) {
                    if (popupSearchField.text.isEmpty()) {
                        popupSearchField.foreground = dimTextColor
                        popupSearchField.text = "Search filters..."
                    }
                }
            })
        }
        
        searchPanel.add(popupSearchField, BorderLayout.CENTER)
        
        // 清除搜索按钮
        if (searchQuery.isNotEmpty()) {
            val clearSearchBtn = JLabel(AllIcons.Actions.Close)
            clearSearchBtn.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            clearSearchBtn.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent?) {
                    showFilterSuggestionsWithSearch("")
                }
            })
            searchPanel.add(clearSearchBtn, BorderLayout.EAST)
        }
        
        popupPanel.add(searchPanel, BorderLayout.NORTH)
        
        // 主列表面板
        val listPanel = JPanel()
        listPanel.layout = BoxLayout(listPanel, BoxLayout.Y_AXIS)
        listPanel.background = bgColor
        
        var currentSelectedIndex = 0
        val selectableItems = mutableListOf<JPanel>()
        val selectableTexts = mutableListOf<String>()
        
        allItems.forEachIndexed { index, item ->
            if (item.isHeader) {
                // 分区标题
                val headerPanel = JPanel(BorderLayout())
                headerPanel.background = bgColor
                headerPanel.border = BorderFactory.createEmptyBorder(
                    if (index == 0) 6 else 10, 12, 4, 12
                )
                headerPanel.maximumSize = Dimension(Int.MAX_VALUE, if (index == 0) 26 else 30)
                
                val headerLabel = JLabel(item.text)
                headerLabel.font = Font(Font.SANS_SERIF, Font.BOLD, 11)
                headerLabel.foreground = headerColor
                headerPanel.add(headerLabel, BorderLayout.WEST)
                
                // Clear 按钮
                if (item.text.contains("Recent")) {
                    val clearBtn = JLabel("Clear all")
                    clearBtn.font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
                    clearBtn.foreground = linkColor
                    clearBtn.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    clearBtn.addMouseListener(object : MouseAdapter() {
                        override fun mouseEntered(e: MouseEvent?) {
                            clearBtn.text = "<html><u>Clear all</u></html>"
                        }
                        override fun mouseExited(e: MouseEvent?) {
                            clearBtn.text = "Clear all"
                        }
                        override fun mouseClicked(e: MouseEvent?) {
                            filterHistory.clear()
                            filterPopup?.cancel()
                            if (filterFavorites.isNotEmpty()) showFilterSuggestions()
                        }
                    })
                    headerPanel.add(clearBtn, BorderLayout.EAST)
                }
                
                listPanel.add(headerPanel)
            } else {
                // 可选条目
                val itemPanel = JPanel(BorderLayout(6, 0))
                val isFirstSelectable = selectableItems.isEmpty()
                itemPanel.background = if (isFirstSelectable) selectedColor else bgColor
                itemPanel.border = BorderFactory.createEmptyBorder(7, 14, 7, 10)
                itemPanel.maximumSize = Dimension(Int.MAX_VALUE, 34)
                
                // 左侧图标
                val iconLabel = JLabel(
                    if (item.isFavorite) AllIcons.Nodes.Favorite 
                    else AllIcons.Vcs.History
                )
                itemPanel.add(iconLabel, BorderLayout.WEST)
                
                // 中间：文字 + 时间
                val centerPanel = JPanel(BorderLayout(4, 0))
                centerPanel.isOpaque = false
                
                val displayText = if (item.text.length > 28) item.text.take(25) + "..." else item.text
                val textLabel = JLabel(displayText)
                textLabel.font = Font("JetBrains Mono", Font.PLAIN, 12)
                textLabel.foreground = textColor
                textLabel.toolTipText = item.text
                centerPanel.add(textLabel, BorderLayout.WEST)
                
                // 使用时间
                val usageTime = filterUsageTime[item.filterText]
                if (usageTime != null) {
                    val timeAgo = getTimeAgo(usageTime)
                    val timeLabel = JLabel(timeAgo)
                    timeLabel.font = Font(Font.SANS_SERIF, Font.PLAIN, 10)
                    timeLabel.foreground = JBColor(Color(90, 90, 90), Color(75, 75, 75))
                    centerPanel.add(timeLabel, BorderLayout.EAST)
                }
                
                itemPanel.add(centerPanel, BorderLayout.CENTER)
                
                // 右侧：匹配数 + 删除按钮
                val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0))
                rightPanel.isOpaque = false
                
                // 匹配数
                val matchCount = countMatches(item.filterText)
                val countLabel = JLabel(if (matchCount > 0) "$matchCount" else "—")
                countLabel.font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
                countLabel.foreground = if (matchCount > 0) dimTextColor else JBColor(Color(80, 80, 80), Color(70, 70, 70))
                countLabel.toolTipText = "$matchCount matches"
                rightPanel.add(countLabel)
                
                // 删除按钮（悬停显示）
                val deleteBtn = JLabel(AllIcons.Actions.Close)
                deleteBtn.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                deleteBtn.toolTipText = "Remove"
                deleteBtn.isVisible = false
                deleteBtn.addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent?) {
                        e?.consume()
                        if (item.isFavorite) {
                            filterFavorites.remove(item.filterText)
                            updateFavoriteButtonState()
                        } else {
                            filterHistory.remove(item.filterText)
                        }
                        filterUsageTime.remove(item.filterText)
                        filterPopup?.cancel()
                        if (filterHistory.isNotEmpty() || filterFavorites.isNotEmpty()) {
                            showFilterSuggestionsWithSearch(searchQuery)
                        }
                    }
                })
                rightPanel.add(deleteBtn)
                
                itemPanel.add(rightPanel, BorderLayout.EAST)
                
                // 右键菜单
                val popupMenu = JPopupMenu()
                popupMenu.background = bgColor
                
                val copyItem = JMenuItem("Copy", AllIcons.Actions.Copy)
                copyItem.addActionListener {
                    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                    clipboard.setContents(StringSelection(item.filterText), null)
                }
                popupMenu.add(copyItem)
                
                val favoriteItem = JMenuItem(
                    if (item.isFavorite) "Remove from Favorites" else "Add to Favorites",
                    if (item.isFavorite) AllIcons.Nodes.NotFavoriteOnHover else AllIcons.Nodes.Favorite
                )
                favoriteItem.addActionListener {
                    if (item.isFavorite) {
                        filterFavorites.remove(item.filterText)
                    } else {
                        filterFavorites.add(0, item.filterText)
                    }
                    updateFavoriteButtonState()
                    filterPopup?.cancel()
                    showFilterSuggestionsWithSearch(searchQuery)
                }
                popupMenu.add(favoriteItem)
                
                popupMenu.addSeparator()
                
                val deleteItem = JMenuItem("Delete", AllIcons.Actions.GC)
                deleteItem.addActionListener {
                    if (item.isFavorite) filterFavorites.remove(item.filterText)
                    else filterHistory.remove(item.filterText)
                    filterUsageTime.remove(item.filterText)
                    filterPopup?.cancel()
                    if (filterHistory.isNotEmpty() || filterFavorites.isNotEmpty()) {
                        showFilterSuggestionsWithSearch(searchQuery)
                    }
                }
                popupMenu.add(deleteItem)
                
                itemPanel.componentPopupMenu = popupMenu
                
                val currentIndex = selectableItems.size
                selectableItems.add(itemPanel)
                selectableTexts.add(item.filterText)
                
                // 鼠标交互
                itemPanel.addMouseListener(object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent?) {
                        selectableItems.forEachIndexed { i, panel ->
                            panel.background = if (i == currentIndex) hoverColor else bgColor
                            // 只在悬停的项目上显示删除按钮
                            val right = panel.getComponent(2) as? JPanel
                            right?.components?.filterIsInstance<JLabel>()?.lastOrNull()?.isVisible = (i == currentIndex)
                        }
                        currentSelectedIndex = currentIndex
                    }
                    override fun mouseExited(e: MouseEvent?) {
                        itemPanel.background = bgColor
                        deleteBtn.isVisible = false
                    }
                    override fun mouseClicked(e: MouseEvent?) {
                        filterTextField.text = item.filterText
                        filterTextField.editor?.caretModel?.moveToOffset(filterTextField.text.length)
                        filterPopup?.cancel()
                        this@SerialPortToolWindow.applyFilter()
                    }
                })
                
                listPanel.add(itemPanel)
            }
        }
        
        val scrollPane = JBScrollPane(listPanel)
        scrollPane.border = BorderFactory.createEmptyBorder()
        scrollPane.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        scrollPane.verticalScrollBar.unitIncrement = 16
        val itemCount = selectableItems.size + allItems.count { it.isHeader }
        scrollPane.preferredSize = Dimension(340, minOf(itemCount * 32 + 12, 320))
        popupPanel.add(scrollPane, BorderLayout.CENTER)
        
        // 底部状态栏
        val bottomPanel = JPanel(BorderLayout())
        bottomPanel.background = JBColor(Color(43, 45, 47), Color(37, 38, 40))
        bottomPanel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor(Color(60, 60, 60), Color(50, 50, 50))),
            BorderFactory.createEmptyBorder(5, 12, 5, 12)
        )
        
        val totalEntries = filterManager.getAllEntries().size
        val filteredEntries = filterManager.getFilteredEntries().size
        val statusText = if (totalEntries == 0) "No log entries" 
                         else "Showing $filteredEntries of $totalEntries entries"
        val resultsLabel = JLabel(statusText)
        resultsLabel.font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
        resultsLabel.foreground = dimTextColor
        bottomPanel.add(resultsLabel, BorderLayout.WEST)
        
        // 快捷键提示
        val hintLabel = JLabel("↑↓ Navigate  ↵ Select  Type to search")
        hintLabel.font = Font(Font.SANS_SERIF, Font.PLAIN, 10)
        hintLabel.foreground = JBColor(Color(90, 90, 90), Color(80, 80, 80))
        bottomPanel.add(hintLabel, BorderLayout.EAST)
        
        popupPanel.add(bottomPanel, BorderLayout.SOUTH)
        
        // 搜索框实时搜索
        var searchTimer: javax.swing.Timer? = null
        popupSearchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = scheduleSearch()
            override fun removeUpdate(e: DocumentEvent?) = scheduleSearch()
            override fun changedUpdate(e: DocumentEvent?) = scheduleSearch()
            
            private fun scheduleSearch() {
                searchTimer?.stop()
                searchTimer = javax.swing.Timer(150) {
                    val query = popupSearchField.text
                    if (query != "Search filters..." && query != searchQuery) {
                        showFilterSuggestionsWithSearch(query)
                    }
                }.apply { isRepeats = false; start() }
            }
        })
        
        // 搜索框键盘事件
        popupSearchField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent?) {
                when (e?.keyCode) {
                    KeyEvent.VK_UP -> {
                        if (selectableItems.isNotEmpty() && currentSelectedIndex > 0) {
                            selectableItems[currentSelectedIndex].background = bgColor
                            currentSelectedIndex--
                            selectableItems[currentSelectedIndex].background = selectedColor
                            selectableItems[currentSelectedIndex].scrollRectToVisible(
                                selectableItems[currentSelectedIndex].bounds
                            )
                        }
                        e.consume()
                    }
                    KeyEvent.VK_DOWN -> {
                        if (selectableItems.isNotEmpty() && currentSelectedIndex < selectableItems.size - 1) {
                            selectableItems[currentSelectedIndex].background = bgColor
                            currentSelectedIndex++
                            selectableItems[currentSelectedIndex].background = selectedColor
                            selectableItems[currentSelectedIndex].scrollRectToVisible(
                                selectableItems[currentSelectedIndex].bounds
                            )
                        }
                        e.consume()
                    }
                    KeyEvent.VK_ENTER -> {
                        if (selectableItems.isNotEmpty() && currentSelectedIndex >= 0 && currentSelectedIndex < selectableTexts.size) {
                            filterTextField.text = selectableTexts[currentSelectedIndex]
                            filterTextField.editor?.caretModel?.moveToOffset(filterTextField.text.length)
                            filterPopup?.cancel()
                            this@SerialPortToolWindow.applyFilter()
                        }
                        e.consume()
                    }
                    KeyEvent.VK_ESCAPE -> {
                        filterPopup?.cancel()
                        e.consume()
                    }
                }
            }
        })
        
        // 列表面板键盘导航
        popupPanel.isFocusable = true
        popupPanel.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent?) {
                when (e?.keyCode) {
                    KeyEvent.VK_UP -> {
                        if (selectableItems.isNotEmpty() && currentSelectedIndex > 0) {
                            selectableItems[currentSelectedIndex].background = bgColor
                            currentSelectedIndex--
                            selectableItems[currentSelectedIndex].background = selectedColor
                            selectableItems[currentSelectedIndex].scrollRectToVisible(
                                selectableItems[currentSelectedIndex].bounds
                            )
                        }
                        e.consume()
                    }
                    KeyEvent.VK_DOWN -> {
                        if (selectableItems.isNotEmpty() && currentSelectedIndex < selectableItems.size - 1) {
                            selectableItems[currentSelectedIndex].background = bgColor
                            currentSelectedIndex++
                            selectableItems[currentSelectedIndex].background = selectedColor
                            selectableItems[currentSelectedIndex].scrollRectToVisible(
                                selectableItems[currentSelectedIndex].bounds
                            )
                        }
                        e.consume()
                    }
                    KeyEvent.VK_ENTER -> {
                        if (selectableItems.isNotEmpty() && currentSelectedIndex >= 0 && currentSelectedIndex < selectableTexts.size) {
                            filterTextField.text = selectableTexts[currentSelectedIndex]
                            filterTextField.editor?.caretModel?.moveToOffset(filterTextField.text.length)
                            filterPopup?.cancel()
                            this@SerialPortToolWindow.applyFilter()
                        }
                        e.consume()
                    }
                    KeyEvent.VK_ESCAPE -> {
                        filterPopup?.cancel()
                        e.consume()
                    }
                }
            }
        })
        
        filterPopup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(popupPanel, popupSearchField)  // 聚焦到搜索框
            .setMovable(false)
            .setResizable(false)
            .setRequestFocus(true)
            .createPopup()
        
        filterPopup?.showUnderneathOf(filterTextField)
    }
    
    /**
     * 更新收藏按钮状态
     */
    private fun updateFavoriteButtonState() {
        val currentFilter = filterTextField.text.trim()
        val isFavorite = filterFavorites.contains(currentFilter)
        favoriteBtn?.icon = if (isFavorite) AllIcons.Nodes.Favorite else AllIcons.Nodes.NotFavoriteOnHover
    }
    
    /**
     * 计算日志中某个过滤条件的匹配数量
     */
    private fun countMatches(filterText: String): Int {
        val text = document.text
        if (text.isEmpty() || filterText.isEmpty()) return 0
        return try {
            Regex.escape(filterText).toRegex(RegexOption.IGNORE_CASE).findAll(text).count()
        } catch (e: Exception) {
            0
        }
    }
    
    /**
     * 格式化时间差为人类可读格式
     */
    private fun getTimeAgo(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        
        val seconds = diff / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24
        
        return when {
            seconds < 60 -> "just now"
            minutes < 60 -> "${minutes}m ago"
            hours < 24 -> "${hours}h ago"
            days < 7 -> "${days}d ago"
            else -> {
                val sdf = java.text.SimpleDateFormat("MM/dd", java.util.Locale.getDefault())
                sdf.format(java.util.Date(timestamp))
            }
        }
    }
    
    /**
     * 显示过滤语法帮助
     */
    /**
     * 显示空的历史记录弹窗（Logcat 风格）
     */
    private fun showEmptyHistoryPopup() {
        val bgColor = JBColor(Color(49, 51, 53), Color(43, 43, 43))
        val textColor = JBColor(Color(140, 140, 140), Color(110, 110, 110))
        val hintColor = JBColor(Color(100, 100, 100), Color(85, 85, 85))
        
        val popupPanel = JPanel(BorderLayout())
        popupPanel.background = bgColor
        popupPanel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor(Color(65, 65, 65), Color(55, 55, 55))),
            BorderFactory.createEmptyBorder(16, 20, 16, 20)
        )
        
        // 内容面板
        val contentPanel = JPanel()
        contentPanel.layout = BoxLayout(contentPanel, BoxLayout.Y_AXIS)
        contentPanel.background = bgColor
        contentPanel.alignmentX = Component.CENTER_ALIGNMENT
        
        // 图标
        val iconLabel = JLabel(AllIcons.General.Information)
        iconLabel.alignmentX = Component.CENTER_ALIGNMENT
        contentPanel.add(iconLabel)
        contentPanel.add(Box.createVerticalStrut(8))
        
        // 主文本
        val emptyLabel = JLabel("No filter history")
        emptyLabel.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        emptyLabel.foreground = textColor
        emptyLabel.alignmentX = Component.CENTER_ALIGNMENT
        contentPanel.add(emptyLabel)
        contentPanel.add(Box.createVerticalStrut(4))
        
        // 提示文本
        val hintLabel = JLabel("Type a filter and press Enter to add")
        hintLabel.font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
        hintLabel.foreground = hintColor
        hintLabel.alignmentX = Component.CENTER_ALIGNMENT
        contentPanel.add(hintLabel)
        
        popupPanel.add(contentPanel, BorderLayout.CENTER)
        popupPanel.preferredSize = Dimension(260, 90)
        
        filterPopup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(popupPanel, null)
            .setMovable(false)
            .setResizable(false)
            .setRequestFocus(false)
            .createPopup()
        
        filterPopup?.showUnderneathOf(filterTextField)
    }
    
    private fun showFilterSyntaxHelp() {
        val bgColor = JBColor(Color(43, 43, 43), Color(43, 43, 43))
        val hoverColor = JBColor(Color(60, 63, 65), Color(60, 63, 65))
        
        val popupPanel = JPanel(BorderLayout())
        popupPanel.background = bgColor
        popupPanel.border = BorderFactory.createLineBorder(JBColor.border())
        
        val listPanel = JPanel()
        listPanel.layout = BoxLayout(listPanel, BoxLayout.Y_AXIS)
        listPanel.background = bgColor
        
        // 标题
        val headerPanel = JPanel(BorderLayout())
        headerPanel.background = bgColor
        headerPanel.border = BorderFactory.createEmptyBorder(6, 10, 6, 10)
        headerPanel.maximumSize = Dimension(Int.MAX_VALUE, 28)
        val headerLabel = JLabel("📖 Filter Syntax (Logcat Style)")
        headerLabel.font = Font(Font.SANS_SERIF, Font.BOLD, 12)
        headerLabel.foreground = JBColor(Color(100, 149, 237), Color(100, 149, 237))
        headerPanel.add(headerLabel, BorderLayout.WEST)
        listPanel.add(headerPanel)
        
        // 分隔线
        val sep = JSeparator()
        sep.maximumSize = Dimension(Int.MAX_VALUE, 1)
        listPanel.add(sep)
        
        // 语法列表
        filterSuggestions.forEach { suggestion ->
            val itemPanel = JPanel(BorderLayout(8, 0))
            itemPanel.background = bgColor
            itemPanel.border = BorderFactory.createEmptyBorder(4, 12, 4, 12)
            itemPanel.maximumSize = Dimension(Int.MAX_VALUE, 32)
            
            // 语法
            val syntaxLabel = JLabel(suggestion.syntax)
            syntaxLabel.font = Font(Font.MONOSPACED, Font.BOLD, 12)
            syntaxLabel.foreground = JBColor(Color(78, 201, 176), Color(78, 201, 176))
            syntaxLabel.preferredSize = Dimension(120, 20)
            itemPanel.add(syntaxLabel, BorderLayout.WEST)
            
            // 描述
            val descLabel = JLabel(suggestion.description)
            descLabel.font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
            descLabel.foreground = JBColor(Color(150, 150, 150), Color(150, 150, 150))
            itemPanel.add(descLabel, BorderLayout.CENTER)
            
            // 示例
            val exampleLabel = JLabel(suggestion.example)
            exampleLabel.font = Font(Font.MONOSPACED, Font.ITALIC, 10)
            exampleLabel.foreground = JBColor.GRAY
            itemPanel.add(exampleLabel, BorderLayout.EAST)
            
            // 鼠标悬停和点击
            itemPanel.addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent?) {
                    itemPanel.background = hoverColor
                }
                override fun mouseExited(e: MouseEvent?) {
                    itemPanel.background = bgColor
                }
                override fun mouseClicked(e: MouseEvent?) {
                    filterTextField.text = suggestion.syntax
                    filterTextField.editor?.caretModel?.moveToOffset(filterTextField.text.length)
                    filterPopup?.cancel()
                }
            })
            
            listPanel.add(itemPanel)
        }
        
        val scrollPane = JBScrollPane(listPanel)
        scrollPane.border = BorderFactory.createEmptyBorder()
        scrollPane.preferredSize = Dimension(500, minOf((filterSuggestions.size + 1) * 32 + 20, 350))
        popupPanel.add(scrollPane, BorderLayout.CENTER)
        
        // 底部提示
        val footerPanel = JPanel(FlowLayout(FlowLayout.LEFT, 10, 4))
        footerPanel.background = bgColor
        val tipLabel = JLabel("💡 Tip: Use Ctrl+Space for suggestions while typing")
        tipLabel.font = Font(Font.SANS_SERIF, Font.ITALIC, 10)
        tipLabel.foreground = JBColor.GRAY
        footerPanel.add(tipLabel)
        popupPanel.add(footerPanel, BorderLayout.SOUTH)
        
        filterPopup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(popupPanel, null)
            .setMovable(false)
            .setResizable(false)
            .setRequestFocus(true)
            .createPopup()
        
        filterPopup?.showUnderneathOf(filterTextField)
    }
    
    /**
     * 添加到过滤器历史记录
     */
    private fun addToFilterHistory(filter: String) {
        if (filter.isBlank()) return
        // 移除重复项
        filterHistory.remove(filter)
        // 添加到开头
        filterHistory.add(0, filter)
        // 记录使用时间
        filterUsageTime[filter] = System.currentTimeMillis()
        // 限制历史记录数量
        while (filterHistory.size > maxFilterHistory) {
            val removed = filterHistory.removeAt(filterHistory.size - 1)
            filterUsageTime.remove(removed)
        }
    }
    
    /**
     * 清除过滤器历史记录
     */
    private fun clearFilterHistory() {
        filterHistory.clear()
    }
    
    /**
     * 自动补全建议项 (Logcat 风格)
     */
    data class CompletionItem(val text: String, val hint: String?)
    
    /**
     * 根据输入自动显示匹配的建议 (Logcat 风格自动补全)
     */
    private fun showAutoCompleteSuggestions() {
        val text = filterTextField.text
        if (text.isEmpty()) {
            filterPopup?.cancel()
            return
        }
        
        // 获取当前正在输入的词（最后一个空格后的内容）
        val lastSpaceIndex = text.lastIndexOf(' ')
        val currentWord = if (lastSpaceIndex >= 0) text.substring(lastSpaceIndex + 1) else text
        
        if (currentWord.isEmpty()) {
            filterPopup?.cancel()
            return
        }
        
        // 过滤匹配的建议（历史记录 + 语法）
        val matchedItems = mutableListOf<CompletionItem>()
        
        // 匹配语法建议 (带提示)
        filterSuggestions.filter { it.syntax.startsWith(currentWord, ignoreCase = true) }
            .forEach { matchedItems.add(CompletionItem(it.syntax, it.description)) }
        
        // 匹配历史记录
        filterHistory.filter { it.contains(currentWord, ignoreCase = true) }
            .forEach { 
                if (matchedItems.none { item -> item.text == it }) {
                    matchedItems.add(CompletionItem(it, "History"))
                }
            }
        
        if (matchedItems.isEmpty()) {
            filterPopup?.cancel()
            return
        }
        
        // 显示自动补全弹窗 (Logcat 风格)
        filterPopup?.cancel()
        
        val listModel = DefaultListModel<CompletionItem>()
        matchedItems.forEach { listModel.addElement(it) }
        
        val list = JBList(listModel)
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.background = JBColor(Color(43, 43, 43), Color(43, 43, 43))
        list.foreground = JBColor(Color(187, 187, 187), Color(187, 187, 187))
        if (listModel.size() > 0) list.selectedIndex = 0
        
        // Logcat 风格的渲染器 (显示语法 + 提示)
        list.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): java.awt.Component {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                
                val item = value as? CompletionItem ?: return this
                
                // 创建带语法和提示的面板
                val panel = JPanel(BorderLayout())
                panel.isOpaque = true
                panel.background = if (isSelected) {
                    JBColor(Color(45, 91, 138), Color(45, 91, 138))
                } else {
                    JBColor(Color(43, 43, 43), Color(43, 43, 43))
                }
                
                val textLabel = JLabel(item.text)
                textLabel.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
                textLabel.foreground = if (isSelected) Color.WHITE else JBColor(Color(187, 187, 187), Color(187, 187, 187))
                textLabel.border = BorderFactory.createEmptyBorder(2, 8, 2, 8)
                
                val hintLabel = JLabel(item.hint ?: "")
                hintLabel.font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
                hintLabel.foreground = JBColor(Color(128, 128, 128), Color(120, 120, 120))
                hintLabel.border = BorderFactory.createEmptyBorder(2, 8, 2, 8)
                
                panel.add(textLabel, BorderLayout.WEST)
                panel.add(hintLabel, BorderLayout.EAST)
                
                return panel
            }
        }
        
        filterPopup = JBPopupFactory.getInstance()
            .createListPopupBuilder(list)
            .setMovable(false)
            .setResizable(false)
            .setRequestFocus(false)
            .setItemChosenCallback { selected ->
                val item = selected as? CompletionItem ?: return@setItemChosenCallback
                val currentText = filterTextField.text
                val lastSpaceIdx = currentText.lastIndexOf(' ')
                val newText = if (lastSpaceIdx >= 0) {
                    currentText.substring(0, lastSpaceIdx + 1) + item.text
                } else {
                    item.text
                }
                filterTextField.text = newText
                filterTextField.editor?.caretModel?.moveToOffset(newText.length)
                filterTextField.requestFocus()
            }
            .createPopup()
        
        filterPopup?.showUnderneathOf(filterTextField)
    }
    
    private fun updateToggleButton(button: JButton, selected: Boolean) {
        button.border = if (selected) {
            BorderFactory.createLineBorder(JBColor(Color(70, 130, 180), Color(100, 149, 237)), 2)
        } else {
            BorderFactory.createEmptyBorder(2, 2, 2, 2)
        }
    }
    
    /**
     * 创建日志显示区
     */
    private fun createLogPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        
        // === 搜索栏 (Ctrl+F 显示，默认隐藏) ===
        searchBar = createSearchBar()
        searchBar!!.isVisible = false
        panel.add(searchBar, BorderLayout.NORTH)
        
        // 使用 EditorEx 替代 JTextPane (Logcat 风格)
        val editorComp = editor.component
        // Logcat 风格的边框：顶部有边框线
        editorComp.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor.border()),
            BorderFactory.createEmptyBorder()
        )
        
        // Keep auto-scroll state synced with viewport position.
        // If user scrolls up -> autoScroll=false; scrolls back to bottom -> autoScroll=true.
        editor.scrollPane.verticalScrollBar.addAdjustmentListener {
            val bar = editor.scrollPane.verticalScrollBar
            val atBottom = bar.value + bar.visibleAmount >= bar.maximum - 2
            if (autoScroll != atBottom) {
                autoScroll = atBottom
                autoScrollBtn?.let { btn -> updateToggleButton(btn, autoScroll) }
            }
        }
        
        panel.add(editorComp, BorderLayout.CENTER)
        return panel
    }
    
    /**
     * 创建搜索栏 (Logcat 风格 - Ctrl+F)
     */
    private fun createSearchBar(): JPanel {
        val bar = JPanel(BorderLayout(4, 0))
        bar.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
            BorderFactory.createEmptyBorder(4, 8, 4, 8)
        )
        bar.background = JBColor(Color(60, 63, 65), Color(60, 63, 65))
        
        // 左侧：展开按钮 + 搜索输入框
        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        leftPanel.isOpaque = false
        
        // 展开/折叠按钮
        val expandBtn = JButton(AllIcons.General.ArrowRight)
        expandBtn.preferredSize = Dimension(20, 20)
        expandBtn.isBorderPainted = false
        expandBtn.isContentAreaFilled = false
        expandBtn.toolTipText = "展开/折叠"
        leftPanel.add(expandBtn)
        
        // 搜索图标
        val searchIcon = JLabel(AllIcons.Actions.Search)
        leftPanel.add(searchIcon)
        
        // 搜索输入框
        searchField.preferredSize = Dimension(200, 24)
        searchField.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        searchField.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor.border()),
            BorderFactory.createEmptyBorder(2, 4, 2, 4)
        )
        leftPanel.add(searchField)
        
        // 搜索历史下拉按钮
        val historyBtn = createSearchBarButton(AllIcons.Actions.SearchWithHistory, "搜索历史")
        historyBtn.addActionListener { showSearchHistory() }
        leftPanel.add(historyBtn)
        
        bar.add(leftPanel, BorderLayout.WEST)
        
        // 中间：选项按钮
        val optionsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0))
        optionsPanel.isOpaque = false
        
        // 刷新按钮
        val refreshBtn = createSearchBarButton(AllIcons.Actions.Refresh, "刷新搜索")
        refreshBtn.addActionListener { performSearch() }
        optionsPanel.add(refreshBtn)
        
        // Cc 大小写敏感
        val ccBtn = createSearchBarToggleButton("Cc", "大小写敏感", caseSensitive)
        ccBtn.addActionListener {
            caseSensitive = !caseSensitive
            updateSearchBarToggleButton(ccBtn, caseSensitive)
            performSearch()
        }
        optionsPanel.add(ccBtn)
        
        // W 全词匹配
        val wordBtn = createSearchBarToggleButton("W", "全词匹配", wholeWord)
        wordBtn.addActionListener {
            wholeWord = !wholeWord
            updateSearchBarToggleButton(wordBtn, wholeWord)
            performSearch()
        }
        optionsPanel.add(wordBtn)
        
        // .* 正则表达式
        val regexBtn = createSearchBarToggleButton(".*", "正则表达式", useRegex)
        regexBtn.addActionListener {
            useRegex = !useRegex
            updateSearchBarToggleButton(regexBtn, useRegex)
            performSearch()
        }
        optionsPanel.add(regexBtn)
        
        // 分隔符
        optionsPanel.add(JSeparator(SwingConstants.VERTICAL).apply {
            preferredSize = Dimension(1, 16)
        })
        
        // 结果计数
        searchResultLabel = JLabel("0 results")
        searchResultLabel!!.foreground = JBColor.GRAY
        optionsPanel.add(searchResultLabel)
        
        // 上一个/下一个
        val prevBtn = createSearchBarButton(AllIcons.Actions.PreviousOccurence, "上一个 (Shift+F3)")
        prevBtn.addActionListener { navigateSearch(-1) }
        optionsPanel.add(prevBtn)
        
        val nextBtn = createSearchBarButton(AllIcons.Actions.NextOccurence, "下一个 (F3)")
        nextBtn.addActionListener { navigateSearch(1) }
        optionsPanel.add(nextBtn)
        
        bar.add(optionsPanel, BorderLayout.CENTER)
        
        // 右侧：关闭按钮
        val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
        rightPanel.isOpaque = false
        
        val closeBtn = createSearchBarButton(AllIcons.Actions.Close, "关闭 (Esc)")
        closeBtn.addActionListener { hideSearchBar() }
        rightPanel.add(closeBtn)
        
        bar.add(rightPanel, BorderLayout.EAST)
        
        // 搜索输入框事件
        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = performSearch()
            override fun removeUpdate(e: DocumentEvent) = performSearch()
            override fun changedUpdate(e: DocumentEvent) = performSearch()
        })
        
        // Enter 下一个，Shift+Enter 上一个
        searchField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when {
                    e.keyCode == KeyEvent.VK_ENTER && e.isShiftDown -> navigateSearch(-1)
                    e.keyCode == KeyEvent.VK_ENTER -> navigateSearch(1)
                    e.keyCode == KeyEvent.VK_ESCAPE -> hideSearchBar()
                }
            }
        })
        
        return bar
    }
    
    private fun createSearchBarButton(icon: Icon, tooltip: String): JButton {
        return JButton(icon).apply {
            preferredSize = Dimension(22, 22)
            toolTipText = tooltip
            isBorderPainted = false
            isContentAreaFilled = false
        }
    }
    
    private fun createSearchBarToggleButton(text: String, tooltip: String, selected: Boolean): JButton {
        return JButton(text).apply {
            preferredSize = Dimension(26, 22)
            toolTipText = tooltip
            font = Font(Font.MONOSPACED, Font.BOLD, 11)
            isBorderPainted = true
            isContentAreaFilled = false
            updateSearchBarToggleButton(this, selected)
        }
    }
    
    private fun updateSearchBarToggleButton(button: JButton, selected: Boolean) {
        if (selected) {
            button.border = BorderFactory.createLineBorder(JBColor(Color(70, 130, 180), Color(100, 149, 237)), 1)
            button.foreground = JBColor(Color(70, 130, 180), Color(100, 149, 237))
        } else {
            button.border = BorderFactory.createEmptyBorder(1, 1, 1, 1)
            button.foreground = JBColor.GRAY
        }
    }
    
    /**
     * 显示搜索栏
     */
    private fun showSearchBar() {
        searchBar?.isVisible = true
        searchField.requestFocusInWindow()
        searchField.selectAll()
    }
    
    /**
     * 隐藏搜索栏
     */
    private fun hideSearchBar() {
        searchBar?.isVisible = false
        clearSearchHighlights()
    }
    
    /**
     * 执行搜索
     */
    private fun performSearch() {
        clearSearchHighlights()
        searchResults.clear()
        currentSearchIndex = -1
        
        val searchText = searchField.text
        if (searchText.isEmpty()) {
            searchResultLabel?.text = "0 results"
            return
        }
        
        val text = document.text
        val pattern = try {
            when {
                useRegex -> searchText.toRegex(if (caseSensitive) setOf() else setOf(RegexOption.IGNORE_CASE))
                wholeWord -> "\\b${Regex.escape(searchText)}\\b".toRegex(if (caseSensitive) setOf() else setOf(RegexOption.IGNORE_CASE))
                else -> Regex.escape(searchText).toRegex(if (caseSensitive) setOf() else setOf(RegexOption.IGNORE_CASE))
            }
        } catch (e: Exception) {
            searchResultLabel?.text = "Invalid regex"
            return
        }
        
        // 查找所有匹配
        pattern.findAll(text).forEach { match ->
            searchResults.add(match.range.first)
            highlightSearchResult(match.range.first, match.range.last + 1)
        }
        
        searchResultLabel?.text = "${searchResults.size} results"
        
        // 跳转到第一个结果
        if (searchResults.isNotEmpty()) {
            currentSearchIndex = 0
            navigateToResult(currentSearchIndex)
        }
    }
    
    /**
     * 高亮搜索结果
     */
    private fun highlightSearchResult(start: Int, end: Int) {
        val markupModel = com.intellij.openapi.editor.impl.DocumentMarkupModel.forDocument(document, project, true)
        try {
            markupModel.addRangeHighlighter(
                start,
                end,
                com.intellij.openapi.editor.markup.HighlighterLayer.SELECTION - 1, // 使用 SELECTION 层之前的一层
                com.intellij.openapi.editor.markup.TextAttributes(
                    null,
                    JBColor(Color(255, 200, 0, 100), Color(100, 100, 0, 150)),
                    null,
                    null,
                    0
                ),
                com.intellij.openapi.editor.markup.HighlighterTargetArea.EXACT_RANGE
            )
        } catch (e: Exception) {
            // 忽略高亮错误
        }
    }
    
    /**
     * 清除搜索高亮
     */
    private fun clearSearchHighlights() {
        val markupModel = com.intellij.openapi.editor.impl.DocumentMarkupModel.forDocument(document, project, true)
        markupModel.removeAllHighlighters()
    }
    
    /**
     * 导航搜索结果
     */
    private fun navigateSearch(direction: Int) {
        if (searchResults.isEmpty()) return
        
        // 首次导航时保存到搜索历史
        val searchText = searchField.text
        if (searchText.isNotBlank()) {
            addToSearchHistory(searchText)
        }
        
        currentSearchIndex += direction
        if (currentSearchIndex < 0) currentSearchIndex = searchResults.size - 1
        if (currentSearchIndex >= searchResults.size) currentSearchIndex = 0
        
        navigateToResult(currentSearchIndex)
    }
    
    /**
     * 跳转到指定搜索结果
     */
    private fun navigateToResult(index: Int) {
        if (index < 0 || index >= searchResults.size) return
        
        val position = searchResults[index]
        editor.caretModel.moveToOffset(position)
        editor.selectionModel.removeSelection()
        
        // 确保可见
        editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
        
        // 更新结果标签
        searchResultLabel?.text = "${index + 1}/${searchResults.size}"
    }
    
    /**
     * 添加到搜索历史
     */
    private fun addToSearchHistory(text: String) {
        if (text.isBlank()) return
        searchHistory.remove(text)
        searchHistory.add(0, text)
        while (searchHistory.size > maxSearchHistory) {
            searchHistory.removeAt(searchHistory.size - 1)
        }
    }
    
    /**
     * 显示搜索历史下拉列表
     */
    private fun showSearchHistory() {
        if (searchHistory.isEmpty()) {
            // 显示空历史提示
            JBPopupFactory.getInstance()
                .createMessage("无搜索历史")
                .showUnderneathOf(searchField)
            return
        }
        
        val listModel = DefaultListModel<String>()
        searchHistory.forEach { listModel.addElement(it) }
        
        val list = JBList(listModel)
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        list.background = JBColor(Color(43, 43, 43), Color(43, 43, 43))
        list.foreground = JBColor(Color(187, 187, 187), Color(187, 187, 187))
        if (listModel.size() > 0) list.selectedIndex = 0
        
        JBPopupFactory.getInstance()
            .createListPopupBuilder(list)
            .setTitle("搜索历史 (${searchHistory.size})")
            .setMovable(false)
            .setResizable(false)
            .setRequestFocus(true)
            .setItemChosenCallback { selected ->
                searchField.text = selected as String
                performSearch()
                addToSearchHistory(selected)
            }
            .setAdText("Enter 选择 | 右键清除历史", SwingConstants.LEFT)
            .createPopup()
            .showUnderneathOf(searchField)
    }
    
    /**
     * 创建底部发送栏
     */
    private fun createBottomBar(): JPanel {
        val mainBottomPanel = JPanel(BorderLayout(0, 0))
        mainBottomPanel.background = JBColor.background()
        
        // === 发送栏 ===
        val sendPanel = JPanel(BorderLayout(4, 0))
        sendPanel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor.border()),
            BorderFactory.createEmptyBorder(4, 8, 4, 8)
        )
        sendPanel.background = JBColor.background()
        
        val label = JLabel("TX:")
        label.foreground = JBColor(Color(0, 102, 204), Color(100, 149, 237))
        sendPanel.add(label, BorderLayout.WEST)
        
        sendField.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor.border()),
            BorderFactory.createEmptyBorder(4, 6, 4, 6)
        )
        sendPanel.add(sendField, BorderLayout.CENTER)
        
        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        buttonPanel.isOpaque = false

        hexModeCheckBox.toolTipText = "HEX模式发送"
        buttonPanel.add(hexModeCheckBox)
        
        val sendBtn = JButton("发送")
        sendBtn.preferredSize = Dimension(60, 28)
        sendBtn.addActionListener { sendData() }
        buttonPanel.add(sendBtn)
        
        sendPanel.add(buttonPanel, BorderLayout.EAST)
        mainBottomPanel.add(sendPanel, BorderLayout.CENTER)
        
        // === 状态栏 (Logcat 风格统计) ===
        val statusBar = JPanel(BorderLayout())
        statusBar.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor.border()),
            BorderFactory.createEmptyBorder(2, 8, 2, 8)
        )
        statusBar.background = JBColor(Color(45, 45, 45), Color(45, 45, 45))
        
        // 左侧：日志统计
        statusLabel = JLabel("📊 Total: 0 | Filtered: 0 | TX: 0 | RX: 0")
        statusLabel?.font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        statusLabel?.foreground = JBColor(Color(150, 150, 150), Color(150, 150, 150))
        statusBar.add(statusLabel, BorderLayout.WEST)
        
        // 右侧：视图模式指示
        val viewModeLabel = JLabel("Standard View")
        viewModeLabel.font = Font(Font.SANS_SERIF, Font.ITALIC, 10)
        viewModeLabel.foreground = JBColor.GRAY
        statusBar.add(viewModeLabel, BorderLayout.EAST)
        
        mainBottomPanel.add(statusBar, BorderLayout.SOUTH)
        
        return mainBottomPanel
    }
    
    /**
     * 更新状态栏统计信息
     */
    private fun updateStatusBar() {
        val total = filterManager.getTotalCount()
        val filtered = filterManager.getFilteredCount()
        val allEntries = filterManager.getAllEntries()
        val txCount = allEntries.count { it.direction == "TX" }
        val rxCount = allEntries.count { it.direction == "RX" }
        
        statusLabel?.text = "📊 Total: $total | Filtered: $filtered | TX: $txCount | RX: $rxCount"
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
        
        // 过滤器实时应用 (参考 Logcat FilterTextField)
        filterTextField.addDocumentListener(object : com.intellij.openapi.editor.event.DocumentListener {
            override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                // 从事件获取最新文本 (重要！filterTextField.text 可能还没更新)
                val filterText = event.document.text
                
                // 更新收藏按钮状态
                val isFav = filterFavorites.contains(filterText)
                favoriteBtn?.icon = if (isFav) AllIcons.Nodes.Favorite else AllIcons.Nodes.NotFavoriteOnHover
                
                // 延迟应用过滤器 (避免频繁刷新)
                filterTimer?.stop()
                filterTimer = Timer(150) {
                    val filter = parseFilterExpression(filterText)
                    filterManager.setActiveFilter(filter)
                    refreshDisplay()
                }.apply {
                    isRepeats = false
                    start()
            }
            
                // 延迟显示自动补全建议
                suggestionTimer?.stop()
                suggestionTimer = Timer(100) {
                    showAutoCompleteSuggestions()
                }.apply {
                    isRepeats = false
                    start()
                }
            }
        })
        
        // 在 EditorTextField 添加到 UI 后添加 KeyListener
        filterTextField.addHierarchyListener { e ->
            if ((e.changeFlags and java.awt.event.HierarchyEvent.SHOWING_CHANGED.toLong()) != 0L) {
                if (filterTextField.isShowing) {
                    filterTextField.editor?.contentComponent?.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                            when (e.keyCode) {
                                KeyEvent.VK_ESCAPE -> {
                                    filterTextField.text = ""
                    applyFilter()
                                    e.consume()
                                }
                                KeyEvent.VK_DOWN -> {
                                    if (filterTextField.text.isEmpty()) {
                    showFilterSuggestions()
                                        e.consume()
                                    }
                                }
                                KeyEvent.VK_ENTER -> {
                                    // 保存到历史
                                    if (filterTextField.text.isNotBlank()) {
                                        addToFilterHistory(filterTextField.text)
                                    }
                                    e.consume()
                                }
                            }
                        }
                    })
                }
            }
        }
        
        // 快捷键
        setupKeyboardShortcuts()
    }
    
    private fun setupKeyboardShortcuts() {
        // Use WHEN_IN_FOCUSED_WINDOW so Ctrl+F works even when focus is inside EditorEx.
        val inputMap = mainPanel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        val actionMap = mainPanel.actionMap
        
        // Ctrl+L 清空
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_L, Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx), "clearLogs")
        actionMap.put("clearLogs", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) = filterManager.clearLogs()
        })
        
        // Ctrl+F 显示搜索栏 (Logcat 风格)
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_F, Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx), "showSearch")
        actionMap.put("showSearch", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) = showSearchBar()
        })

        // Fallback for EditorEx: some IDE key handlers may consume Ctrl+F first.
        editor.contentComponent.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                val isMenuShortcut = (e.modifiersEx and Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx) != 0
                if (isMenuShortcut && e.keyCode == KeyEvent.VK_F) {
                    showSearchBar()
                    e.consume()
                }
            }
        })
        
        // F3 下一个搜索结果
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_F3, 0), "nextSearch")
        actionMap.put("nextSearch", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) = navigateSearch(1)
        })
        
        // Shift+F3 上一个搜索结果
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_F3, InputEvent.SHIFT_DOWN_MASK), "prevSearch")
        actionMap.put("prevSearch", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) = navigateSearch(-1)
        })
        
        // Escape 关闭搜索栏
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeSearch")
        actionMap.put("closeSearch", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                if (searchBar?.isVisible == true) {
                    hideSearchBar()
                }
            }
        })
    }
    
    private fun updateConnectButton() {
        if (isConnected) {
            connectButton.text = "断开"
            connectButton.foreground = JBColor.RED
        } else {
            connectButton.text = "连接"
            connectButton.foreground = JBColor(Color(210, 90, 70), Color(240, 120, 95))
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
                coroutineScope.launch(Dispatchers.EDT) {
                    val message = SerialPortMessage(
                        timestamp = Instant.now(),
                        direction = SerialPortMessage.Direction.SYS,
                        level = SerialPortMessage.LogLevel.WARN,
                        content = diagInfo.toString()
                    )
                    processMessages(listOf(message))
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
        val filterText = filterTextField.text
        // 保存到历史记录
        if (filterText.isNotBlank()) {
            addToFilterHistory(filterText)
        }
        val filter = parseFilterExpression(filterText)
        filterManager.setActiveFilter(filter)
        refreshDisplay()
    }
    
    /**
     * 从日志级别下拉框获取最低级别 (Logcat 风格)
     */
    private fun getSelectedLogLevel(): LogLevel {
        return when (logLevelComboBox.selectedIndex) {
            0 -> LogLevel.VERBOSE
            1 -> LogLevel.DEBUG
            2 -> LogLevel.INFO
            3 -> LogLevel.WARN
            4 -> LogLevel.ERROR
            else -> LogLevel.VERBOSE
        }
    }
    
    private fun parseFilterExpression(expression: String): FilterCondition {
        var keyword = ""
        var isRegex = false
        var isExact = false
        var isImplicitLine = true  // 默认搜索整行 (Logcat IMPLICIT_LINE)
        var excludeKeyword = ""
        var excludeIsRegex = false
        var excludeIsExact = false
        var showTx = true
        var showRx = true
        var showSys = true
        var maxAgeMs: Long = 0
        var onlyCrash = false
        var onlyStacktrace = false
        // 从下拉框获取级别 (Logcat 风格)
        var minLevel = getSelectedLogLevel()
        
        if (expression.isEmpty()) {
            return FilterCondition(minLevel = minLevel)
        }
        
        val plainTerms = mutableListOf<String>()
        val parts = tokenizeFilterExpression(expression)
        for (part in parts) {
            when {
                // 运算符与括号（当前实现为兼容忽略）
                part == "&" || part == "|" || part == "(" || part == ")" -> Unit
                // 排除过滤 (Logcat 风格)
                part.startsWith("-message~:") -> {
                    excludeKeyword = unquoteFilterValue(part.substringAfter("-message~:"))
                    excludeIsRegex = true
                }
                part.startsWith("-message=:") -> {
                    excludeKeyword = unquoteFilterValue(part.substringAfter("-message=:"))
                    excludeIsExact = true
                }
                part.startsWith("-message:") -> excludeKeyword = unquoteFilterValue(part.substringAfter("-message:"))
                part.startsWith("-line~:") -> {
                    excludeKeyword = unquoteFilterValue(part.substringAfter("-line~:"))
                    excludeIsRegex = true
                }
                part.startsWith("-line=:") -> {
                    excludeKeyword = unquoteFilterValue(part.substringAfter("-line=:"))
                    excludeIsExact = true
                }
                part.startsWith("-line:") -> excludeKeyword = unquoteFilterValue(part.substringAfter("-line:"))
                // 包含过滤 (Logcat 风格) - 只搜索消息内容
                part.startsWith("message~:") -> { 
                    keyword = unquoteFilterValue(part.substringAfter("message~:"))
                    isRegex = true
                    isImplicitLine = false  // 只搜索消息
                }
                part.startsWith("message=:") -> { 
                    keyword = unquoteFilterValue(part.substringAfter("message=:"))
                    isExact = true
                    isImplicitLine = false  // 只搜索消息
                }
                part.startsWith("message:") -> {
                    keyword = unquoteFilterValue(part.substringAfter("message:"))
                    isImplicitLine = false  // 只搜索消息
                }
                // line: 过滤（和 Logcat 一样作用于整行）
                part.startsWith("line~:") -> {
                    keyword = unquoteFilterValue(part.substringAfter("line~:"))
                    isRegex = true
                    isImplicitLine = true
                }
                part.startsWith("line=:") -> {
                    keyword = unquoteFilterValue(part.substringAfter("line=:"))
                    isExact = true
                    isImplicitLine = true
                }
                part.startsWith("line:") -> {
                    keyword = unquoteFilterValue(part.substringAfter("line:"))
                    isImplicitLine = true
                }
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
                // 文本级别过滤 (可覆盖下拉框)
                part.startsWith("level:") -> {
                    minLevel = when (part.substringAfter("level:").uppercase()) {
                        "V", "VERBOSE" -> LogLevel.VERBOSE
                        "D", "DEBUG" -> LogLevel.DEBUG
                        "I", "INFO" -> LogLevel.INFO
                        "W", "WARN" -> LogLevel.WARN
                        "E", "ERROR" -> LogLevel.ERROR
                        else -> minLevel
                    }
                }
                // 时间范围过滤 (Logcat age: 语法)
                part.startsWith("age:") -> {
                    maxAgeMs = LogFilterManager.parseAge(part.substringAfter("age:"))
                }
                // 特殊过滤 (Logcat is: 语法)
                part == "is:crash" -> onlyCrash = true
                part == "is:stacktrace" -> onlyStacktrace = true
                // 普通关键词 - 搜索整行 (Logcat IMPLICIT_LINE)
                else -> if (part.isNotEmpty() && !part.contains(":")) {
                    plainTerms.add(unquoteFilterValue(part))
                }
            }
        }

        // Logcat 风格：连续普通词条合并为一个整行查询（foo bar => "foo bar"）
        if (keyword.isEmpty() && plainTerms.isNotEmpty()) {
            keyword = plainTerms.joinToString(" ")
            isImplicitLine = true
        }

        // 对齐 Logcat 行为：正则非法时退化为整行普通字符串查询，而不是“匹配全部”
        if (isRegex && keyword.isNotEmpty()) {
            val valid = try {
                Regex(keyword, RegexOption.IGNORE_CASE)
                true
            } catch (_: Exception) {
                false
            }
            if (!valid) {
                return FilterCondition(
                    keyword = expression.trim(),
                    isRegex = false,
                    isExact = false,
                    matchCase = filterMatchCase,
                    isImplicitLine = true,
                    minLevel = minLevel,
                    showTx = showTx,
                    showRx = showRx,
                    showSystem = showSys,
                    maxAgeMs = maxAgeMs,
                    onlyCrash = onlyCrash,
                    onlyStacktrace = onlyStacktrace
                )
            }
        }
        if (excludeIsRegex && excludeKeyword.isNotEmpty()) {
            val valid = try {
                Regex(excludeKeyword, RegexOption.IGNORE_CASE)
                true
            } catch (_: Exception) {
                false
            }
            if (!valid) {
                excludeIsRegex = false
            }
        }
        
        return FilterCondition(
            keyword = keyword,
            isRegex = isRegex,
            isExact = isExact,
            matchCase = filterMatchCase,
            isImplicitLine = isImplicitLine,
            excludeKeyword = excludeKeyword,
            excludeIsRegex = excludeIsRegex,
            excludeIsExact = excludeIsExact,
            minLevel = minLevel,
            showTx = showTx,
            showRx = showRx,
            showSystem = showSys,
            maxAgeMs = maxAgeMs,
            onlyCrash = onlyCrash,
            onlyStacktrace = onlyStacktrace
        )
    }

    private fun tokenizeFilterExpression(expression: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quoteChar: Char? = null
        var escaped = false

        fun flush() {
            if (current.isNotEmpty()) {
                tokens.add(current.toString())
                current.clear()
            }
        }

        for (ch in expression) {
            if (escaped) {
                current.append(ch)
                escaped = false
                continue
            }
            if (ch == '\\') {
                escaped = true
                current.append(ch)
                continue
            }

            if (quoteChar != null) {
                current.append(ch)
                if (ch == quoteChar) {
                    quoteChar = null
                }
                continue
            }

            when {
                ch == '\'' || ch == '"' -> {
                    quoteChar = ch
                    current.append(ch)
                }
                ch.isWhitespace() -> flush()
                else -> current.append(ch)
            }
        }
        flush()
        return tokens
    }

    private fun unquoteFilterValue(value: String): String {
        val trimmed = value.trim()
        if (trimmed.length < 2) return trimmed
        val first = trimmed.first()
        val last = trimmed.last()
        if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            val raw = trimmed.substring(1, trimmed.length - 1)
            return raw
                .replace("\\\\", "\\")
                .replace("\\\"", "\"")
                .replace("\\'", "'")
        }
        return trimmed
    }
    
    private fun refreshDisplay() {
        coroutineScope.launch(Dispatchers.EDT) {
            try {
                clearDocument()
                val filteredEntries = filterManager.getFilteredEntries()
                val messages = filteredEntries.map { it.toSerialPortMessage() }
                processMessages(messages)
                if (autoScroll) scrollToBottom()
                updateStatusBar()
            } catch (e: Exception) { /* ignore */ }
        }
    }
    
    /**
     * 处理消息并追加到文档 (参考 Logcat)
     */
    private suspend fun processMessages(messages: List<SerialPortMessage>) {
        if (messages.isEmpty()) return
        
        messageBacklog.addAll(messages)
        
        // 更新格式化器设置
        messageFormatter.setShowTimestamp(showTimestamp)
        messageFormatter.setDisplayHex(displayHex)
        messageFormatter.setCompactView(isCompactView)
        messageFormatter.setSoftWrapEnabled(editor.settings.isUseSoftWraps)
        
        // 格式化消息
        val textAccumulator = TextAccumulator()
        messageFormatter.formatMessages(textAccumulator, messages)
        
        // 追加到文档
        appendMessages(textAccumulator)
    }
    
    /**
     * 追加消息到文档 (参考 Logcat)
     */
    private suspend fun appendMessages(textAccumulator: TextAccumulator) {
        withContext(Dispatchers.EDT) {
            // 参考 Logcat: 检查是否需要保持在底部
            val shouldStickToEnd = !ignoreCaretAtBottom && editor.isCaretAtBottom()
            ignoreCaretAtBottom = false
            
            documentAppender.appendToDocument(textAccumulator)
            
            if (shouldStickToEnd) {
                scrollToBottom()
            } else if (autoScroll) {
                // 如果没有保持在底部（比如刚插入数据时发现光标不在底部），则取消自动滚动
                autoScroll = false
                autoScrollBtn?.let { updateToggleButton(it, false) }
            }
        }
    }
    
    /**
     * 清空文档 (参考 Logcat clearDocument)
     */
    private fun clearDocument() {
        com.intellij.openapi.application.WriteAction.run<Throwable> {
            document.setText("")
        }
        messageFormatter.reset()
        documentAppender.reset()  // 重置高亮范围
    }
    
    // appendEntryToDisplay 函数已被新的消息处理机制替代
    // 现在使用 processMessages() 和 MessageFormatter 来处理消息
    
    private fun scrollToBottom() {
        com.intellij.openapi.editor.ex.util.EditorUtil.scrollToTheEnd(editor, true)
        caretLine = document.lineCount
        ignoreCaretAtBottom = false
        if (!autoScroll) {
            autoScroll = true
            autoScrollBtn?.let { updateToggleButton(it, true) }
        }
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
        val content = String(data, StandardCharsets.UTF_8)
        filterManager.addEntry(LogEntry(
            timestamp = timestamp,
            direction = "RX",
            content = content,
            level = parseLogLevel(content),  // 自动解析日志级别
            rawData = data
        ))
    }
    
    override fun onDataSent(data: String, format: DataFormat, timestamp: String) {
        filterManager.addEntry(LogEntry(
            timestamp = timestamp,
            direction = "TX",
            content = data,
            level = parseLogLevel(data),  // 自动解析日志级别
            rawData = data.toByteArray()
        ))
    }
    
    /**
     * 根据日志内容自动解析日志级别 (参考 Logcat)
     * 支持格式：
     * - [V] [D] [I] [W] [E] 或 [VERBOSE] [DEBUG] [INFO] [WARN] [ERROR]
     * - V/ D/ I/ W/ E/ (Logcat 格式)
     * - 关键词: error, exception, fatal, warning, warn, info, debug, verbose
     */
    private fun parseLogLevel(content: String): LogLevel {
        val trimmed = content.trim()
        val upper = trimmed.uppercase()
        
        // 1. 检查 Logcat 格式: V/ D/ I/ W/ E/
        if (trimmed.length >= 2) {
            val prefix = trimmed.substring(0, 2)
            when (prefix) {
                "V/", "v/" -> return LogLevel.VERBOSE
                "D/", "d/" -> return LogLevel.DEBUG
                "I/", "i/" -> return LogLevel.INFO
                "W/", "w/" -> return LogLevel.WARN
                "E/", "e/" -> return LogLevel.ERROR
            }
        }
        
        // 2. 检查方括号格式: [E] [ERROR] [W] [WARN] 等
        val bracketPattern = Regex("^\\s*\\[([VDIWE]|VERBOSE|DEBUG|INFO|WARN(?:ING)?|ERROR)\\]", RegexOption.IGNORE_CASE)
        bracketPattern.find(trimmed)?.let { match ->
            return when (match.groupValues[1].uppercase()) {
                "V", "VERBOSE" -> LogLevel.VERBOSE
                "D", "DEBUG" -> LogLevel.DEBUG
                "I", "INFO" -> LogLevel.INFO
                "W", "WARN", "WARNING" -> LogLevel.WARN
                "E", "ERROR" -> LogLevel.ERROR
                else -> LogLevel.DEBUG
            }
        }
        
        // 3. 检查级别前缀: ERROR: WARN: INFO: DEBUG: VERBOSE:
        val prefixPattern = Regex("^\\s*(ERROR|WARN(?:ING)?|INFO|DEBUG|VERBOSE)\\s*[:\\-]", RegexOption.IGNORE_CASE)
        prefixPattern.find(trimmed)?.let { match ->
            return when (match.groupValues[1].uppercase()) {
                "VERBOSE" -> LogLevel.VERBOSE
                "DEBUG" -> LogLevel.DEBUG
                "INFO" -> LogLevel.INFO
                "WARN", "WARNING" -> LogLevel.WARN
                "ERROR" -> LogLevel.ERROR
                else -> LogLevel.DEBUG
            }
        }
        
        // 4. 检查关键词 (优先级从高到低)
        return when {
            // Error 级别关键词
            upper.contains("ERROR") || 
            upper.contains("EXCEPTION") || 
            upper.contains("FATAL") ||
            upper.contains("FAILURE") ||
            upper.contains("FAILED") ||
            upper.contains("CRASH") -> LogLevel.ERROR
            
            // Warn 级别关键词
            upper.contains("WARN") ||
            upper.contains("CAUTION") -> LogLevel.WARN
            
            // Info 级别关键词
            upper.contains("INFO") ||
            upper.contains("SUCCESS") ||
            upper.contains("CONNECTED") ||
            upper.contains("READY") -> LogLevel.INFO
            
            // Verbose 级别关键词
            upper.contains("VERBOSE") ||
            upper.contains("TRACE") -> LogLevel.VERBOSE
            
            // 默认 Debug
            else -> LogLevel.DEBUG
        }
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
        
        coroutineScope.launch {
            val message = entry.toSerialPortMessage()
            processMessages(listOf(message))
            withContext(Dispatchers.EDT) {
            if (autoScroll) scrollToBottom()
            updateStatusBar()
            }
        }
    }
    
    override fun onFilterChanged(filteredEntries: List<LogEntry>) {}
    
    override fun onLogsCleared() {
        coroutineScope.launch(Dispatchers.EDT) {
            messageBacklog.clear()
            clearDocument()
        }
    }
}
