package com.infinstall.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.infinstall.app.ui.InfinstallRoot
import com.infinstall.app.ui.theme.InfinstallTheme
import com.infinstall.app.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncoming(intent)
        setContent {
            InfinstallTheme {
                Surface(Modifier.fillMaxSize()) {
                    InfinstallRoot(vm)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
    }

    private fun handleIncoming(intent: Intent?) {
        if (intent == null) return
        val uris = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> {
                intent.getParcelableExtraCompat<Uri>(Intent.EXTRA_STREAM)?.let { uris.add(it) }
                intent.data?.let { uris.add(it) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                intent.getParcelableArrayListExtraCompat<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
            }
            Intent.ACTION_VIEW -> {
                intent.data?.let { uris.add(it) }
            }
        }
        if (uris.isNotEmpty()) {
            vm.installFromUris(uris.distinct())
        }
    }
}

@Suppress("DEPRECATION")
private inline fun <reified T> Intent.getParcelableExtraCompat(key: String): T? {
    return if (android.os.Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(key, T::class.java)
    } else {
        getParcelableExtra(key) as? T
    }
}

@Suppress("DEPRECATION")
private inline fun <reified T> Intent.getParcelableArrayListExtraCompat(key: String): ArrayList<T>? {
    return if (android.os.Build.VERSION.SDK_INT >= 33) {
        getParcelableArrayListExtra(key, T::class.java)
    } else {
        @Suppress("UNCHECKED_CAST")
        getParcelableArrayListExtra(key) as? ArrayList<T>
    }
}
