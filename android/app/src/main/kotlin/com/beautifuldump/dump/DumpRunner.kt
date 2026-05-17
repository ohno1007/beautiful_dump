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
    val injected: Boolean = false,
    val sdkPath: String? = null,
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
        const val DUMPER_LIB = "libbd_dumper.so"
        const val INJECT_LIB = "libbd_inject.so"
        const val PAYLOAD_LIB = "libbd_payload.so"
        const val DUMPER_BIN = "/data/local/tmp/bd_dumper"
        const val INJECT_BIN = "/data/local/tmp/bd_inject"
        const val PAYLOAD_BIN = "/data/local/tmp/libbd_payload.so"
    }

    fun ensureRoot(): Boolean = Shell.getShell().isRoot

    private fun extractFromApk(libName: String, destFile: File) {
        val apkPath = context.applicationInfo.sourceDir
        ZipFile(apkPath).use { zf ->
            val entry = zf.getEntry("lib/arm64-v8a/$libName")
                ?: error("$libName missing from APK")
            zf.getInputStream(entry).use { ins ->
                destFile.outputStream().use { out -> ins.copyTo(out) }
            }
        }
    }

    private fun stageDumper(): String {
        val tmp = File(context.cacheDir, "bd_dumper").apply { parentFile?.mkdirs() }
        val pkgVer = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        val marker = File(context.cacheDir, "bd_dumper.v")
        if (!tmp.exists() || marker.takeIf { it.exists() }?.readText()?.trim() != pkgVer.toString()) {
            extractFromApk(DUMPER_LIB, tmp)
            marker.writeText(pkgVer.toString())
        }
        val r = Shell.cmd(
            "cp -f ${tmp.absolutePath} $DUMPER_BIN",
            "chmod 0755 $DUMPER_BIN",
            "chcon u:object_r:shell_data_file:s0 $DUMPER_BIN 2>/dev/null || true",
        ).exec()
        if (!r.isSuccess) error("stage dumper failed: ${r.out}")
        return DUMPER_BIN
    }

    private fun stageInjector(): Pair<String, String> {
        val injTmp = File(context.cacheDir, "bd_inject")
        val payTmp = File(context.cacheDir, "libbd_payload.so")
        val pkgVer = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        val marker = File(context.cacheDir, "bd_inject.v")
        if (!injTmp.exists() || !payTmp.exists() ||
            marker.takeIf { it.exists() }?.readText()?.trim() != pkgVer.toString()) {
            extractFromApk(INJECT_LIB, injTmp)
            extractFromApk(PAYLOAD_LIB, payTmp)
            marker.writeText(pkgVer.toString())
        }
        // The payload must be readable + dlopen-able from the target's
        // untrusted_app SELinux context. apk_data_file works on every
        // Android we've tested; we also try system_lib_file as a fallback.
        Shell.cmd(
            "cp -f ${injTmp.absolutePath} $INJECT_BIN",
            "chmod 0755 $INJECT_BIN",
            "chcon u:object_r:shell_data_file:s0 $INJECT_BIN 2>/dev/null || true",
            "cp -f ${payTmp.absolutePath} $PAYLOAD_BIN",
            "chmod 0644 $PAYLOAD_BIN",
            "chcon u:object_r:apk_data_file:s0 $PAYLOAD_BIN 2>/dev/null || " +
                "chcon u:object_r:system_lib_file:s0 $PAYLOAD_BIN 2>/dev/null || true",
        ).exec()
        return INJECT_BIN to PAYLOAD_BIN
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

    /** Inject the payload .so into the target game and wait for its SDK file. */
    private suspend fun runInject(
        pkg: String,
        pid: Int,
        outDir: String,
        emit: suspend (DumpEvent) -> Unit,
    ): String? {
        emit(DumpEvent.Progress("准备注入器…"))
        val (injectBin, payloadSo) = stageInjector()

        emit(DumpEvent.Log("$ $injectBin $pid $payloadSo"))
        emit(DumpEvent.Progress("ptrace 注入中…"))
        val r = Shell.cmd("$injectBin $pid $payloadSo").exec()
        for (line in r.out) emit(DumpEvent.Log(line))
        emit(DumpEvent.Log("[inject exit ${r.code}]"))
        if (r.code != 0) return null

        // Payload writes to the game's private cache dir; poll for it.
        val sdkSrc = "/data/data/$pkg/cache/bd_sdk.cs"
        val statusSrc = "/data/data/$pkg/cache/bd_inject_status.txt"
        emit(DumpEvent.Progress("等待 payload 写出 SDK…"))
        var lastSize = -1L
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            val sz = Shell.cmd("stat -c %s $sdkSrc 2>/dev/null").exec()
                .out.firstOrNull()?.trim()?.toLongOrNull() ?: 0
            if (sz > 0 && sz == lastSize) break
            lastSize = sz
            Thread.sleep(1500)
        }
        // Pull the payload status (success/failure markers) into the log.
        val status = Shell.cmd("cat $statusSrc 2>/dev/null").exec().out
        for (s in status) emit(DumpEvent.Log("[payload] $s"))

        if (lastSize <= 0) {
            emit(DumpEvent.Log("[inject] no SDK file produced after 60s"))
            return null
        }

        val sdkDest = "$outDir/inject_sdk.cs"
        Shell.cmd("cp $sdkSrc $sdkDest", "chmod 0644 $sdkDest").exec()
        emit(DumpEvent.Log("[inject] sdk -> $sdkDest ($lastSize bytes)"))
        return sdkDest
    }

    fun run(pkg: String, magic: String? = null): Flow<DumpEvent> = flow {
        if (!ensureRoot()) {
            emit(DumpEvent.Failure("未授予 root 权限")); return@flow
        }

        emit(DumpEvent.Progress("准备 dumper…"))
        val dumper = try { stageDumper() } catch (e: Throwable) {
            emit(DumpEvent.Failure("stage dumper 失败: ${e.message}")); return@flow
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

        val magicArg = magic?.let { " ${it.replace(" ", "")}" }.orEmpty()
        emit(DumpEvent.Progress("内存 dump 中…"))
        val cmd = "$dumper $pid $outDir$magicArg"
        emit(DumpEvent.Log("$ $cmd"))
        val result = Shell.cmd(cmd).exec()
        emit(DumpEvent.Log("[dumper exit ${result.code}]"))

        val files = mutableListOf<DumpFile>()
        val errors = mutableListOf<String>()
        var metaAddr: String? = null
        var detectedMagic: String? = null

        for (line in result.out) {
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
                127 -> "shell 找不到 dumper - SELinux 阻止"
                126 -> "权限拒绝 - noexec mount 或 SELinux denial"
                139 -> "dumper 段错误 - /proc/$pid/mem 读取被拒"
                else -> errors.lastOrNull() ?: "dumper 未产生任何文件 (exit ${result.code})"
            }
            emit(DumpEvent.Failure(hint))
            return@flow
        }

        // === Active phase: inject payload .so to call IL2CPP runtime APIs ===
        // This pulls class/method names + RVAs directly from the live
        // runtime, bypassing metadata encryption.
        var injected = false
        var sdkPath: String? = null
        try {
            sdkPath = runInject(pkg, pid, outDir) { emit(it) }
            injected = sdkPath != null
            if (injected) {
                files += DumpFile("inject_sdk.cs (★ SDK)", sdkPath!!, 0L)
            }
        } catch (t: Throwable) {
            emit(DumpEvent.Log("[inject] failed: ${t.message}"))
        }

        val noteParts = mutableListOf<String>()
        if (detectedMagic != null && detectedMagic != "0xfab11baf")
            noteParts += "自定义 magic: $detectedMagic"
        if (injected) noteParts += "已注入 ✓"
        val metaSummary = noteParts.joinToString(" · ").ifBlank { metaAddr }

        emit(DumpEvent.Done(DumpResult(
            packageName = pkg, outDir = outDir, pid = pid, files = files,
            metadataAddr = metaSummary, errors = errors,
            injected = injected, sdkPath = sdkPath,
        )))
    }.flowOn(Dispatchers.IO)
}
