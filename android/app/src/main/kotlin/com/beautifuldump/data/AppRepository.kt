package com.beautifuldump.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import java.io.File

data class AppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val nativeLibDir: String,
    val likelyUnity: Boolean,
    val isSystem: Boolean,
)

class AppRepository(private val context: Context) {

    private val pm: PackageManager get() = context.packageManager

    fun listLaunchable(): List<AppInfo> {
        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(mainIntent, 0)
        return resolved
            .asSequence()
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .map { it.toAppInfo() }
            .sortedWith(compareByDescending<AppInfo> { it.likelyUnity }.thenBy { it.label.lowercase() })
            .toList()
    }

    private fun ApplicationInfo.toAppInfo(): AppInfo {
        val label = pm.getApplicationLabel(this).toString()
        val icon = runCatching { pm.getApplicationIcon(this) }.getOrNull()
        val unity = looksLikeUnity(this)
        val isSys = (flags and ApplicationInfo.FLAG_SYSTEM) != 0
        return AppInfo(
            packageName = packageName,
            label = label,
            icon = icon,
            nativeLibDir = nativeLibraryDir ?: "",
            likelyUnity = unity,
            isSystem = isSys,
        )
    }

    private fun looksLikeUnity(info: ApplicationInfo): Boolean {
        val dir = info.nativeLibraryDir ?: return false
        // Cheap heuristic: libil2cpp.so present, or libunity.so. Packed games
        // often hide libil2cpp.so until first launch, so this is best-effort.
        val candidates = listOf("libil2cpp.so", "libunity.so", "libmain.so")
        return candidates.any { File(dir, it).exists() } ||
                File(info.sourceDir.substringBeforeLast('/'), "base.apk").let { apk ->
                    apk.exists() && apk.length() > 0
                            && hasLibInApk(apk, "libil2cpp.so")
                }
    }

    private fun hasLibInApk(apk: File, libName: String): Boolean = runCatching {
        java.util.zip.ZipFile(apk).use { zf ->
            zf.entries().asSequence().any { it.name.endsWith("/$libName") }
        }
    }.getOrDefault(false)
}
