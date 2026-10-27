package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.JayhawksDatabase
import com.example.data.get
import com.example.platform.AndroidDevice
import com.example.ui.JayhawksApp
import com.example.ui.LocalDevice
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Built once here, not inside setContent, which would make a new one
        // on every recomposition. The screens read it from LocalDevice.
        val device = AndroidDevice(applicationContext)
        setContent {
            // Kept across rotation like any ViewModel; built here because it
            // takes the database and storage rather than finding them.
            val viewModel = viewModel {
                JayhawksViewModel(JayhawksDatabase.get(applicationContext).dao(), device)
            }
            CompositionLocalProvider(LocalDevice provides device) {
                MyApplicationTheme {
                    JayhawksApp(viewModel)
                }
            }
        }
    }
}
