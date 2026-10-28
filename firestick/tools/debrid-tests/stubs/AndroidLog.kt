package android.util
import java.util.concurrent.CopyOnWriteArrayList
/** android.util.Log stand-in: every line is kept so the tests can check nothing secret reaches the log. */
object Log {
    val lines = CopyOnWriteArrayList<String>()
    @Volatile var echo = System.getenv("DEBRID_TEST_LOG") == "1"
    private fun put(l: String, tag: String, msg: String): Int { val s = "$l/$tag: $msg"; lines.add(s); if (echo) println("      log $s"); return 0 }
    @JvmStatic fun w(tag: String, msg: String) = put("W", tag, msg)
    @JvmStatic fun i(tag: String, msg: String) = put("I", tag, msg)
    @JvmStatic fun d(tag: String, msg: String) = put("D", tag, msg)
    @JvmStatic fun e(tag: String, msg: String) = put("E", tag, msg)
}
