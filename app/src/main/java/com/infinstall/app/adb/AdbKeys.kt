package com.infinstall.app.adb

import android.content.Context
import dadb.AdbKeyPair
import java.io.File

object AdbKeys {
    fun loadOrCreate(context: Context): AdbKeyPair {
        val dir = File(context.applicationContext.filesDir, "tv_keys").apply { mkdirs() }
        val privateKey = File(dir, "key")
        val publicKey = File(dir, "key.pub")
        if (!privateKey.exists() || !publicKey.exists()) {
            AdbKeyPair.generate(privateKey, publicKey)
        }
        return AdbKeyPair.read(privateKey, publicKey)
    }
}
