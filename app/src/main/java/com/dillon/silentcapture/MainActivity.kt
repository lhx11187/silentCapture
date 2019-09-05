package com.dillon.silentcapture

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import com.blankj.utilcode.constant.PermissionConstants
import com.blankj.utilcode.util.LogUtils
import com.blankj.utilcode.util.PermissionUtils
import com.blankj.utilcode.util.ToastUtils
import kotlinx.android.synthetic.main.activity_main.*

class MainActivity : AppCompatActivity() {
    // 测试结果
    // 三星9.0系统, 8.0系统，华为7.0系统，平稳运行
    // vivo与oppo内置安全，测试8.0系统都失败，其他未知

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tv_start_capture.setOnClickListener {
            checkPermissions()
        }
        tv_stop_capture.setOnClickListener { stopPicture() }
    }

    private fun checkPermissions() {
        if (!NotificationSetUtil.isNotificationEnabled(this)) {
            if (PermissionUtils.isGranted(Manifest.permission.CAMERA)) {
                if (PermissionUtils.isGranted(Manifest.permission.SYSTEM_ALERT_WINDOW)) {
                    startPicture()
                } else {
                    requestDrawOverlays()
                }
            } else {
                requestCamera()
            }
        } else {
            NotificationSetUtil.goAppNotificationSet(this)
        }
    }

    private fun requestCamera() {
        PermissionUtils.permission(PermissionConstants.CAMERA)
            .callback(object : PermissionUtils.FullCallback {
                override fun onGranted(permissionsGranted: List<String>) {
                    if (PermissionUtils.isGranted(Manifest.permission.SYSTEM_ALERT_WINDOW)) {
                        startPicture()
                    } else {
                        requestDrawOverlays()
                    }
                }

                override fun onDenied(
                    permissionsDeniedForever: List<String>,
                    permissionsDenied: List<String>
                ) {
                    LogUtils.d(permissionsDeniedForever, permissionsDenied)
                    if (permissionsDeniedForever.isNotEmpty()) {
                        //点了不再询问，自行到对应APP开启权限
                        return
                    }
                    requestCamera()
                }
            })
            .request()
    }

    private fun requestDrawOverlays() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            //Demo设置一定要用户授权，实际可以先弹窗说明，再进行跳转
            PermissionUtils.requestDrawOverlays(object : PermissionUtils.SimpleCallback {
                override fun onGranted() {
                    startPicture()
                }

                override fun onDenied() {
                    //拒绝了，继续请求
                    requestDrawOverlays()
                }
            })
        }
    }

    private fun startPicture() {
        val intent = Intent(this, SilentPictureService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)

        }
        ToastUtils.showLong("开始拍照! 保存目录：Android.data.com.dillon.silentcapture")
    }

    private fun stopPicture() {
        val intent = Intent(this, SilentPictureService::class.java)
        stopService(intent)
    }
}
