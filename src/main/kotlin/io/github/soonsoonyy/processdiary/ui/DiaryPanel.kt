package io.github.soonsoonyy.processdiary.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.soonsoonyy.processdiary.PD
import io.github.soonsoonyy.processdiary.diary.DiaryLog
import io.github.soonsoonyy.processdiary.diary.DiaryLog.Kind
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.table.AbstractTableModel

/** 도구 창의 "Diary" 탭: 지금까지 일어난 일을 시간순으로 */
class DiaryPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    internal class GroupChoice(val group: DiaryLog.Group) {
        override fun toString() = PD.msg("diary.group.${group.name.lowercase()}")
    }

    private val log = DiaryLog.get()
    internal val groupBox = ComboBox(DiaryLog.Group.entries.map(::GroupChoice).toTypedArray())
    internal val projectBox = JBCheckBox(PD.msg("diary.thisProject"), true)
    private val search = SearchTextField(false)
    private val copyBtn = JButton(PD.msg("diary.copy"), AllIcons.Actions.Copy)
    private val clearBtn = JButton(PD.msg("diary.clear"), AllIcons.Actions.GC)
    private val countLabel = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val model = Model()
    internal val table = JBTable(model)
    private val listener: () -> Unit = { ApplicationManager.getApplication().invokeLater({ rebuild() }, ModalityState.any()) }

    init {
        val bar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4))).apply {
            add(groupBox); add(projectBox); add(search.apply { textEditor.columns = 18; textEditor.emptyText.text = PD.msg("diary.search") })
            add(copyBtn); add(clearBtn); add(countLabel)
        }
        table.setShowGrid(false)
        table.intercellSpacing = JBUI.emptySize()
        table.rowHeight = JBUI.scale(24)
        table.setStriped(true)
        table.tableHeader.reorderingAllowed = false
        table.selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        table.emptyText.text = PD.msg("diary.empty")
        table.columnModel.getColumn(0).apply { preferredWidth = JBUI.scale(120); maxWidth = JBUI.scale(160) }
        table.columnModel.getColumn(1).preferredWidth = JBUI.scale(700)
        table.columnModel.getColumn(2).apply { preferredWidth = JBUI.scale(120); maxWidth = JBUI.scale(220) }
        table.columnModel.getColumn(0).cellRenderer = cell { e -> append(time(e.time), SimpleTextAttributes.GRAYED_ATTRIBUTES) }
        table.columnModel.getColumn(1).cellRenderer = cell { e ->
            icon = iconOf(e.kind)
            append(e.text)
            toolTipText = e.text
        }
        table.columnModel.getColumn(2).cellRenderer = cell { e -> append(e.project, SimpleTextAttributes.GRAYED_ATTRIBUTES) }
        add(bar, BorderLayout.NORTH)
        add(JBScrollPane(table), BorderLayout.CENTER)

        groupBox.addActionListener { rebuild() }
        projectBox.addActionListener { rebuild() }
        search.addDocumentListener(object : DocumentAdapter() { override fun textChanged(e: DocumentEvent) = rebuild() })
        copyBtn.addActionListener { copySelected() }
        clearBtn.addActionListener {
            if (Messages.showYesNoDialog(project, PD.msg("diary.clear.confirm"), PD.msg("diary.clear"), PD.msg("diary.clear"),
                    PD.msg("common.cancel"), Messages.getQuestionIcon()) == Messages.YES) log.clear()
        }
        // 두 번 클릭: 아직 살아 있는 프로세스면 프로세스 탭에서 보여줌
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2) return
                val entry = model.rows.getOrNull(table.rowAtPoint(e.point)) ?: return
                if (entry.pid > 0 && ProcessHandle.of(entry.pid.toLong()).map { it.isAlive }.orElse(false)) {
                    ProcessToolWindowFactory.showPid(project, entry.pid)
                }
            }
        })
        log.addListener(listener)
        rebuild()
    }

    private fun cell(f: ColoredTableCellRenderer.(DiaryLog.Entry) -> Unit) = object : ColoredTableCellRenderer() {
        override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
            val e = value as? DiaryLog.Entry ?: return
            ipad = JBUI.insetsLeft(6)
            f(e)
        }
    }

    fun rebuild() {
        val group = (groupBox.selectedItem as GroupChoice).group
        val q = search.text.trim().lowercase()
        val rows = log.entries().filter { e ->
            (group == DiaryLog.Group.ALL || e.kind.group == group) &&
                (!projectBox.isSelected || e.project.isEmpty() || e.project == project.name) &&
                (q.isEmpty() || e.text.lowercase().contains(q) || e.pid.toString() == q)
        }
        model.rows = rows
        model.fireTableDataChanged()
        countLabel.text = PD.msg("diary.count", rows.size)
    }

    private fun copySelected() {
        val sel = table.selectedRows.toList().mapNotNull { model.rows.getOrNull(it) }.ifEmpty { model.rows }
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
        val text = sel.joinToString("\n") { "${fmt.format(Date(it.time))}  ${it.text}" + if (it.project.isNotEmpty()) "  [${it.project}]" else "" }
        CopyPasteManager.getInstance().setContents(StringSelection(text))
    }

    private class Model : AbstractTableModel() {
        var rows: List<DiaryLog.Entry> = emptyList()
        override fun getRowCount() = rows.size
        override fun getColumnCount() = 3
        override fun getColumnName(column: Int) = PD.msg(listOf("diary.col.time", "diary.col.event", "diary.col.project")[column])
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = rows[rowIndex]
    }

    override fun dispose() { log.removeListener(listener) }

    companion object {
        /** 오늘이면 시각만, 아니면 날짜도 */
        fun time(t: Long): String {
            val now = Calendar.getInstance()
            val c = Calendar.getInstance().apply { timeInMillis = t }
            val today = now.get(Calendar.YEAR) == c.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)
            return SimpleDateFormat(if (today) "HH:mm:ss" else "MM-dd HH:mm").format(Date(t))
        }

        fun iconOf(k: Kind): Icon = when (k) {
            Kind.RUN_START -> AllIcons.Actions.Execute
            Kind.RUN_END -> AllIcons.Actions.Suspend
            Kind.PORT_CONFLICT -> AllIcons.General.Warning
            Kind.PORT_AUTO, Kind.PORT_FREED -> AllIcons.Actions.Restart
            Kind.LEFTOVER -> AllIcons.General.Warning
            Kind.STOP -> AllIcons.Debugger.KillProcess
            Kind.ALERT, Kind.TREND -> AllIcons.General.Warning
            Kind.DOCKER -> AllIcons.General.Information
            Kind.JAVA -> AllIcons.Nodes.Class
        }
    }
}
