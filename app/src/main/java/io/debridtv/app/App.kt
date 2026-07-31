package io.debridtv.app

import android.app.Application
import io.debridtv.app.di.ServiceLocator

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }
}
