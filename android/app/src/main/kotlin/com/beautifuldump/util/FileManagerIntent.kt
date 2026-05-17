package com.beautifuldump.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Best-effort "open this directory in a file manager".  No public Android API
 * does this reliably, so we fall through a list of vendor-specific intents
 * before giving up on a SEND_MULTIPLE chooser.
 */
object FileManagerIntent {

    fun open(context: Context, path: String) {
        val dir = File(path)
        val attempts = buildList<Intent> {
            add(viewAsFolder(dir))
            add(viewAsTreeUri(dir))
            add(samsungMyFiles(dir))
            add(miuiExplorer(dir))
            add(filesByGoogle(dir))
        }
        for (intent in attempts) {
            try {
                context.startActivity(intent.addFlag())
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        // Last resort - share all files via chooser so the user picks an app.
        shareAll(context, dir)
    }

    private fun Intent.addFlag(): Intent = apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun viewAsFolder(dir: File) = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.parse(dir.absolutePath), "resource/folder")
    }

    private fun viewAsTreeUri(dir: File): Intent {
        val docId = "primary:" + dir.absolutePath.removePrefix("/sdcard/").removePrefix("/storage/emulated/0/")
        val uri = Uri.parse("content://com.android.externalstorage.documents/document/${Uri.encode(docId)}")
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "vnd.android.document/directory")
        }
    }

    private fun samsungMyFiles(dir: File) = Intent("com.sec.android.app.myfiles.PICK_DATA").apply {
        putExtra("CONTENT_TYPE", "*/*")
        putExtra("folderPath", dir.absolutePath)
    }

    private fun miuiExplorer(dir: File) = Intent(Intent.ACTION_VIEW).apply {
        setClassName("com.android.fileexplorer", "com.android.fileexplorer.FileExplorerTabActivity")
        putExtra("explorer_path", dir.absolutePath)
    }

    private fun filesByGoogle(dir: File) = Intent(Intent.ACTION_VIEW).apply {
        setPackage("com.google.android.documentsui")
        setDataAndType(Uri.parse(dir.absolutePath), "*/*")
    }

    private fun shareAll(context: Context, dir: File) {
        val files = dir.listFiles()?.takeIf { it.isNotEmpty() } ?: return
        val authority = "${context.packageName}.fileprovider"
        val uris = ArrayList<Uri>()
        for (f in files) {
            runCatching { uris += FileProvider.getUriForFile(context, authority, f) }
        }
        if (uris.isEmpty()) return
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "选择文件管理器").addFlag())
    }
}
