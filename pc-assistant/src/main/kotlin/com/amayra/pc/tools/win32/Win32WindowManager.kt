package com.amayra.pc.tools.win32

import com.amayra.pc.core.PcLog
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.win32.StdCallLibrary
import java.util.concurrent.atomic.AtomicReference

/**
 * Reliable Win32 window management via JNA — replaces the PowerShell
 * Shell.Application COM approach, which only saw Explorer windows.
 *
 * Enumerates ALL top-level visible windows with titles and rectangles,
 * and can focus, move, resize, minimize, maximize or restore them.
 */
object Win32WindowManager {

    interface User32 : StdCallLibrary {
        fun EnumWindows(callback: WndEnumProc, lParam: Pointer?): Boolean
        fun GetWindowTextW(hWnd: Pointer, buffer: CharArray, maxCount: Int): Int
        fun GetWindowTextLengthW(hWnd: Pointer): Int
        fun IsWindowVisible(hWnd: Pointer): Boolean
        fun IsIconic(hWnd: Pointer): Boolean
        fun IsZoomed(hWnd: Pointer): Boolean
        fun GetForegroundWindow(): Pointer?
        fun SetForegroundWindow(hWnd: Pointer): Boolean
        fun ShowWindow(hWnd: Pointer, nCmdShow: Int): Boolean
        fun GetWindowRect(hWnd: Pointer, rect: WinDef.RECT): Boolean
        fun MoveWindow(hWnd: Pointer, x: Int, y: Int, w: Int, h: Int, repaint: Boolean): Boolean
        fun GetWindowThreadProcessId(hWnd: Pointer, lpdwProcessId: com.sun.jna.ptr.IntByReference): Int
        fun GetClassNameW(hWnd: Pointer, buffer: CharArray, maxCount: Int): Int
        fun PostMessageW(hWnd: Pointer, msg: Int, wParam: Pointer?, lParam: Pointer?): Boolean

        interface WndEnumProc : StdCallLibrary.StdCallCallback {
            fun callback(hWnd: Pointer, lParam: Pointer?): Boolean
        }

        companion object {
            val SW_HIDE = 0; val SW_SHOWNORMAL = 1; val SW_SHOWMINIMIZED = 2
            val SW_SHOWMAXIMIZED = 3; val SW_SHOWNOACTIVATE = 4; val SW_RESTORE = 9
            val SW_SHOWMINNOACTIVE = 7; val SW_SHOW = 5; val SW_MINIMIZE = 6
            val SW_SHOWNA = 8; val SW_SHOWDEFAULT = 10
        }
    }

    private val user32: User32 = Native.load("user32", User32::class.java)

    data class WindowInfo(
        val hwnd: Long,
        val title: String,
        val className: String,
        val pid: Int,
        val rect: IntArray?, // x, y, w, h
        val minimized: Boolean,
        val maximized: Boolean,
        val focused: Boolean
    ) {
        override fun equals(other: Any?): Boolean = other is WindowInfo && other.hwnd == hwnd
        override fun hashCode(): Int = hwnd.hashCode()
        fun rectJson(): String = rect?.joinToString(",", "[", "]") ?: "null"
    }

    private fun windowTitle(hWnd: Pointer): String {
        val len = user32.GetWindowTextLengthW(hWnd)
        if (len <= 0) return ""
        val buf = CharArray(len + 1)
        user32.GetWindowTextW(hWnd, buf, buf.size)
        return Native.toString(buf)
    }

    private fun className(hWnd: Pointer): String {
        val buf = CharArray(256)
        user32.GetClassNameW(hWnd, buf, buf.size)
        return Native.toString(buf)
    }

    private fun rectOf(hWnd: Pointer): IntArray? {
        val r = WinDef.RECT()
        return if (user32.GetWindowRect(hWnd, r)) intArrayOf(r.left, r.top, r.right - r.left, r.bottom - r.top) else null
    }

    /** All visible top-level windows with a non-empty title. */
    fun listWindows(): List<WindowInfo> {
        val results = AtomicReference<List<WindowInfo>>(emptyList())
        val fg = user32.GetForegroundWindow()
        val callback = object : User32.WndEnumProc {
            override fun callback(hWnd: Pointer, lParam: Pointer?): Boolean {
                try {
                    if (!user32.IsWindowVisible(hWnd)) return true
                    val title = windowTitle(hWnd)
                    if (title.isBlank()) return true
                    val pidRef = com.sun.jna.ptr.IntByReference()
                    user32.GetWindowThreadProcessId(hWnd, pidRef)
                    results.set(results.get() + WindowInfo(
                        hwnd = Pointer.nativeValue(hWnd),
                        title = title,
                        className = className(hWnd),
                        pid = pidRef.value,
                        rect = rectOf(hWnd),
                        minimized = user32.IsIconic(hWnd),
                        maximized = user32.IsZoomed(hWnd),
                        focused = fg != null && Pointer.nativeValue(fg) == Pointer.nativeValue(hWnd)
                    ))
                } catch (t: Throwable) {
                    PcLog.w("WIN32", "enum window skipped: ${t.message}")
                }
                return true
            }
        }
        user32.EnumWindows(callback, null)
        return results.get()
    }

    /** Find a window whose title contains [titlePart] (case-insensitive). */
    fun findByTitle(titlePart: String): WindowInfo? =
        listWindows().firstOrNull { it.title.contains(titlePart, ignoreCase = true) }

    fun focus(titlePart: String): WindowInfo? {
        val w = findByTitle(titlePart) ?: return null
        val hWnd = Pointer(w.hwnd)
        // Restore first if minimized, then bring to foreground.
        if (w.minimized) user32.ShowWindow(hWnd, User32.SW_RESTORE)
        user32.ShowWindow(hWnd, User32.SW_SHOW)
        user32.SetForegroundWindow(hWnd)
        return w
    }

    fun move(titlePart: String, x: Int, y: Int): WindowInfo? {
        val w = findByTitle(titlePart) ?: return null
        val hWnd = Pointer(w.hwnd)
        val (rw, rh) = (w.rect ?: intArrayOf(0, 0, 800, 600)).let { it[2] to it[3] }
        if (w.minimized) user32.ShowWindow(hWnd, User32.SW_RESTORE)
        user32.MoveWindow(hWnd, x, y, rw, rh, true)
        return w
    }

    fun resize(titlePart: String, width: Int, height: Int): WindowInfo? {
        val w = findByTitle(titlePart) ?: return null
        val hWnd = Pointer(w.hwnd)
        val (rx, ry) = (w.rect ?: intArrayOf(100, 100, 0, 0)).let { it[0] to it[1] }
        if (w.minimized) user32.ShowWindow(hWnd, User32.SW_RESTORE)
        user32.MoveWindow(hWnd, rx, ry, width, height, true)
        return w
    }

    fun minimize(titlePart: String): WindowInfo? {
        val w = findByTitle(titlePart) ?: return null
        user32.ShowWindow(Pointer(w.hwnd), User32.SW_MINIMIZE)
        return w
    }

    fun maximize(titlePart: String): WindowInfo? {
        val w = findByTitle(titlePart) ?: return null
        user32.ShowWindow(Pointer(w.hwnd), User32.SW_SHOWMAXIMIZED)
        return w
    }

    fun restore(titlePart: String): WindowInfo? {
        val w = findByTitle(titlePart) ?: return null
        user32.ShowWindow(Pointer(w.hwnd), User32.SW_RESTORE)
        return w
    }

    fun activeWindow(): WindowInfo? {
        val fg = user32.GetForegroundWindow() ?: return null
        val title = windowTitle(fg)
        val pidRef = com.sun.jna.ptr.IntByReference()
        user32.GetWindowThreadProcessId(fg, pidRef)
        return WindowInfo(
            hwnd = Pointer.nativeValue(fg), title = title, className = className(fg),
            pid = pidRef.value, rect = rectOf(fg),
            minimized = user32.IsIconic(fg), maximized = user32.IsZoomed(fg), focused = true
        )
    }

    /** Close a window politely via WM_CLOSE (respects unsaved-work dialogs). */
    fun closeWindow(titlePart: String): WindowInfo? {
        val w = findByTitle(titlePart) ?: return null
        val hWnd = Pointer(w.hwnd)
        if (w.minimized) user32.ShowWindow(hWnd, User32.SW_RESTORE)
        val WM_CLOSE = 0x0010
        user32.PostMessageW(hWnd, WM_CLOSE, null, null)
        return w
    }

    fun available(): Boolean = try { listWindows(); true } catch (t: Throwable) {
        PcLog.w("WIN32", "JNA user32 unavailable: ${t.message}"); false
    }
}
