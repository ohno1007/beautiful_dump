package com.beautifuldump

import android.app.Application
import android.os.Environment
import android.util.Log
import com.topjohnwu.superuser.Shell
import java.io.File
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BdApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
        runCatching {
            // FLAG_MOUNT_MASTER intentionally NOT used: it triggers su --mount-master,
            // which Magisk supports but KernelSU/APatch may reject and fail Shell init.
            Shell.setDefaultBuilder(
                Shell.Builder.create()
                    .setFlags(Shell.FLAG_REDIRECT_STDERR)
                    .setTimeout(30)
            )
        }.onFailure { Log.w("bd", "libsu setDefaultBuilder failed", it) }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            runCatching {
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val out = File(
                    Environment.getExternalStorageDirectory(),
                    "beautiful_dump_crash.log"
                )
                out.parentFile?.mkdirs()
                PrintWriter(out.outputStream().bufferedWriter()).use { w ->
                    w.println("=== beautiful_dump crash $ts ===")
                    w.println("thread: ${thread.name}")
                    w.println("device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                    w.println("api:    ${android.os.Build.VERSION.SDK_INT}")
                    w.println()
                    ex.printStackTrace(w)
                    w.println()
                    var c: Throwable? = ex.cause
                    while (c != null) {
                        w.println("caused by:")
                        c.printStackTrace(w)
                        c = c.cause
                    }
                }
                Log.e("bd", "crash logged to ${out.absolutePath}", ex)
            }
            previous?.uncaughtException(thread, ex)
        }
    }
}
