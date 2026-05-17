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
        // We ship the dumper as libbd_dumper.so inside the APK.
        const val DUMPER_LIB = "libbd_dumper.so"
        // Modern AGP keeps native libs zip-aligned inside the APK and never
        // extracts them to disk - the nativeLibraryDir path is virtual and
        // not directly exec'able. We unpack to a real path under
        // /data/local/tmp at runtime so execve works regardless of OEM /
        // SELinux quirks.
        const val DUMPER_BIN = "/data/local/tmp/bd_dumper"
    }

    // Shell configuration lives in BdApplication.onCreate so we don't risk
    // hitting "shell already created" IllegalStateException here.
    fun ensureRoot(): Boolean = Shell.getShell().isRoot

    /** Extract libbd_dumper.so from our own APK to a real disk path and
     *  give it execute permissions via root. Returns the runnable path. */
    private fun stageDumper(): String {
        val apkPath = context.applicationInfo.sourceDir
        val tmp = File(context.cacheDir, "bd_dumper").apply { parentFile?.mkdirs() }
        // Always re-extract on app upgrade; the file is tiny.
        val pkgVer = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        val marker = File(context.cacheDir, "bd_dumper.v")
        val current = marker.takeIf { it.exists() }?.readText()?.trim()
        if (!tmp.exists() || current != pkgVer.toString()) {
            ZipFile(apkPath).use { zf ->
                val entry = zf.getEntry("lib/arm64-v8a/$DUMPER_LIB")
                    ?: error("$DUMPER_LIB missing from APK")
                zf.getInputStream(entry).use { ins ->
                    tmp.outputStream().use { out -> ins.copyTo(out) }
                }
            }
            marker.writeText(pkgVer.toString())
        }
        // cacheDir is mounted noexec, so move via root to /data/local/tmp/.
        val cp = Shell.cmd(
            "cp -f ${tmp.absolutePath} $DUMPER_BIN",
            "chmod 0755 $DUMPER_BIN",
            "chcon u:object_r:shell_data_file:s0 $DUMPER_BIN 2>/dev/null || true",
        ).exec()
        if (!cp.isSuccess) {
            error("failed to stage dumper: exit=${cp.code} out=${cp.out.joinToString("\\n")}")
        }
        return DUMPER_BIN
    }

    /** Returns PID of [pkg] or 0. Uses `pidof` if available, otherwise /proc walk. */
    fun pidOf(pkg: String): Int {
        val out = Shell.cmd("pidof $pkg").exec().out.firstOrNull()?.trim().orEmpty()
        if (out.isNotEmpty()) return out.split(" ").first().toIntOrNull() ?: 0
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

        emit(DumpEvent.Progress("准备 dumper…"))
        val dumper = try {
            stageDumper()
        } catch (e: Throwable) {
            emit(DumpEvent.Failure("stage dumper 失败: ${e.message}")); return@flow
        }
        emit(DumpEvent.Log("dumper -> $dumper"))

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
            // Verify libil2cpp.so is actually mapped before invoking the dumper.
            val maps = Shell.cmd("grep -c libil2cpp.so /proc/$pid/maps || true").exec()
            val hits = maps.out.firstOrNull()?.trim()?.toIntOrNull() ?: 0
            emit(DumpEvent.Log("libil2cpp.so segments in /proc/$pid/maps: $hits"))
            if (hits == 0) {
                emit(DumpEvent.Failure("PID $pid 的 maps 里没有 libil2cpp.so —— 加固/壳延迟解密?"))
                return@flow
            }
        }

        val outDir = "$DUMP_ROOT/${pkg.replace('.', '_')}"
        Shell.cmd("mkdir -p $outDir && chmod 0755 $outDir").exec()

        val magicArg = magic?.let { " ${it.replace(" ", "")}" }.orEmpty()
        emit(DumpEvent.Progress("内存 dump 中…"))
        val cmd = "$dumper $pid $outDir$magicArg"
        emit(DumpEvent.Log("$ $cmd"))

        val result = Shell.cmd(cmd).exec()
        emit(DumpEvent.Log("[exit ${result.code}]"))

        val files = mutableListOf<DumpFile>()
        val errors = mutableListOf<String>()
        var metaAddr: String? = null
        var detectedMagic: String? = null

        for (line in result.out) {
            emit(DumpEvent.Log(line))
            val obj = runCatching { JSONObject(line) }.getOrNull() ?: continue
            when (obj.optString("event")) {
                "libil2cpp" -> files += DumpFile(
                    "libil2cpp.so",
                    obj.getString("path"), obj.getLong("size"),
                )
                "libunity" -> files += DumpFile(
                    "libunity.so",
                    obj.getString("path"), obj.getLong("size"),
                )
                "sdk" -> {
                    val source = obj.optString("source", "?")
                    val count = obj.optLong("count", 0)
                    // sdk_strings_* files don't have a 'size' field, use line count
                    files += DumpFile("sdk[$source] ($count names)",
                        obj.getString("path"), 0L)
                }
                "metadata" -> {
                    if (obj.has("error")) {
                        errors += obj.getString("error")
                    } else {
                        val idx = obj.optInt("idx", 0)
                        val label = if (idx == 0) "global-metadata.dat"
                                    else "global-metadata.candidate${idx}.dat"
                        files += DumpFile(label, obj.getString("path"), obj.getLong("size"))
                        if (idx == 0) {
                            metaAddr = if (obj.has("addr")) obj.getString("addr") else null
                            detectedMagic = if (obj.has("magic")) obj.getString("magic") else null
                        }
                    }
                }
            }
        }

        Shell.cmd("chmod -R 0644 $outDir/* 2>/dev/null || true").exec()
        Shell.cmd("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://$outDir").exec()

        if (files.none { it.label == "libil2cpp.so" }) {
            val hint = when (result.code) {
                127 -> "shell 找不到 dumper - SELinux 阻止? 上下文: " +
                        Shell.cmd("ls -Z $dumper").exec().out.joinToString()
                126 -> "权限拒绝 - 可能 noexec mount 或 SELinux denial"
                139 -> "dumper 段错误 - 可能是 /proc/$pid/mem 读取被拒"
                else -> errors.lastOrNull() ?: "dumper 未产生任何文件 (exit ${result.code})"
            }
            emit(DumpEvent.Failure(hint))
            return@flow
        }
        val noteParts = mutableListOf<String>()
        if (detectedMagic != null && detectedMagic != "0xfab11baf")
            noteParts += "检测到自定义 magic: $detectedMagic"
        val metaSummary = if (metaAddr != null) "$metaAddr  ${noteParts.joinToString(" · ")}" else null
        emit(DumpEvent.Done(DumpResult(pkg, outDir, pid, files, metaSummary, errors)))
    }.flowOn(Dispatchers.IO)
}
