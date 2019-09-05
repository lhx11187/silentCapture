package com.dillon.silentcapture

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

import androidx.core.app.NotificationManagerCompat


object NotificationSetUtil {

    //判断该app是否打开了通知
    fun isNotificationEnabled(context: Context): Boolean {
        val notificationManagerCompat = NotificationManagerCompat.from(context)
        return notificationManagerCompat.areNotificationsEnabled()

    }

    //打开手机通知设置页面
    fun goAppNotificationSet(context: Context) {
        val intent = Intent()
        when {
            Build.VERSION.SDK_INT >= 26 -> {
                // android 8.0引导
                intent.action = "android.settings.APP_NOTIFICATION_SETTINGS"
                intent.putExtra("android.provider.extra.APP_PACKAGE", context.packageName)
            }
            Build.VERSION.SDK_INT >= 21 -> {
                // android 5.0-7.0
                intent.action = "android.settings.APP_NOTIFICATION_SETTINGS"
                intent.putExtra("app_package", context.packageName)
                intent.putExtra("app_uid", context.applicationInfo.uid)
            }
            else -> {
                // 其他
                intent.action = "android.settings.APPLICATION_DETAILS_SETTINGS"
                intent.data = Uri.fromParts("package", context.packageName, null)
            }
        }
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    }
}
