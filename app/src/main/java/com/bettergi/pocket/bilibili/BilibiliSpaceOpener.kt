package com.bettergi.pocket.bilibili

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

class BilibiliSpaceOpener(private val context: Context) {
    fun open() {
        val intent = resolveIntent() ?: return
        try {
            context.startActivity(intent)
        } catch (_: Throwable) {
            try {
                context.startActivity(viewIntent(Uri.parse(BilibiliSpace.WEB_URL), packageName = null))
            } catch (_: Throwable) {
            }
        }
    }

    private fun resolveIntent(): Intent? {
        val pm = context.packageManager
        val appUri = Uri.parse(BilibiliSpace.APP_URI)
        val webUri = Uri.parse(BilibiliSpace.WEB_URL)
        val installed = BilibiliSpace.APP_PACKAGES.filter { isInstalled(pm, it) }
        val preferred = BilibiliSpace.preferredPackage(installed)
        val candidates = buildList {
            if (preferred != null) {
                add(viewIntent(appUri, preferred))
                add(viewIntent(webUri, preferred))
            }
            add(viewIntent(appUri, packageName = null))
            add(viewIntent(webUri, packageName = null))
        }
        return candidates.firstOrNull { it.resolveActivity(pm) != null }
            ?: candidates.lastOrNull()
    }

    private fun viewIntent(uri: Uri, packageName: String?): Intent {
        return Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (packageName != null) {
                setPackage(packageName)
            }
        }
    }

    private fun isInstalled(pm: PackageManager, packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }
}
