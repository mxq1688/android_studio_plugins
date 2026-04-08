package com.serialport.plugin.util

import com.intellij.execution.impl.ConsoleViewUtil
import com.intellij.ide.ui.UISettingsListener
import com.intellij.ide.ui.UISettingsUtils
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.undo.UndoUtil
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.DocumentImpl
import com.intellij.openapi.editor.impl.EditorFactoryImpl
import com.intellij.openapi.project.Project
import com.intellij.util.ui.JBUI

/**
 * 创建串口日志编辑器，参考 Logcat 的实现
 * 基于 [com.intellij.execution.impl.ConsoleViewImpl]
 */
fun createSerialPortEditor(project: Project, disposable: Disposable): EditorEx {
    val editorFactory = EditorFactory.getInstance()
    val document = (editorFactory as EditorFactoryImpl).createDocument(true)
    (document as DocumentImpl).setAcceptSlashR(true)
    UndoUtil.disableUndoFor(document)
    val editor = editorFactory.createViewer(document, project, EditorKind.CONSOLE) as EditorEx
    val editorSettings = editor.settings
    editorSettings.isAllowSingleLogicalLineFolding = true
    editorSettings.isLineMarkerAreaShown = false
    editorSettings.isIndentGuidesShown = false
    editorSettings.isLineNumbersShown = false
    editorSettings.isFoldingOutlineShown = true
    editorSettings.isAdditionalPageAtBottom = false
    editorSettings.additionalColumnsCount = 0
    editorSettings.additionalLinesCount = 0
    editorSettings.isRightMarginShown = false
    editorSettings.isCaretRowShown = false
    editorSettings.isShowingSpecialChars = false
    editor.gutterComponentEx.isPaintBackground = false

    editor.updateFontSize()
    val uiSettingsListener = UISettingsListener { editor.updateFontSize() }
    val bus = ApplicationManager.getApplication().messageBus.connect(disposable)
    bus.subscribe(UISettingsListener.TOPIC, uiSettingsListener)
    bus.subscribe(EditorColorsManager.TOPIC, EditorColorsListener { editor.updateFontSize() })

    return editor
}

/**
 * 更新编辑器字体大小
 */
internal fun EditorEx.updateFontSize() {
    val fontSize = UISettingsUtils.getInstance().scaledConsoleFontSize
    setFontSize(fontSize)
    colorsScheme =
        ConsoleViewUtil.updateConsoleColorScheme(colorsScheme).apply { setEditorFontSize(fontSize) }
}

/** 返回光标是否在编辑器文档底部 */
internal fun EditorEx.isCaretAtBottom() =
    document.let { it.getLineNumber(caretModel.offset) >= it.lineCount - 1 }

/** 返回编辑器垂直滚动位置是否在底部 */
internal fun EditorEx.isScrollAtBottom(useImmediatePosition: Boolean): Boolean {
    val scrollBar = scrollPane.verticalScrollBar
    val position =
        if (useImmediatePosition) scrollBar.value else scrollingModel.visibleAreaOnScrollingFinished.y
    return scrollBar.maximum - scrollBar.visibleAmount == position
}
