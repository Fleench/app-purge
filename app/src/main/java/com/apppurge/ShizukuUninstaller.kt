package com.apppurge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import rikka.shizuku.Shizuku

object ShizukuUninstaller {
    const val REQUEST_CODE = 6204

    fun isBinderAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    fun hasPermission(): Boolean {
        return isBinderAvailable() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun requestPermissionIfNeeded(): Boolean {
        if (!isBinderAvailable() || hasPermission()) return false
        Shizuku.requestPermission(REQUEST_CODE)
        return true
    }

    fun uninstallWithShizuku(packageName: String): Boolean {
        if (!hasPermission()) return false
        return try {
            val command = arrayOf("pm", "uninstall", "--user", "0", packageName)
            val process = Shizuku.newProcess(command, null, null)
            process.waitFor() == 0
        } catch (_: Throwable) {
            false
        }
    }

    fun launchFallbackUninstall(context: Context, packageName: String) {
        val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE).apply {
            data = Uri.parse("package:$packageName")
            putExtra(Intent.EXTRA_RETURN_RESULT, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            intent.putExtra(Intent.EXTRA_PACKAGE_NAME, packageName)
        }
        context.startActivity(intent)
    }
}
