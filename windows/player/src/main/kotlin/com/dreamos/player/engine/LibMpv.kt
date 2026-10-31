package com.dreamos.player.engine

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.PointerByReference
import java.io.File

/** libmpv's client API (client.h, API 2.x) and the software render API used for scrub previews (render.h). */
@Suppress("FunctionName")
interface LibMpv : Library {
    fun mpv_client_api_version(): Long
    fun mpv_create(): Pointer?
    fun mpv_initialize(ctx: Pointer): Int
    fun mpv_terminate_destroy(ctx: Pointer)
    fun mpv_error_string(error: Int): String
    fun mpv_free(data: Pointer)
    fun mpv_set_option_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_set_option(ctx: Pointer, name: String, format: Int, data: Pointer): Int
    fun mpv_command(ctx: Pointer, args: Array<String?>): Int
    fun mpv_command_async(ctx: Pointer, replyUserdata: Long, args: Array<String?>): Int
    fun mpv_set_property_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_set_property_async(ctx: Pointer, replyUserdata: Long, name: String, format: Int, data: Pointer): Int
    fun mpv_get_property(ctx: Pointer, name: String, format: Int, data: Pointer): Int
    fun mpv_get_property_string(ctx: Pointer, name: String): Pointer?
    fun mpv_observe_property(ctx: Pointer, replyUserdata: Long, name: String, format: Int): Int
    fun mpv_request_log_messages(ctx: Pointer, minLevel: String): Int
    fun mpv_wait_event(ctx: Pointer, timeout: Double): MpvEvent
    fun mpv_wakeup(ctx: Pointer)

    fun mpv_render_context_create(res: PointerByReference, ctx: Pointer, params: Array<MpvRenderParam>): Int
    fun mpv_render_context_set_update_callback(rctx: Pointer, callback: UpdateCallback?, data: Pointer?)
    fun mpv_render_context_update(rctx: Pointer): Long
    fun mpv_render_context_render(rctx: Pointer, params: Array<MpvRenderParam>): Int
    fun mpv_render_context_free(rctx: Pointer)

    fun interface UpdateCallback : Callback { fun invoke(data: Pointer?) }

    companion object {
        const val FORMAT_NONE = 0
        const val FORMAT_STRING = 1
        const val FORMAT_FLAG = 3
        const val FORMAT_INT64 = 4
        const val FORMAT_DOUBLE = 5
        const val FORMAT_NODE = 6

        const val EVENT_NONE = 0
        const val EVENT_SHUTDOWN = 1
        const val EVENT_LOG_MESSAGE = 2
        const val EVENT_COMMAND_REPLY = 5
        const val EVENT_START_FILE = 6
        const val EVENT_END_FILE = 7
        const val EVENT_FILE_LOADED = 8
        const val EVENT_VIDEO_RECONFIG = 17
        const val EVENT_AUDIO_RECONFIG = 18
        const val EVENT_SEEK = 20
        const val EVENT_PLAYBACK_RESTART = 21
        const val EVENT_PROPERTY_CHANGE = 22

        const val END_EOF = 0
        const val END_STOP = 2
        const val END_QUIT = 3
        const val END_ERROR = 4

        const val RENDER_PARAM_INVALID = 0
        const val RENDER_PARAM_API_TYPE = 1
        const val RENDER_PARAM_SW_SIZE = 17
        const val RENDER_PARAM_SW_FORMAT = 18
        const val RENDER_PARAM_SW_STRIDE = 19
        const val RENDER_PARAM_SW_POINTER = 20

        /** Where libmpv-2.dll is looked for, in order: `-Ddream.mpv.dir`, the packaged app's resources, `third_party/mpv`. */
        fun dirs(): List<File> = listOfNotNull(
            System.getProperty("dream.mpv.dir"),
            System.getProperty("compose.application.resources.dir"),
            "third_party/mpv", "../third_party/mpv",
        ).map { File(it) }

        /** Loaded once: from the first of [dirs] that has it, else the system path. */
        val api: LibMpv by lazy {
            val found = dirs().firstOrNull { File(it, "libmpv-2.dll").isFile }
            if (found != null) {
                val prior = System.getProperty("jna.library.path")
                System.setProperty("jna.library.path", listOfNotNull(found.absolutePath, prior).joinToString(File.pathSeparator))
            }
            Native.load("mpv-2", LibMpv::class.java)
        }
    }
}

/**
 * For the packaged app's self check (and anyone else): loads libmpv the way Dream Player does and says which one, as
 * "libmpv 2.5 (client API) from C:\...\libmpv-2.dll". Throws when it can't be loaded.
 */
object LibMpvProbe {
    fun probe(): String {
        val v = LibMpv.api.mpv_client_api_version()
        val file = LibMpv.dirs().map { File(it, "libmpv-2.dll") }.firstOrNull { it.isFile }?.absolutePath ?: "the system path"
        return "libmpv ${v shr 16}.${v and 0xFFFF} (client API) from $file"
    }
}

@Structure.FieldOrder("event_id", "error", "reply_userdata", "data")
class MpvEvent(p: Pointer?) : Structure(p) {
    @JvmField var event_id: Int = 0
    @JvmField var error: Int = 0
    @JvmField var reply_userdata: Long = 0
    @JvmField var data: Pointer? = null
    init { if (p != null) read() }
    constructor() : this(null)
}

@Structure.FieldOrder("name", "format", "data")
class MpvEventProperty(p: Pointer) : Structure(p) {
    @JvmField var name: String? = null
    @JvmField var format: Int = 0
    @JvmField var data: Pointer? = null
    init { read() }
}

@Structure.FieldOrder("reason", "error", "playlist_entry_id", "playlist_insert_id", "playlist_insert_num_entries")
class MpvEventEndFile(p: Pointer) : Structure(p) {
    @JvmField var reason: Int = 0
    @JvmField var error: Int = 0
    @JvmField var playlist_entry_id: Long = 0
    @JvmField var playlist_insert_id: Long = 0
    @JvmField var playlist_insert_num_entries: Int = 0
    init { read() }
}

@Structure.FieldOrder("prefix", "level", "text", "log_level")
class MpvEventLog(p: Pointer) : Structure(p) {
    @JvmField var prefix: String? = null
    @JvmField var level: String? = null
    @JvmField var text: String? = null
    @JvmField var log_level: Int = 0
    init { read() }
}

@Structure.FieldOrder("type", "data")
open class MpvRenderParam : Structure() {
    @JvmField var type: Int = 0
    @JvmField var data: Pointer? = null
}
