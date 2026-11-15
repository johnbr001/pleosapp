package com.ys.pleosapp.shared

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import com.ys.pleosapp.shared.util.CastLog

class MyCarAppService : CarAppService() {
    override fun onCreate() {
        super.onCreate()
        CastLog.init(this)
        CastLog.installCrashHandler()
    }

    override fun createHostValidator(): HostValidator {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(): Session {
        return MyCarAppSession()
    }
}