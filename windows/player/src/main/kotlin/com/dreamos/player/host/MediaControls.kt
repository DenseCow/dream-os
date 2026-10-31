package com.dreamos.player.host

import com.dreamos.player.engine.PlayerLog
import com.sun.jna.Callback
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Windows' media controls for Dream Player: the media keys on any keyboard or headset, and the system media overlay
 * (the volume flyout and Quick Settings show the title, the art and Play / Pause / Next).
 *
 * SystemMediaTransportControls (WinRT) is reached straight from the JVM through JNA: `RoGetActivationFactory` for the
 * class's interop factory, `ISystemMediaTransportControlsInterop.GetForWindow` with the player's window, then the
 * interfaces' methods by their vtable slots (checked against the Windows SDK's windows.media.h, 10.0.26100). The
 * ButtonPressed handler is a small COM object built here (IUnknown + Invoke). Every call runs on one MTA thread.
 *
 * If SMTC can't start (an old Windows, a policy), the media keys still work while the player is open through
 * `RegisterHotKey` on a thread of its own (play / pause, next, stop), released when the player closes.
 */
class MediaControls private constructor(private val onButton: (Button) -> Unit) {
    /** [TOGGLE]: the fallback's single Play / Pause key. */
    enum class Button { PLAY, PAUSE, TOGGLE, STOP, NEXT, PREVIOUS }

    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "dream-media-keys").apply { isDaemon = true } }
    private var smtc: Pointer? = null
    private var updater: Pointer? = null
    private var video: Pointer? = null
    private var handler: ComHandler? = null
    private var token = 0L
    private var hotkeys: HotKeys? = null
    private var last: Info? = null

    /** What the overlay shows. [art]: an https image (the title's backdrop). */
    data class Info(val title: String, val subtitle: String, val art: String?, val playing: Boolean, val canNext: Boolean)

    private fun start(hwnd: Long) {
        thread.execute {
            val ok = runCatching { startSmtc(hwnd) }.onFailure { PlayerLog.w("Media keys: system media controls unavailable (${it.message})") }.getOrDefault(false)
            if (!ok) hotkeys = HotKeys(onButton).also { it.start() }
        }
    }

    /** Shows [info] in the overlay (only what changed is sent). Any thread. */
    fun update(info: Info) {
        thread.execute {
            val before = last
            last = info
            val s = smtc ?: return@execute
            runCatching {
                if (before?.playing != info.playing) call(s, SMTC_PUT_STATUS, if (info.playing) STATUS_PLAYING else STATUS_PAUSED)
                if (before?.canNext != info.canNext) call(s, SMTC_PUT_NEXT, if (info.canNext) 1 else 0)
                if (before?.title != info.title || before.subtitle != info.subtitle || before.art != info.art) {
                    val u = updater ?: return@runCatching
                    val v = video ?: return@runCatching
                    hstring(info.title) { call(v, VIDEO_PUT_TITLE, it) }
                    hstring(info.subtitle) { call(v, VIDEO_PUT_SUBTITLE, it) }
                    info.art?.let { thumbnail(u, it) }
                    call(u, UPDATER_UPDATE)
                }
            }.onFailure { PlayerLog.w("Media keys: update failed (${it.message})") }
        }
    }

    /** Dev check: what Windows holds now ("status 4, enabled 1, next 0"), or why not. Blocks up to 2 s. */
    internal fun readBack(): String = runCatching {
        thread.submit<String> {
            val s = smtc ?: return@submit if (hotkeys != null) "fallback: media hot keys" else "not started"
            val st = IntByReference(); val en = IntByReference(); val nx = IntByReference()
            call(s, SMTC_PUT_STATUS - 1, st.pointer); call(s, SMTC_PUT_ENABLED - 1, en.pointer); call(s, SMTC_PUT_NEXT - 1, nx.pointer)
            "status ${st.value}, enabled ${en.value}, next ${nx.value}, button handler ${if (token != 0L) "registered" else "missing"}"
        }.get(2, TimeUnit.SECONDS)
    }.getOrElse { "failed: ${it.message}" }

    /** Leaves the overlay and gives the media keys back. Waits up to a second. */
    fun close() {
        thread.execute {
            runCatching {
                smtc?.let { s ->
                    call(s, SMTC_PUT_STATUS, STATUS_CLOSED)
                    updater?.let { call(it, UPDATER_CLEAR); call(it, UPDATER_UPDATE) }
                    if (token != 0L) callL(s, SMTC_REMOVE_BUTTON, token)
                    call(s, SMTC_PUT_ENABLED, 0)
                }
                listOfNotNull(video, updater, smtc).forEach { release(it) }
            }
            // Windows may still hold the button handler and call its Release later, from a thread of its own: its
            // vtable and callbacks are native memory that Java would free with the object, so it is kept for good.
            handler?.let { synchronized(retired) { retired += it } }
            smtc = null; updater = null; video = null; handler = null
            hotkeys?.stop()
            hotkeys = null
        }
        thread.shutdown()
        thread.awaitTermination(1, TimeUnit.SECONDS)
    }

    // ---------------------------------------------------------------- SMTC

    private fun startSmtc(hwnd: Long): Boolean {
        val hr0 = Combase.INSTANCE.RoInitialize(1) // RO_INIT_MULTITHREADED; S_FALSE / RPC_E_CHANGED_MODE are fine
        if (hr0 < 0 && hr0 != RPC_E_CHANGED_MODE) error("RoInitialize 0x%08x".format(hr0))
        val interop = factory("Windows.Media.SystemMediaTransportControls", IID_SMTC_INTEROP)
        try {
            val out = PointerByReference()
            check(callP(interop, INTEROP_GET_FOR_WINDOW, Pointer(hwnd), iid(IID_SMTC), out), "GetForWindow")
            smtc = out.value
        } finally { release(interop) }
        val s = smtc!!
        val u = PointerByReference()
        check(call(s, SMTC_GET_UPDATER, u.pointer), "DisplayUpdater")
        updater = u.value
        check(call(updater!!, UPDATER_PUT_TYPE, TYPE_VIDEO), "put_Type")
        val v = PointerByReference()
        check(call(updater!!, UPDATER_GET_VIDEO, v.pointer), "VideoProperties")
        video = v.value
        call(s, SMTC_PUT_ENABLED, 1)
        call(s, SMTC_PUT_PLAY, 1)
        call(s, SMTC_PUT_PAUSE, 1)
        call(s, SMTC_PUT_STOP, 1)
        call(s, SMTC_PUT_NEXT, 0)
        call(s, SMTC_PUT_PREVIOUS, 1) // Previous = Start Over (the remote's MEDIA_PREVIOUS)
        val h = ComHandler { button ->
            val b = when (button) {
                0 -> Button.PLAY; 1 -> Button.PAUSE; 2 -> Button.STOP; 6 -> Button.NEXT; 7 -> Button.PREVIOUS
                else -> null
            }
            if (b != null) onButton(b)
        }
        handler = h
        val tok = LongByReference()
        check(call(s, SMTC_ADD_BUTTON, h.pointer, tok.pointer), "add_ButtonPressed")
        token = tok.value
        PlayerLog.i("Media keys: system media controls on")
        return true
    }

    /** RandomAccessStreamReference.CreateFromUri(new Uri(url)) -> put_Thumbnail. */
    private fun thumbnail(updater: Pointer, url: String) {
        val uriFactory = factory("Windows.Foundation.Uri", IID_URI_FACTORY)
        val refStatics = factory("Windows.Storage.Streams.RandomAccessStreamReference", IID_STREAM_REF_STATICS)
        try {
            val uri = PointerByReference()
            hstring(url) { callP(uriFactory, URI_CREATE, it, uri) }
            val u = uri.value ?: return
            try {
                val ref = PointerByReference()
                if (callP(refStatics, STREAM_REF_FROM_URI, u, ref) >= 0 && ref.value != null) {
                    call(updater, UPDATER_PUT_THUMBNAIL, ref.value)
                    release(ref.value)
                }
            } finally { release(u) }
        } finally { release(uriFactory); release(refStatics) }
    }

    private fun factory(cls: String, iid: String): Pointer {
        val out = PointerByReference()
        hstring(cls) { h ->
            val hr = Combase.INSTANCE.RoGetActivationFactory(h, iid(iid), out)
            check(hr, "RoGetActivationFactory($cls)")
        }
        return out.value
    }

    private inline fun <T> hstring(s: String, use: (Pointer?) -> T): T {
        val ref = PointerByReference()
        val hr = Combase.INSTANCE.WindowsCreateString(WString(s), s.length, ref)
        check(hr, "WindowsCreateString")
        try { return use(ref.value) } finally { Combase.INSTANCE.WindowsDeleteString(ref.value) }
    }

    private fun check(hr: Int, what: String): Int {
        if (hr < 0) error("$what 0x%08x".format(hr))
        return hr
    }

    // ---------------------------------------------------------------- media-key fallback

    /** Global hot keys for the media keys on a message-loop thread (only while SMTC is unavailable). */
    private class HotKeys(private val onButton: (Button) -> Unit) {
        private var threadId = 0
        private val t = Thread({ loop() }, "dream-media-hotkeys").apply { isDaemon = true }

        fun start() = t.start()

        private fun loop() {
            threadId = com.sun.jna.platform.win32.Kernel32.INSTANCE.GetCurrentThreadId()
            val u = User32.INSTANCE
            val keys = mapOf(1 to (VK_MEDIA_PLAY_PAUSE to Button.TOGGLE), 2 to (VK_MEDIA_NEXT to Button.NEXT), 3 to (VK_MEDIA_STOP to Button.STOP))
            val got = keys.filter { (id, k) -> u.RegisterHotKey(null, id, 0x4000 /* MOD_NOREPEAT */, k.first) }
            PlayerLog.i("Media keys: ${got.size} of ${keys.size} media keys taken while the player is open")
            val msg = WinUser.MSG()
            while (u.GetMessage(msg, null, 0, 0) > 0) {
                if (msg.message == WinUser.WM_HOTKEY) {
                    val k = keys[msg.wParam.toInt()] ?: continue
                    onButton(k.second)
                }
            }
            got.keys.forEach { u.UnregisterHotKey(null, it) }
        }

        fun stop() {
            if (threadId != 0) User32.INSTANCE.PostThreadMessage(threadId, WinUser.WM_QUIT, null, null)
        }
    }

    // ---------------------------------------------------------------- COM plumbing

    /**
     * TypedEventHandler<SystemMediaTransportControls, ButtonPressedEventArgs> as a COM object: a pointer to a vtable of
     * QueryInterface, AddRef, Release, Invoke. Lives as long as [MediaControls] (the callbacks are held here).
     */
    private class ComHandler(private val onButton: (Int) -> Unit) {
        private val qi = object : QI {
            override fun invoke(self: Pointer, riid: Pointer, out: Pointer): Int {
                val asked = Guid.GUID(riid)
                val ok = asked == Guid.GUID(IID_UNKNOWN) || asked == Guid.GUID(IID_AGILE) || asked == Guid.GUID(IID_BUTTON_HANDLER)
                out.setPointer(0, if (ok) self else null)
                return if (ok) 0 else E_NOINTERFACE
            }
        }
        private val ref = object : Ref { override fun invoke(self: Pointer): Int = 1 }
        private val inv = object : Invoke {
            override fun invoke(self: Pointer, sender: Pointer?, args: Pointer?): Int {
                if (args == null) return 0
                val b = IntByReference()
                if (call(args, ARGS_GET_BUTTON, b.pointer) >= 0) runCatching { onButton(b.value) }
                return 0
            }
        }
        private val vtbl = Memory(4L * Native.POINTER_SIZE).apply {
            setPointer(0, com.sun.jna.CallbackReference.getFunctionPointer(qi))
            setPointer(1L * Native.POINTER_SIZE, com.sun.jna.CallbackReference.getFunctionPointer(ref))
            setPointer(2L * Native.POINTER_SIZE, com.sun.jna.CallbackReference.getFunctionPointer(ref))
            setPointer(3L * Native.POINTER_SIZE, com.sun.jna.CallbackReference.getFunctionPointer(inv))
        }
        val pointer: Pointer = Memory(Native.POINTER_SIZE.toLong()).apply { setPointer(0, vtbl) }

        interface QI : Callback { fun invoke(self: Pointer, riid: Pointer, out: Pointer): Int }
        interface Ref : Callback { fun invoke(self: Pointer): Int }
        interface Invoke : Callback { fun invoke(self: Pointer, sender: Pointer?, args: Pointer?): Int }
    }

    @Suppress("FunctionName")
    private interface Combase : com.sun.jna.Library {
        fun RoInitialize(type: Int): Int
        fun RoGetActivationFactory(cls: Pointer?, iid: Guid.GUID, out: PointerByReference): Int
        fun WindowsCreateString(s: WString, len: Int, out: PointerByReference): Int
        fun WindowsDeleteString(h: Pointer?): Int

        companion object { val INSTANCE: Combase by lazy { Native.load("combase", Combase::class.java) } }
    }

    companion object {
        /** Button handlers of closed players (a few hundred bytes each, one per play): see [close]. */
        private val retired = ArrayList<ComHandler>()

        /** Media keys and the overlay for the window [hwnd] (a top-level window of this process); [onButton] on any thread. */
        fun attach(hwnd: Long, onButton: (Button) -> Unit): MediaControls? {
            if (!System.getProperty("os.name", "").startsWith("Windows")) return null
            return MediaControls(onButton).also { it.start(hwnd) }
        }

        private const val E_NOINTERFACE = 0x80004002.toInt()
        private const val RPC_E_CHANGED_MODE = 0x80010106.toInt()
        private const val VK_MEDIA_NEXT = 0xB0
        private const val VK_MEDIA_STOP = 0xB2
        private const val VK_MEDIA_PLAY_PAUSE = 0xB3

        private const val IID_UNKNOWN = "{00000000-0000-0000-C000-000000000046}"
        private const val IID_AGILE = "{94ea2b94-e9cc-49e0-c0ff-ee64ca8f5b90}"
        private const val IID_SMTC_INTEROP = "{ddb0472d-c911-4a1f-86d9-dc3d71a95f5a}"
        private const val IID_SMTC = "{99fa3ff4-1742-42a6-902e-087d41f965ec}"
        private const val IID_BUTTON_HANDLER = "{0557e996-7b23-5bae-aa81-ea0d671143a4}"
        private const val IID_URI_FACTORY = "{44a9796f-723e-4fdf-a218-033e75b0c084}"
        private const val IID_STREAM_REF_STATICS = "{857309dc-3fbf-4e7d-986f-ef3b1a07a964}"

        // vtable slots: IUnknown 0-2, IInspectable 3-5, then each interface's methods in windows.media.h order.
        private const val INTEROP_GET_FOR_WINDOW = 6
        private const val SMTC_PUT_STATUS = 7
        private const val SMTC_GET_UPDATER = 8
        private const val SMTC_PUT_ENABLED = 11
        private const val SMTC_PUT_PLAY = 13
        private const val SMTC_PUT_STOP = 15
        private const val SMTC_PUT_PAUSE = 17
        private const val SMTC_PUT_PREVIOUS = 25
        private const val SMTC_PUT_NEXT = 27
        private const val SMTC_ADD_BUTTON = 32
        private const val SMTC_REMOVE_BUTTON = 33
        private const val UPDATER_PUT_TYPE = 7
        private const val UPDATER_PUT_THUMBNAIL = 11
        private const val UPDATER_GET_VIDEO = 13
        private const val UPDATER_CLEAR = 16
        private const val UPDATER_UPDATE = 17
        private const val VIDEO_PUT_TITLE = 7
        private const val VIDEO_PUT_SUBTITLE = 9
        private const val ARGS_GET_BUTTON = 6
        private const val URI_CREATE = 6
        private const val STREAM_REF_FROM_URI = 7

        private const val STATUS_CLOSED = 0
        private const val STATUS_PLAYING = 3
        private const val STATUS_PAUSED = 4
        private const val TYPE_VIDEO = 2

        private fun iid(s: String) = Guid.GUID(s)

        private fun fn(self: Pointer, slot: Int): Function =
            Function.getFunction(self.getPointer(0).getPointer(slot.toLong() * Native.POINTER_SIZE))

        /** this->vtbl[slot](this, args...) returning its HRESULT. */
        private fun call(self: Pointer, slot: Int, vararg args: Any?): Int = fn(self, slot).invokeInt(arrayOf(self, *args))
        private fun callP(self: Pointer, slot: Int, vararg args: Any?): Int = call(self, slot, *args)
        private fun callL(self: Pointer, slot: Int, v: Long): Int = fn(self, slot).invokeInt(arrayOf(self, v))
        private fun release(p: Pointer) { runCatching { fn(p, 2).invokeInt(arrayOf(p)) } }
    }
}
