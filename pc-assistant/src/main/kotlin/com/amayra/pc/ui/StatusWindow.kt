package com.amayra.pc.ui

import com.amayra.pc.brain.AmayraBrain
import com.amayra.pc.core.AssistantState
import com.amayra.pc.core.StateBus
import com.amayra.pc.relay.PcRelayServer
import com.amayra.pc.tools.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.Color
import java.awt.Font
import java.awt.GridBagLayout
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

/**
 * Always-on-top status window for the Amayra PC assistant: her current state,
 * the last tool call (with ok/fail color), relay connections, and model info.
 * Deliberately Swing — zero extra dependencies, tiny, and always-on-top works
 * natively. The console REPL keeps working alongside it.
 */
class StatusWindow(
    private val brain: AmayraBrain,
    private val registry: ToolRegistry,
    private val relay: PcRelayServer,
    private val relayPort: Int,
    private val model: String
) {
    private val fmt = DateTimeFormatter.ofPattern("HH:mm:ss")

    private lateinit var frame: JFrame
    private lateinit var stateLabel: JLabel
    private lateinit var toolRows: Array<JLabel>
    private lateinit var relayLabel: JLabel
    private lateinit var updatedLabel: JLabel

    private companion object {
        const val TOOL_ROWS = 5
        const val EMPTY_ROW = "—"
    }

    fun show() {
        SwingUtilities.invokeLater {
            frame = JFrame("Amayra — PC Assistant").apply {
                isAlwaysOnTop = true
                defaultCloseOperation = WindowConstants.HIDE_ON_CLOSE  // tray-friendly; console /exit really exits
                setSize(380, 290)
                isResizable = false
                layout = GridBagLayout()
                setLocationRelativeTo(null)

                val title = JLabel("◈ AMAYRA ◈").apply {
                    font = Font(Font.SANS_SERIF, Font.BOLD, 16)
                    foreground = Color(0x21, 0x96, 0xF3)
                    horizontalAlignment = JLabel.CENTER
                }

                stateLabel = bigLabel()
                toolRows = Array(TOOL_ROWS) { label() }
                relayLabel = label()
                updatedLabel = JLabel("").apply {
                    font = Font(Font.SANS_SERIF, Font.PLAIN, 10)
                    foreground = Color.GRAY
                    horizontalAlignment = JLabel.CENTER
                }

                val panel = JPanel().apply {
                    background = Color(0x1E, 0x1E, 0x22)
                    layout = GridBagLayout()
                    val c = java.awt.GridBagConstraints().apply {
                        gridx = 0; fill = java.awt.GridBagConstraints.HORIZONTAL; weightx = 1.0
                        insets = java.awt.Insets(4, 14, 4, 14)
                    }
                    add(title, c.apply { gridy = 0 })
                    add(stateLabel, c.apply { gridy = 1 })
                    add(JLabel("Recent tool calls").apply {
                        font = Font(Font.SANS_SERIF, Font.PLAIN, 10)
                        foreground = Color.GRAY
                        horizontalAlignment = JLabel.CENTER
                    }, c.apply { gridy = 2 })
                    toolRows.forEachIndexed { i, row -> add(row, c.apply { gridy = 3 + i }) }
                    add(relayLabel, c.apply { gridy = 3 + TOOL_ROWS })
                    add(updatedLabel, c.apply { gridy = 4 + TOOL_ROWS })
                }
                contentPane = panel
            }
            frame.isVisible = true
            startRefresh()
        }
    }

    private fun bigLabel() = JLabel("Idle").apply {
        font = Font(Font.SANS_SERIF, Font.BOLD, 15)
        foreground = Color.WHITE
        horizontalAlignment = JLabel.CENTER
    }

    private fun label() = JLabel(EMPTY_ROW).apply {
        font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
        foreground = Color(0xBB, 0xBB, 0xCC)
        horizontalAlignment = JLabel.CENTER
    }

    private fun startRefresh() {
        // Poll on a background thread, apply on the EDT.
        Thread({
            while (true) {
                SwingUtilities.invokeLater { refresh() }
                Thread.sleep(1000)
            }
        }, "amayra-status-ui").apply { isDaemon = true; start() }
    }

    private fun refresh() {
        val st = StateBus.state.value
        stateLabel.text = st.label
        stateLabel.foreground = when (st) {
            is AssistantState.Error -> Color(0xFF, 0x6E, 0x6E)
            is AssistantState.ToolExecution, is AssistantState.Verifying -> Color(0xFF, 0xD5, 0x4F)
            is AssistantState.Listening, is AssistantState.Speaking -> Color(0x69, 0xF0, 0xAE)
            AssistantState.Idle -> Color.WHITE
            else -> Color(0xE0, 0xE0, 0xE0)
        }

        // Tool call history: newest first, per-row ok/fail color.
        val calls = registry.recentCalls(TOOL_ROWS)
        toolRows.forEachIndexed { i, row ->
            val entry = calls.getOrNull(i)
            if (entry == null) {
                row.text = EMPTY_ROW
                row.foreground = Color(0x55, 0x55, 0x60)
            } else {
                row.text = "${entry.timestamp}  ${entry.call.take(42)}"
                row.foreground = if (entry.ok) Color(0x69, 0xF0, 0xAE) else Color(0xFF, 0x6E, 0x6E)
            }
        }

        relayLabel.text = if (relay.running)
            "Relay :$relayPort · ${relay.connectionsAccepted} connections · last ${relay.lastClientSummary.take(22)}"
        else "Relay :$relayPort · OFFLINE"
        relayLabel.foreground = if (relay.running) Color(0x90, 0xCA, 0xF9) else Color(0xFF, 0x6E, 0x6E)

        updatedLabel.text = "$model · ${LocalTime.now().format(fmt)}"
    }
}
