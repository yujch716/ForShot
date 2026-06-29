package com.photo.thirds

import android.app.Application
import android.content.Context

class ThirdsApplication : Application() {
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        com.cySdkyc.clx.Helper.install(this)
    }
}
