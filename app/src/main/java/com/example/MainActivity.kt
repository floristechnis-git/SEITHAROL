package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.example.ui.CascadeApp
import com.example.ui.CascadeViewModel
import com.example.ui.CascadeViewModelFactory
import com.example.ui.splash.SplashScreen
import com.example.ui.theme.CascadeTheme

class MainActivity : ComponentActivity() {

    private val viewModel: CascadeViewModel by viewModels {
        val app = application as CascadeApplication
        CascadeViewModelFactory(
            app.musicRepository,
            app.downloadRepository,
            app.resolverEngine,
            app.audioPlayerManager
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
        setContent {
            val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
            val userDarkSetting by viewModel.isDarkMode.collectAsState()
            val effectiveDark = userDarkSetting ?: systemDark
            var isSplashFinished by remember { mutableStateOf(false) }

            CascadeTheme(darkTheme = effectiveDark) {
                if (!isSplashFinished) {
                    SplashScreen(onSplashFinished = { isSplashFinished = true })
                } else {
                    CascadeApp(viewModel = viewModel)
                }
            }
        }
    }
}
