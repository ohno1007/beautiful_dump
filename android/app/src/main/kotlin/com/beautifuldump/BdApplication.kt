package com.beautifuldump

import android.app.Application
import com.topjohnwu.superuser.Shell

class BdApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Configure libsu BEFORE anything else can touch Shell.
        runCatching {
            Shell.setDefaultBuilder(
                Shell.Builder.create()
                    .setFlags(Shell.FLAG_REDIRECT_STDERR or Shell.FLAG_MOUNT_MASTER)
                    .setTimeout(30)
            )
        }
    }
}
