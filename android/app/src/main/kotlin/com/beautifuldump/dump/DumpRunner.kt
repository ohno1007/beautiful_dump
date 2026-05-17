package com.beautifuldump.dump

import android.content.Context
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

data class DumpFile(val label: String, val path: String, val sizeBytes: Long)

data class DumpResult(
    val packageName: String,
    val outDir: String,
    val pid: Int,
    val files: List<DumpFile>,
    val metadataAddr: String? = null,
    val errors: List<String> = emptyList(),
    val zygiskActive: Boolean = false,
) {
    val success: Boolean get() = files.isNotEmpty()
    val hasSdk: Boolean get() = files.any { it.label.startsWith("dump.cs") }
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
        const val DUMPER_LIB = "libbd_dumper.so"
        const val DUMPER_BIN = "/data/local/tmp/bd_dumper"
    }

    val zygisk = ZygiskHelper(context)

    fun ensureRoot(): Boolean = Shell.getShell().isRoot

    private fun stageDumper(): String {
        val tmp = File(context.cacheDir, "bd_dumper").apply { parentFile?.mkdirs() }
        val pkgVer = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        val marker = File(context.cacheDir, "bd_dumper.v")
        if (!tmp.exists() || marker.takeIf { it.exists() }?.readText()?.trim() != pkgVer.toString()) {
            val apkPath = context.applicationInfo.sourceDir
            ZipFile(apkPath).use { zf ->
                val entry = zf.getEntry("lib/arm64-v8a/$DUMPER_LIB")
                    ?: error("$DUMPER_LIB missing from APK")
                zf.getInputStream(entry).use { ins ->
                    tmp.outputStream().use { ins.copyTo(it) }
                }
            }
            marker.writeText(pkgVer.toString())
        }
        Shell.cmd(
            "cp -f ${tmp.absolutePath} $DUMPER_BIN",
            "chmod 0755 $DUMPER_BIN",
            "chcon u:object_r:shell_data_file:s0 $DUMPER_BIN 2>/dev/null || true",
        ).exec()
        return DUMPER_BIN
    }

    fun pidOf(pkg: String): Int {
        val out = Shell.cmd("pidof $pkg").exec().out.firstOrNull()?.trim().orEmpty()
        if (out.isNotEmpty()) return out.split(" ").first().toIntOrNull() ?: 0
        val fb = Shell.cmd(
            "for p in /proc/[0-9]*; do " +
                "cmd=\$(cat \$p/cmdline 2>/dev/null | tr -d '\\0'); " +
                "if [ \"\$cmd\" = \"$pkg\" ]; then echo \${p##*/}; break; fi; done"
        ).exec().out.firstOrNull()?.trim().orEmpty()
        return fb.toIntOrNull() ?: 0
    }

    fun launch(pkg: String) {
        Shell.cmd("monkey -p $pkg -c android.intent.category.LAUNCHER 1").exec()
    }

    fun forceStop(pkg: String) {
        Shell.cmd("am force-stop $pkg").exec()
    }

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

        val outDir = "$DUMP_ROOT/${pkg.replace('.', '_')}"
        Shell.cmd("mkdir -p $outDir && chmod 0755 $outDir").exec()

        val files = mutableListOf<DumpFile>()
        val errors = mutableListOf<String>()

        // === Primary path: Zygisk-Il2CppDumper ===
        // Active in-process at zygote stage - bypasses ptrace anti-debug
        // and metadata-on-demand encryption.
        val zygActive = zygisk.isInstalled()
        if (zygActive) {
            emit(DumpEvent.Log("Zygisk-Il2CppDumper 已安装 ✓"))
            emit(DumpEvent.Progress("配置目标包名…"))
            zygisk.configure(pkg)

            emit(DumpEvent.Progress("强制重启 $pkg…"))
            forceStop(pkg)
            // Clear stale output so we don't read a previous game's dump.
            Shell.cmd("rm -rf /data/data/$pkg/files/dump.cs " +
                      "/data/data/$pkg/files/global-metadata.dat " +
                      "/data/data/$pkg/files/script.json 2>/dev/null || true").exec()
            Thread.sleep(800)
            launch(pkg)

            emit(DumpEvent.Progress("等待游戏触发 il2cpp_init 并 dump (最多 90s)…"))
            val dumpDir = zygisk.waitForDump(pkg)
            if (dumpDir != null) {
                emit(DumpEvent.Log("Zygisk module wrote to $dumpDir"))
                val pulled = zygisk.pull(dumpDir, outDir)
                files += pulled
                for (f in pulled) emit(DumpEvent.Log("  pulled: ${f.label} (${f.sizeBytes} B)"))
            } else {
                emit(DumpEvent.Log("[zygisk] 在游戏 files/ 下没看到 dump.cs - " +
                    "目标包名是否拼对? 设备已重启了? Zygisk 是否启用?"))
            }
        }

        // === Fallback: passive memory dump (for unprotected games) ===
        emit(DumpEvent.Progress("内存 dump（备用）…"))
        try {
            val dumper = stageDumper()
            var pid = pidOf(pkg)
            if (pid == 0) {
                if (!zygActive) launch(pkg)
                pid = waitForIl2Cpp(pkg)
            }
            if (pid > 0) {
                val cmd = "$dumper $pid $outDir${magic?.let { " ${it.replace(" ", "")}" }.orEmpty()}"
                emit(DumpEvent.Log("$ $cmd"))
                val r = Shell.cmd(cmd).exec()
                emit(DumpEvent.Log("[dumper exit ${r.code}]"))
                for (line in r.out) {
                    emit(DumpEvent.Log(line))
                    val obj = runCatching { JSONObject(line) }.getOrNull() ?: continue
                    when (obj.optString("event")) {
                        "libil2cpp" -> files += DumpFile("libil2cpp.so",
                            obj.getString("path"), obj.getLong("size"))
                        "libunity" -> files += DumpFile("libunity.so",
                            obj.getString("path"), obj.getLong("size"))
                        "sdk" -> files += DumpFile(
                            "sdk[${obj.optString("source", "?")}] (${obj.optLong("count", 0)} names)",
                            obj.getString("path"), 0L,
                        )
                        "metadata" -> if (!obj.has("error")) {
                            val idx = obj.optInt("idx", 0)
                            val label = if (idx == 0) "global-metadata.dat (passive)"
                                        else "global-metadata.candidate${idx}.dat"
                            files += DumpFile(label, obj.getString("path"), obj.getLong("size"))
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            emit(DumpEvent.Log("[fallback] ${t.message}"))
        }

        Shell.cmd("chmod -R 0644 $outDir/* 2>/dev/null || true").exec()
        Shell.cmd("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://$outDir").exec()

        if (files.isEmpty()) {
            emit(DumpEvent.Failure(
                if (!zygActive) "什么也没拿到。强保护游戏请先安装 Zygisk-Il2CppDumper 模块。"
                else "Zygisk 已装但游戏没产出 dump - 检查游戏是否真的启动了 IL2CPP runtime"
            ))
            return@flow
        }
        emit(DumpEvent.Done(DumpResult(
            packageName = pkg, outDir = outDir, pid = 0, files = files,
            errors = errors, zygiskActive = zygActive,
        )))
    }.flowOn(Dispatchers.IO)
}
