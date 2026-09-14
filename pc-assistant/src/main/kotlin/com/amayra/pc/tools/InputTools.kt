package com.amayra.pc.tools

import kotlinx.serialization.json.*
import java.awt.event.InputEvent
import java.awt.event.KeyEvent

/**
 * Amayra's "hands": mouse and keyboard control via java.awt.Robot.
 * All actions are real — the OS receives genuine input events.
 */
class InputTools {

    fun registerAll(registry: ToolRegistry) {
        registry.register(MouseMove())
        registry.register(MouseClick())
        registry.register(MouseDoubleClick())
        registry.register(MouseRightClick())
        registry.register(Scroll())
        registry.register(KeyboardType())
        registry.register(KeyboardPress())
        registry.register(KeyboardHotkey())
    }

    private fun int(args: JsonObject, key: String): Int? = (args[key] as? JsonPrimitive)?.let {
        it.intOrNull ?: it.contentOrNull?.toIntOrNull()
    }

    private fun str(args: JsonObject, key: String): String? = (args[key] as? JsonPrimitive)?.contentOrNull

    private fun click(mask: Int) {
        Hands.robot.mousePress(mask)
        Hands.robot.mouseRelease(mask)
    }

    inner class MouseMove : Tool {
        override val name = "mouse_move"
        override val description = "Move the mouse cursor to absolute screen coordinates (x, y). [risk=SAFE]"
        override val risk = RiskLevel.SAFE
        override fun parameters() = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("x") { put("type", "integer") }
                putJsonObject("y") { put("type", "integer") }
            }
            putJsonArray("required") { add(JsonPrimitive("x")); add(JsonPrimitive("y")) }
        }
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val x = int(args, "x") ?: return ToolResult.Failure("Missing 'x'")
            val y = int(args, "y") ?: return ToolResult.Failure("Missing 'y'")
            val s = Hands.screenSize
            if (x < 0 || y < 0 || x >= s.width || y >= s.height)
                return ToolResult.Failure("Coordinates ($x,$y) off-screen (screen is ${s.width}x${s.height}).")
            Hands.robot.mouseMove(x, y)
            return ToolResult.Success("Mouse moved to ($x,$y).")
        }
    }

    inner class MouseClick : Tool {
        override val name = "mouse_click"
        override val description = "Left-click at coordinates (x, y). Moves the cursor there first. [risk=DEFAULT]"
        override fun parameters() = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("x") { put("type", "integer") }
                putJsonObject("y") { put("type", "integer") }
            }
            putJsonArray("required") { add(JsonPrimitive("x")); add(JsonPrimitive("y")) }
        }
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val x = int(args, "x") ?: return ToolResult.Failure("Missing 'x'")
            val y = int(args, "y") ?: return ToolResult.Failure("Missing 'y'")
            Hands.robot.mouseMove(x, y)
            Thread.sleep(80)
            click(InputEvent.BUTTON1_DOWN_MASK)
            return ToolResult.Success("Left-clicked at ($x,$y).")
        }
    }

    inner class MouseDoubleClick : Tool {
        override val name = "mouse_double_click"
        override val description = "Double-left-click at coordinates. [risk=DEFAULT]"
        override fun parameters() = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("x") { put("type", "integer") }
                putJsonObject("y") { put("type", "integer") }
            }
            putJsonArray("required") { add(JsonPrimitive("x")); add(JsonPrimitive("y")) }
        }
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val x = int(args, "x") ?: return ToolResult.Failure("Missing 'x'")
            val y = int(args, "y") ?: return ToolResult.Failure("Missing 'y'")
            Hands.robot.mouseMove(x, y)
            Thread.sleep(80)
            click(InputEvent.BUTTON1_DOWN_MASK)
            Thread.sleep(60)
            click(InputEvent.BUTTON1_DOWN_MASK)
            return ToolResult.Success("Double-clicked at ($x,$y).")
        }
    }

    inner class MouseRightClick : Tool {
        override val name = "mouse_right_click"
        override val description = "Right-click at coordinates (context menu). [risk=DEFAULT]"
        override fun parameters() = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("x") { put("type", "integer") }
                putJsonObject("y") { put("type", "integer") }
            }
            putJsonArray("required") { add(JsonPrimitive("x")); add(JsonPrimitive("y")) }
        }
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val x = int(args, "x") ?: return ToolResult.Failure("Missing 'x'")
            val y = int(args, "y") ?: return ToolResult.Failure("Missing 'y'")
            Hands.robot.mouseMove(x, y)
            Thread.sleep(80)
            click(InputEvent.BUTTON3_DOWN_MASK)
            return ToolResult.Success("Right-clicked at ($x,$y).")
        }
    }

    inner class Scroll : Tool {
        override val name = "scroll"
        override val description = "Scroll the mouse wheel: positive 'clicks' scroll down, negative scroll up. [risk=SAFE]"
        override val risk = RiskLevel.SAFE
        override fun parameters() = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("clicks") { put("type", "integer"); put("description", "Negative = up, positive = down") }
            }
            putJsonArray("required") { add(JsonPrimitive("clicks")) }
        }
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val clicks = int(args, "clicks") ?: return ToolResult.Failure("Missing 'clicks'")
            Hands.robot.mouseWheel(clicks)
            return ToolResult.Success("Scrolled $clicks clicks.")
        }
    }

    inner class KeyboardType : Tool {
        override val name = "keyboard_type"
        override val description = "Type text into the currently focused window (real keystrokes). [risk=DEFAULT]"
        override fun parameters() = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("text") { put("type", "string") }
            }
            putJsonArray("required") { add(JsonPrimitive("text")) }
        }
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val text = str(args, "text") ?: return ToolResult.Failure("Missing 'text'")
            val r = Hands.robot
            // ASCII via key events; non-ASCII falls back to clipboard+paste.
            if (text.all { it.code in 32..126 }) {
                for (ch in text) typeChar(r, ch)
                return ToolResult.Success("Typed ${text.length} characters.")
            } else {
                val clip = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                val prev = runCatching { clip.getContents(null) }.getOrNull()
                clip.setContents(java.awt.datatransfer.StringSelection(text), null)
                Thread.sleep(100)
                hotkey(KeyEvent.VK_CONTROL, KeyEvent.VK_V)
                Thread.sleep(100)
                runCatching { prev?.let { clip.setContents(it, null) } }
                return ToolResult.Success("Pasted ${text.length} characters (non-ASCII text via clipboard).")
            }
        }
        private fun typeChar(r: java.awt.Robot, ch: Char) {
            val upper = ch.isUpperCase() || ch in "!@#$%^&*()_+{}|:\"<>?~"
            if (upper) r.keyPress(KeyEvent.VK_SHIFT)
            val code = keyCodeFor(ch)
            if (code != KeyEvent.VK_UNDEFINED) {
                r.keyPress(code); r.keyRelease(code)
            }
            if (upper) r.keyRelease(KeyEvent.VK_SHIFT)
            Thread.sleep(15)
        }
        private fun keyCodeFor(ch: Char): Int {
            val upper = Character.toUpperCase(ch)
            return when (upper) {
                'A' -> KeyEvent.VK_A; 'B' -> KeyEvent.VK_B; 'C' -> KeyEvent.VK_C; 'D' -> KeyEvent.VK_D
                'E' -> KeyEvent.VK_E; 'F' -> KeyEvent.VK_F; 'G' -> KeyEvent.VK_G; 'H' -> KeyEvent.VK_H
                'I' -> KeyEvent.VK_I; 'J' -> KeyEvent.VK_J; 'K' -> KeyEvent.VK_K; 'L' -> KeyEvent.VK_L
                'M' -> KeyEvent.VK_M; 'N' -> KeyEvent.VK_N; 'O' -> KeyEvent.VK_O; 'P' -> KeyEvent.VK_P
                'Q' -> KeyEvent.VK_Q; 'R' -> KeyEvent.VK_R; 'S' -> KeyEvent.VK_S; 'T' -> KeyEvent.VK_T
                'U' -> KeyEvent.VK_U; 'V' -> KeyEvent.VK_V; 'W' -> KeyEvent.VK_W; 'X' -> KeyEvent.VK_X
                'Y' -> KeyEvent.VK_Y; 'Z' -> KeyEvent.VK_Z
                '0' -> KeyEvent.VK_0; '1' -> KeyEvent.VK_1; '2' -> KeyEvent.VK_2; '3' -> KeyEvent.VK_3
                '4' -> KeyEvent.VK_4; '5' -> KeyEvent.VK_5; '6' -> KeyEvent.VK_6; '7' -> KeyEvent.VK_7
                '8' -> KeyEvent.VK_8; '9' -> KeyEvent.VK_9
                ' ' -> KeyEvent.VK_SPACE; '\n' -> KeyEvent.VK_ENTER; '\t' -> KeyEvent.VK_TAB
                '.' -> KeyEvent.VK_PERIOD; ',' -> KeyEvent.VK_COMMA; '-' -> KeyEvent.VK_MINUS
                '=' -> KeyEvent.VK_EQUALS; '/' -> KeyEvent.VK_SLASH; '\\' -> KeyEvent.VK_BACK_SLASH
                ';' -> KeyEvent.VK_SEMICOLON; '\'' -> KeyEvent.VK_QUOTE; '`' -> KeyEvent.VK_BACK_QUOTE
                '[' -> KeyEvent.VK_OPEN_BRACKET; ']' -> KeyEvent.VK_CLOSE_BRACKET
                else -> KeyEvent.VK_UNDEFINED
            }
        }
    }

    inner class KeyboardPress : Tool {
        override val name = "keyboard_press"
        override val description = "Press a single key, e.g. ENTER, TAB, ESC, DELETE, F5, SPACE, BACKSPACE. [risk=DEFAULT]"
        override fun parameters() = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("key") { put("type", "string"); put("description", "Key name, e.g. ENTER, ESC, TAB") }
            }
            putJsonArray("required") { add(JsonPrimitive("key")) }
        }
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val keyName = str(args, "key")?.uppercase() ?: return ToolResult.Failure("Missing 'key'")
            val code = keyByName(keyName) ?: return ToolResult.Failure("Unknown key: $keyName")
            Hands.robot.keyPress(code); Hands.robot.keyRelease(code)
            return ToolResult.Success("Pressed $keyName.")
        }
    }

    inner class KeyboardHotkey : Tool {
        override val name = "keyboard_hotkey"
        override val description = "Press a key combination, e.g. keys=[CTRL, SHIFT, T] or [ALT, TAB]. [risk=DEFAULT]"
        override fun parameters() = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("keys") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "Key names in order, e.g. [\"CTRL\", \"SHIFT\", \"T\"]")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("keys")) }
        }
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val keys = (args["keys"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?: return ToolResult.Failure("Missing 'keys' array")
            val codes = keys.map { k ->
                keyByName(k.uppercase()) ?: return ToolResult.Failure("Unknown key: $k")
            }
            hotkey(*codes.toIntArray())
            return ToolResult.Success("Pressed ${keys.joinToString("+")}.")
        }
    }

    companion object {
        internal fun hotkey(vararg codes: Int) {
            val r = Hands.robot
            codes.forEach { r.keyPress(it) }
            Thread.sleep(40)
            codes.reversed().forEach { r.keyRelease(it) }
        }

        fun keyByName(name: String): Int? = when (name) {
            "ENTER", "RETURN" -> KeyEvent.VK_ENTER
            "TAB" -> KeyEvent.VK_TAB
            "ESC", "ESCAPE" -> KeyEvent.VK_ESCAPE
            "SPACE" -> KeyEvent.VK_SPACE
            "BACKSPACE" -> KeyEvent.VK_BACK_SPACE
            "DELETE", "DEL" -> KeyEvent.VK_DELETE
            "UP" -> KeyEvent.VK_UP; "DOWN" -> KeyEvent.VK_DOWN
            "LEFT" -> KeyEvent.VK_LEFT; "RIGHT" -> KeyEvent.VK_RIGHT
            "HOME" -> KeyEvent.VK_HOME; "END" -> KeyEvent.VK_END
            "PAGE_UP", "PGUP" -> KeyEvent.VK_PAGE_UP
            "PAGE_DOWN", "PGDN" -> KeyEvent.VK_PAGE_DOWN
            "CTRL", "CONTROL" -> KeyEvent.VK_CONTROL
            "ALT" -> KeyEvent.VK_ALT
            "SHIFT" -> KeyEvent.VK_SHIFT
            "WIN", "WINDOWS", "META" -> KeyEvent.VK_WINDOWS
            "PRINTSCREEN", "PRTSC" -> KeyEvent.VK_PRINTSCREEN
            else -> when {
                name.startsWith("F") && name.drop(1).toIntOrNull() in 1..12 ->
                    KeyEvent::class.java.getField("VK_F${name.drop(1)}").getInt(null)
                name.length == 1 -> KeyEvent.getExtendedKeyCodeForChar(name[0].lowercase()[0].code).takeIf { it != 0 }
                else -> null
            }
        }
    }
}
