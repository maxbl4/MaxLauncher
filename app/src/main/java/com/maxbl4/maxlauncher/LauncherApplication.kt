package com.maxbl4.maxlauncher

import android.app.Application
import com.maxbl4.maxlauncher.apps.AppRepository

class LauncherApplication : Application() {
    val appRepository by lazy { AppRepository(this) }
}
