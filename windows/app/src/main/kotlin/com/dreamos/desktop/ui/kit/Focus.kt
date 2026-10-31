package com.dreamos.desktop.ui.kit

import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import com.dreamos.desktop.core.util.Log

/**
 * Focus moves that never throw. Compose's focus calls throw when the target isn't attached yet ("FocusRequester is not
 * initialized") or when the focus tree is mid-change ("ActiveParent with no focused child"); a moved focus is never
 * worth a crash, so every programmatic request in the shell goes through here and a failure is logged, not thrown.
 */
object Focus {
    /** Where focus rests when nothing else can take it: the shell's root, set once it is on screen. */
    var park: FocusRequester? = null

    /** The control ([dreamClick]) that has keyboard focus now, or null; "Exit Dream OS?" gives focus back to it. */
    var last: FocusRequester? = null

    /** Asks [r] for focus; false (logged) when it can't take it right now. */
    fun request(r: FocusRequester?, why: String): Boolean {
        if (r == null) return false
        return try {
            r.requestFocus()
            true
        } catch (e: IllegalStateException) {
            fail(why, e)
        } catch (e: IllegalArgumentException) {
            fail(why, e)
        }
    }

    /** Moves focus one step [d]; false when there is nothing that way or the tree can't move right now. */
    fun move(fm: FocusManager, d: FocusDirection, why: String): Boolean = try {
        fm.moveFocus(d)
    } catch (e: IllegalStateException) {
        fail(why, e)
    } catch (e: IllegalArgumentException) {
        fail(why, e)
    }

    private fun fail(why: String, e: Exception): Boolean {
        // Never silent: "not initialized" is routine (the target's page hasn't laid out yet) and gets one line; anything
        // else is a focus tree in trouble and goes to the log with its stack.
        val msg = e.message?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim() ?: e.javaClass.simpleName
        if (e is IllegalStateException && msg.contains("not initialized")) Log.i("Focus", "$why: $msg")
        else {
            val sw = java.io.StringWriter()
            e.printStackTrace(java.io.PrintWriter(sw))
            Log.w("Focus", "$why: $msg\n$sw")
        }
        return false
    }
}
