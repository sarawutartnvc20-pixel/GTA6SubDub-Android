package com.gta6subdub.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

object PsRemotePlayHelper {
    private val candidatePackages = listOf(
        "com.playstation.remoteplay", // Official PS Remote Play on Google Play
        "com.scee.psxandroid"         // Fallback PlayStation App
    )

    fun findInstalledPackage(context: Context): String? {
        val pm = context.packageManager
        for (pkg in candidatePackages) {
            if (isPackageInstalled(pm, pkg)) {
                return pkg
            }
        }
        return null
    }

    fun isInstalled(context: Context): Boolean = findInstalledPackage(context) != null

    fun createLaunchIntent(context: Context): Intent? {
        val pkg = findInstalledPackage(context) ?: return null
        return context.packageManager.getLaunchIntentForPackage(pkg)
    }

    private fun isPackageInstalled(pm: PackageManager, packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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
