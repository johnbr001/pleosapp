package com.ys.pleosapp.shared.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Run log for the Live Drive Cast, written both to logcat (tag
 * `LiveDriveCast`) and to `filesDir/cast.log` so a drive can be debugged
 * after the fact.
 *
 * Pull it from a debuggable build with:
 * `adb exec-out run-as com.ys.pleosapp cat files/cast.log > cast.log`
 * or live with: `adb logcat -s LiveDriveCast:V`
 *
 * All writes are best-effort and never throw; logging must never crash the
 * show. The file is truncated past [MAX_BYTES] to bound storage.
 */
object CastLog {
    const val TAG = "LiveDriveCast"
    private const val FILE_NAME = "cast.log"
    private const val MAX_BYTES = 256 * 1024L

    @Volatile private var dir: File? = null
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    fun init(context: Context) {
        dir = context.filesDir
    }

    /**
     * Records any uncaught exception (which would otherwise kill the process
     * with only logcat as a witness) to `cast.log`, then lets the system
     * handle it normally. Call once per process, early in service startup.
     */
    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                dir ?: return@runCatching
                e("CRASH on ${thread.name}", error as? Throwable)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun i(message: String) = write("I", message, null)
    fun w(message: String) = write("W", message, null)
    fun e(message: String, error: Throwable? = null) = write("E", message, error)

    private fun write(level: String, message: String, error: Throwable?) {
        runCatching {
            when (level) {
                "W" -> Log.w(TAG, message, error)
                "E" -> Log.e(TAG, message, error)
                else -> Log.i(TAG, message)
            }
        }
        val dir = dir ?: return
        synchronized(lock) {
            runCatching {
                val file = File(dir, FILE_NAME)
                if (file.exists() && file.length() > MAX_BYTES) {
                    file.writeText("--- truncated, continuing ${stamp()} ---\n")
                }
                val line = "${stamp()} $level $message" +
                    (error?.let { "\n${Log.getStackTraceString(it)}" } ?: "") + "\n"
                file.appendText(line)
            }
        }
    }

    private fun stamp(): String = synchronized(lock) { time.format(Date()) }
}
