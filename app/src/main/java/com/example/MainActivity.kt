package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.example.ui.CascadeApp
import com.example.ui.CascadeViewModel
import com.example.ui.CascadeViewModelFactory
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
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val isDark by viewModel.isDarkMode.collectAsState()
            CascadeTheme(darkTheme = isDark) {
                CascadeApp(viewModel = viewModel)
            }
        }
    }
}
