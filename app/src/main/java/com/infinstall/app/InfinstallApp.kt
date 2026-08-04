package com.infinstall.app

import android.app.Application
import org.conscrypt.Conscrypt
import java.security.Security

class InfinstallApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // TLS 1.3 / wireless debugging pairing needs Conscrypt on many devices
        try {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
        } catch (_: Throwable) {
            // already installed or unavailable
        }
    }
}
