package com.infinstall.app

import android.app.Application
import android.util.Log
import org.conscrypt.Conscrypt
import java.security.Security

class InfinstallApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Wireless debugging pairing needs TLS 1.3 (Conscrypt)
        try {
            val provider = Conscrypt.newProvider()
            // Prefer Conscrypt for TLSv1.3 (libadb looks up OpenSSLProvider)
            Security.insertProviderAt(provider, 1)
            Log.i("Infinstall", "Conscrypt installed: ${provider.name}")
        } catch (t: Throwable) {
            Log.e("Infinstall", "Conscrypt install failed", t)
        }
    }
}
