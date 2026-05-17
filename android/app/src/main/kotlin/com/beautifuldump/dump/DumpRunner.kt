package com.beautifuldump.dump

import android.content.Context
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONObject
import java.io.File

data class DumpFile(val label: String, val path: String, val sizeBytes: Long)

data class DumpResult(
    val packageName: String,
    val outDir: String,
    val pid: Int,
    val files: List<DumpFile>,
    val metadataAddr: String? = null,
    val errors: List<String> = emptyList(),
) {
    val success: Boolean get() = files.any { it.label == "libil2cpp.so" }
}

sealed interface DumpEvent {
    data class Log(val line: String) : DumpEvent
    data class Progress(val message: String) : DumpEvent
    data class Done(val result: DumpResult) : DumpEvent
    data class Failure(val message: String) : DumpEvent
}

class DumpRunner(private val context: Context) {

    companion object {
        const val DUMP_ROOT = "/sdcard/beautiful_dump"
        // jniLibs/<abi>/libbd_dumper.so is extracted here at install time.
        // On API 23+ the loader keeps it executable, so we exec it directly.
        const val DUMPER_LIB = "libbd_dumper.so"
    }

    private val dumperPath: String
        get() = "${context.applicationInfo.nativeLibraryDir}/$DUMPER_LIB"

    init {
        // Run *every* libsu command as root in a single shell session.
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(Shell.Builder.create().setTimeout(30))
    }

    fun ensureRoot(): Boolean = Shell.getShell().isRoot

    /** Returns PID of [pkg] or 0. Uses `pidof` if available, otherwise /proc walk. */
    fun pidOf(pkg: String): Int {
        val out = Shell.cmd("pidof $pkg").exec().out.firstOrNull()?.trim().orEmpty()
        if (out.isNotEmpty()) return out.split(" ").first().toIntOrNull() ?: 0
        // pidof might not exist on stock images; fall back to a /proc scan
        val fallback = Shell.cmd(
            "for p in /proc/[0-9]*; do " +
                "cmd=\$(cat \$p/cmdline 2>/dev/null | tr -d '\\0'); " +
                "if [ \"\$cmd\" = \"$pkg\" ]; then echo \${p##*/}; break; fi; done"
        ).exec().out.firstOrNull()?.trim().orEmpty()
        return fallback.toIntOrNull() ?: 0
    }

    fun launch(pkg: String) {
        Shell.cmd("monkey -p $pkg -c android.intent.category.LAUNCHER 1").exec()
    }

    fun forceStop(pkg: String) {
        Shell.cmd("am force-stop $pkg").exec()
    }

    /** Wait up to [timeoutMs] for the target to load libil2cpp.so. Returns PID or 0. */
    fun waitForIl2Cpp(pkg: String, timeoutMs: Long = 25_000): Int {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val pid = pidOf(pkg)
            if (pid > 0) {
                val maps = Shell.cmd("grep -m1 libil2cpp.so /proc/$pid/maps").exec()
                if (maps.out.isNotEmpty()) return pid
            }
            Thread.sleep(500)
        }
        return 0
    }

    fun run(pkg: String, magic: String? = null): Flow<DumpEvent> = flow {
        if (!ensureRoot()) {
            emit(DumpEvent.Failure("未授予 root 权限")); return@flow
        }

        emit(DumpEvent.Progress("解析 PID…"))
        var pid = pidOf(pkg)
        if (pid == 0) {
            emit(DumpEvent.Log("应用未运行，尝试启动"))
            launch(pkg)
            emit(DumpEvent.Progress("等待 libil2cpp.so 加载…"))
            pid = waitForIl2Cpp(pkg)
            if (pid == 0) {
                emit(DumpEvent.Failure("无法定位 $pkg 的 PID 或 libil2cpp.so 未加载"))
                return@flow
            }
        } else {
            emit(DumpEvent.Log("发现 PID=$pid"))
        }

        val outDir = "$DUMP_ROOT/${pkg.replace('.', '_')}"
        Shell.cmd("mkdir -p $outDir && chmod 0755 $outDir").exec()

        // Make sure the dumper is executable. On some OEMs the loader
        // clears the exec bit when extracting jniLibs - force it back.
        Shell.cmd("chmod 0755 $dumperPath").exec()

        val magicArg = magic?.let { " ${it.replace(" ", "")}" }.orEmpty()
        emit(DumpEvent.Progress("内存 dump 中…"))
        val cmd = "$dumperPath $pid $outDir$magicArg"
        emit(DumpEvent.Log("$ $cmd"))

        val result = Shell.cmd(cmd).exec()
        val files = mutableListOf<DumpFile>()
        val errors = mutableListOf<String>()
        var metaAddr: String? = null

        for (line in result.out) {
            emit(DumpEvent.Log(line))
            val obj = runCatching { JSONObject(line) }.getOrNull() ?: continue
            when (obj.optString("event")) {
                "libil2cpp" -> files += DumpFile("libil2cpp.so", obj.getString("path"), obj.getLong("size"))
                "libunity" -> files += DumpFile("libunity.so", obj.getString("path"), obj.getLong("size"))
                "metadata" -> {
                    if (obj.has("error")) errors += obj.getString("error")
                    else {
                        files += DumpFile("global-metadata.dat", obj.getString("path"), obj.getLong("size"))
                        metaAddr = if (obj.has("addr")) obj.getString("addr") else null
                    }
                }
            }
        }
        for (line in result.err) {
            emit(DumpEvent.Log("[err] $line"))
            errors += line
        }

        Shell.cmd("chmod -R 0644 $outDir/*").exec()
        // Trigger MediaScanner so the files show up in third-party file managers.
        Shell.cmd("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://$outDir").exec()

        if (files.isEmpty()) {
            emit(DumpEvent.Failure(errors.lastOrNull() ?: "dumper 未产生任何文件"))
            return@flow
        }
        emit(DumpEvent.Done(DumpResult(pkg, outDir, pid, files, metaAddr, errors)))
    }.flowOn(Dispatchers.IO)
}
