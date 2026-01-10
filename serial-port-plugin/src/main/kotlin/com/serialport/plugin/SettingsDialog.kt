package com.serialport.plugin

import com.fazecast.jSerialComm.SerialPort
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import java.awt.*
import javax.swing.*
import javax.swing.border.TitledBorder

/**
 * 串口设置对话框 - Logcat 风格
 */
class SettingsDialog(
    private val project: Project,
    private val serialService: SerialPortService,
    private val onSettingsChanged: () -> Unit
) : DialogWrapper(project) {
    
    // 串口参数
    private val dataBitsCombo = JComboBox(arrayOf("5", "6", "7", "8"))
    private val stopBitsCombo = JComboBox(arrayOf("1", "1.5", "2"))
    private val parityCombo = JComboBox(arrayOf("无", "奇校验", "偶校验", "标记", "空格"))
    private val flowControlCombo = JComboBox(arrayOf("无", "RTS/CTS", "XON/XOFF"))
    
    // 发送设置
    private val sendNewlineCombo = JComboBox(arrayOf("无", "CR (\\r)", "LF (\\n)", "CR+LF (\\r\\n)"))
    private val sendDelaySpinner = JSpinner(SpinnerNumberModel(0, 0, 1000, 10))
    
    // 接收设置
    private val receiveNewlineCombo = JComboBox(arrayOf("自动检测", "CR (\\r)", "LF (\\n)", "CR+LF (\\r\\n)", "无"))
    private val bufferSizeSpinner = JSpinner(SpinnerNumberModel(4096, 256, 65536, 256))
    
    // 显示设置
    private val maxLinesSpinner = JSpinner(SpinnerNumberModel(10000, 1000, 100000, 1000))
    private val fontSizeSpinner = JSpinner(SpinnerNumberModel(12, 8, 24, 1))
    private val showTimestampCheck = JCheckBox("显示时间戳", true)
    private val autoScrollCheck = JCheckBox("自动滚动", true)
    
    init {
        title = "串口设置"
        init()
        loadCurrentSettings()
    }
    
    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout(10, 10))
        mainPanel.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        mainPanel.preferredSize = Dimension(450, 400)
        
        val tabbedPane = JTabbedPane()
        
        // 串口参数标签页
        tabbedPane.addTab("串口参数", createSerialParamsPanel())
        
        // 发送/接收标签页
        tabbedPane.addTab("发送/接收", createTransferPanel())
        
        // 显示设置标签页
        tabbedPane.addTab("显示设置", createDisplayPanel())
        
        mainPanel.add(tabbedPane, BorderLayout.CENTER)
        
        // 底部提示
        val tipLabel = JLabel("提示: 修改串口参数需要重新连接后生效")
        tipLabel.foreground = JBColor.GRAY
        tipLabel.font = tipLabel.font.deriveFont(11f)
        mainPanel.add(tipLabel, BorderLayout.SOUTH)
        
        return mainPanel
    }
    
    private fun createSerialParamsPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.border = BorderFactory.createEmptyBorder(15, 15, 15, 15)
        
        val gbc = GridBagConstraints()
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.insets = Insets(5, 5, 5, 5)
        
        var row = 0
        
        // 数据位
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        panel.add(JLabel("数据位:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        dataBitsCombo.selectedItem = "8"
        panel.add(dataBitsCombo, gbc)
        
        row++
        
        // 停止位
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        panel.add(JLabel("停止位:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        stopBitsCombo.selectedItem = "1"
        panel.add(stopBitsCombo, gbc)
        
        row++
        
        // 校验位
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        panel.add(JLabel("校验位:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(parityCombo, gbc)
        
        row++
        
        // 流控制
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        panel.add(JLabel("流控制:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(flowControlCombo, gbc)
        
        row++
        
        // 占位
        gbc.gridx = 0; gbc.gridy = row; gbc.weighty = 1.0
        panel.add(Box.createVerticalGlue(), gbc)
        
        return panel
    }
    
    private fun createTransferPanel(): JPanel {
        val panel = JPanel(BorderLayout(10, 10))
        panel.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        
        // 发送设置
        val sendPanel = JPanel(GridBagLayout())
        sendPanel.border = BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(JBColor.border()),
            "发送设置",
            TitledBorder.LEFT,
            TitledBorder.TOP
        )
        
        val gbc = GridBagConstraints()
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.insets = Insets(5, 10, 5, 10)
        
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0
        sendPanel.add(JLabel("发送换行符:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        sendNewlineCombo.selectedIndex = 3  // CR+LF
        sendPanel.add(sendNewlineCombo, gbc)
        
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0
        sendPanel.add(JLabel("发送延时(ms):"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        sendPanel.add(sendDelaySpinner, gbc)
        
        // 接收设置
        val receivePanel = JPanel(GridBagLayout())
        receivePanel.border = BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(JBColor.border()),
            "接收设置",
            TitledBorder.LEFT,
            TitledBorder.TOP
        )
        
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0
        receivePanel.add(JLabel("换行识别:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        receivePanel.add(receiveNewlineCombo, gbc)
        
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0
        receivePanel.add(JLabel("缓冲区大小:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        receivePanel.add(bufferSizeSpinner, gbc)
        
        val contentPanel = JPanel(GridLayout(2, 1, 10, 10))
        contentPanel.add(sendPanel)
        contentPanel.add(receivePanel)
        
        panel.add(contentPanel, BorderLayout.NORTH)
        
        return panel
    }
    
    private fun createDisplayPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.border = BorderFactory.createEmptyBorder(15, 15, 15, 15)
        
        val gbc = GridBagConstraints()
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.insets = Insets(5, 5, 5, 5)
        gbc.anchor = GridBagConstraints.WEST
        
        var row = 0
        
        // 最大行数
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        panel.add(JLabel("最大显示行数:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(maxLinesSpinner, gbc)
        
        row++
        
        // 字体大小
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        panel.add(JLabel("字体大小:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(fontSizeSpinner, gbc)
        
        row++
        
        // 显示时间戳
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2
        panel.add(showTimestampCheck, gbc)
        
        row++
        
        // 自动滚动
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2
        panel.add(autoScrollCheck, gbc)
        
        row++
        
        // 占位
        gbc.gridx = 0; gbc.gridy = row; gbc.weighty = 1.0
        panel.add(Box.createVerticalGlue(), gbc)
        
        return panel
    }
    
    private fun loadCurrentSettings() {
        // 从服务加载当前设置
        val config = serialService.getConfig()
        
        dataBitsCombo.selectedItem = config.dataBits.toString()
        
        stopBitsCombo.selectedIndex = when (config.stopBits) {
            SerialPort.ONE_STOP_BIT -> 0
            SerialPort.ONE_POINT_FIVE_STOP_BITS -> 1
            SerialPort.TWO_STOP_BITS -> 2
            else -> 0
        }
        
        parityCombo.selectedIndex = when (config.parity) {
            SerialPort.NO_PARITY -> 0
            SerialPort.ODD_PARITY -> 1
            SerialPort.EVEN_PARITY -> 2
            SerialPort.MARK_PARITY -> 3
            SerialPort.SPACE_PARITY -> 4
            else -> 0
        }
        
        sendNewlineCombo.selectedIndex = config.sendNewlineMode
        receiveNewlineCombo.selectedIndex = config.receiveNewlineMode
    }
    
    override fun doOKAction() {
        // 保存设置
        val dataBits = (dataBitsCombo.selectedItem as String).toInt()
        
        val stopBits = when (stopBitsCombo.selectedIndex) {
            0 -> SerialPort.ONE_STOP_BIT
            1 -> SerialPort.ONE_POINT_FIVE_STOP_BITS
            2 -> SerialPort.TWO_STOP_BITS
            else -> SerialPort.ONE_STOP_BIT
        }
        
        val parity = when (parityCombo.selectedIndex) {
            0 -> SerialPort.NO_PARITY
            1 -> SerialPort.ODD_PARITY
            2 -> SerialPort.EVEN_PARITY
            3 -> SerialPort.MARK_PARITY
            4 -> SerialPort.SPACE_PARITY
            else -> SerialPort.NO_PARITY
        }
        
        val flowControl = when (flowControlCombo.selectedIndex) {
            0 -> SerialPort.FLOW_CONTROL_DISABLED
            1 -> SerialPort.FLOW_CONTROL_RTS_ENABLED or SerialPort.FLOW_CONTROL_CTS_ENABLED
            2 -> SerialPort.FLOW_CONTROL_XONXOFF_IN_ENABLED or SerialPort.FLOW_CONTROL_XONXOFF_OUT_ENABLED
            else -> SerialPort.FLOW_CONTROL_DISABLED
        }
        
        serialService.updateConfig(
            dataBits = dataBits,
            stopBits = stopBits,
            parity = parity,
            flowControl = flowControl,
            sendNewlineMode = sendNewlineCombo.selectedIndex,
            receiveNewlineMode = receiveNewlineCombo.selectedIndex
        )
        
        onSettingsChanged()
        super.doOKAction()
    }
}
