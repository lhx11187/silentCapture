package com.dillon.silentcapture

import android.app.Application
import com.blankj.utilcode.util.Utils

/**
 * Created by dillon on 2017/6/11.
 */

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        Utils.init(this)

    }

}