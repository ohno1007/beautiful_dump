package com.beautifuldump.dump

import android.content.Context
import com.topjohnwu.superuser.Shell
import java.io.File

/**
 * Wraps Perfare/Zygisk-Il2CppDumper - the proven open-source approach for
 * getting an SDK out of heavily-protected IL2CPP games. The module zip is
 * bundled as an asset (we forked it to read the target package from
 * /data/local/tmp/bd_zygisk_target.txt at runtime so one build works for
 * any game).
 *
 * Flow:
 *  1. install()         - flashes the module via `magisk --install-module`,
 *                         needs a reboot to take effect.
 *  2. isInstalled()     - true once the module is present in /data/adb/modules.
 *  3. configure(pkg)    - writes the target package name.
 *  4. launch + waitForDump(pkg) - the module writes dump.cs +
 *                         global-metadata.dat into the game's files dir.
 *  5. pull(pkg, outDir) - copies the dump artefacts to /sdcard.
 */
class ZygiskHelper(private val context: Context) {

    companion object {
        const val MODULE_ID = "zygisk_il2cppdumper"
        const val ASSET_NAME = "zygisk-il2cppdumper.zip"
        const val TARGET_CONF = "/data/local/tmp/bd_zygisk_target.txt"
    }

    fun isInstalled(): Boolean {
        val r = Shell.cmd("ls /data/adb/modules/$MODULE_ID/module.prop 2>/dev/null").exec()
        return r.out.isNotEmpty()
    }

    fun isZygiskAvailable(): Boolean {
        // Magisk with Zygisk enabled OR KernelSU with ZygiskNext.
        val magisk = Shell.cmd("magisk -V 2>/dev/null").exec().out.firstOrNull() ?: ""
        val zygiskNext = Shell.cmd(
            "ls /data/adb/modules/zygisksu/module.prop 2>/dev/null"
        ).exec().out.isNotEmpty()
        return magisk.toIntOrNull()?.let { it >= 24000 } == true || zygiskNext
    }

    fun extractZip(): File {
        val dest = File(context.cacheDir, ASSET_NAME).apply { parentFile?.mkdirs() }
        context.assets.open(ASSET_NAME).use { input ->
            dest.outputStream().use { input.copyTo(it) }
        }
        return dest
    }

    /** Flash the module via whatever root manager the device has.
     *  Supports Magisk, KernelSU (ksud), and APatch (apd). */
    fun install(): InstallResult {
        if (!Shell.getShell().isRoot) return InstallResult(false, "需要 root")
        val zip = try { extractZip() } catch (e: Throwable) {
            return InstallResult(false, "解包失败: ${e.message}")
        }
        val remoteZip = "/data/local/tmp/$ASSET_NAME"
        Shell.cmd(
            "cp ${zip.absolutePath} $remoteZip",
            "chmod 0644 $remoteZip",
        ).exec()

        // Detect which root manager is in use and call the matching CLI.
        // We probe via `which` since Magisk's binary path varies by version.
        val has = mutableMapOf<String, Boolean>()
        for (bin in listOf("magisk", "ksud", "apd")) {
            has[bin] = Shell.cmd("which $bin 2>/dev/null").exec().out.any { it.isNotBlank() }
        }

        val cmds = listOfNotNull(
            if (has["magisk"] == true) "magisk --install-module $remoteZip" else null,
            if (has["ksud"] == true) "ksud module install $remoteZip" else null,
            if (has["apd"] == true) "apd module install $remoteZip" else null,
        )

        if (cmds.isEmpty()) {
            return InstallResult(
                success = false,
                message = "未检测到 magisk / ksud / apd。\n" +
                          "请打开你的 root 管理器 (KernelSU Manager 等), " +
                          "选「从存储安装模块」，文件路径:\n$remoteZip",
            )
        }

        val log = StringBuilder()
        var ok = false
        for (cmd in cmds) {
            val r = Shell.cmd(cmd).exec()
            log.append("$ $cmd\n")
            (r.out + r.err).forEach { log.append(it).append('\n') }
            log.append("[exit ${r.code}]\n")
            if (r.isSuccess) { ok = true; break }
        }
        if (!ok) ok = isInstalled()

        return InstallResult(
            success = ok,
            message = if (ok) "模块已安装，**请重启设备后再 Dump**" else "安装失败:\n$log",
            log = log.toString(),
        )
    }

    fun configure(packageName: String) {
        Shell.cmd(
            "mkdir -p /data/local/tmp",
            "echo -n '$packageName' > $TARGET_CONF",
            "chmod 0644 $TARGET_CONF",
        ).exec()
    }

    /**
     * Poll for the Zygisk module's output inside the game's files dir.
     * The module writes dump.cs, script.json, global-metadata.dat there.
     */
    fun waitForDump(packageName: String, timeoutMs: Long = 90_000): String? {
        val dir = "/data/data/$packageName/files"
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastSize = -1L
        while (System.currentTimeMillis() < deadline) {
            val dumpCs = Shell.cmd("stat -c %s $dir/dump.cs 2>/dev/null").exec()
                .out.firstOrNull()?.trim()?.toLongOrNull() ?: 0
            if (dumpCs > 0 && dumpCs == lastSize) return dir
            lastSize = dumpCs
            Thread.sleep(2000)
        }
        return if (lastSize > 0) dir else null
    }

    fun pull(srcDir: String, destDir: String): List<DumpFile> {
        val listed = Shell.cmd("ls -1 $srcDir 2>/dev/null").exec().out
        val out = mutableListOf<DumpFile>()
        for (name in listed) {
            val n = name.trim()
            if (n.isEmpty()) continue
            // Skip nominalcache / large unrelated game files - only pull
            // the Zygisk-Il2CppDumper artefacts.
            val keep = n == "dump.cs" || n == "script.json" ||
                       n == "global-metadata.dat" || n.startsWith("il2cpp_dump")
            if (!keep) continue
            val sizeR = Shell.cmd("stat -c %s $srcDir/$n 2>/dev/null").exec()
                .out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
            val dest = "$destDir/$n"
            Shell.cmd("cp $srcDir/$n $dest", "chmod 0644 $dest").exec()
            val label = when (n) {
                "dump.cs" -> "dump.cs ★ SDK"
                "script.json" -> "script.json (RVAs)"
                "global-metadata.dat" -> "global-metadata.dat"
                else -> n
            }
            out += DumpFile(label, dest, sizeR)
        }
        return out
    }

    data class InstallResult(val success: Boolean, val message: String, val log: String = "")
}
