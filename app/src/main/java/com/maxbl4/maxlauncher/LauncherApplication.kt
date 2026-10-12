package com.maxbl4.maxlauncher

import android.app.Application
import com.maxbl4.maxlauncher.apps.AppRepository
import com.maxbl4.maxlauncher.home.HomeRepository

class LauncherApplication : Application() {
    val appRepository by lazy { AppRepository(this) }
    val homeRepository by lazy { HomeRepository(this, appRepository) }
}
