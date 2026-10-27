package com.example

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import com.example.data.JayhawksDatabase
import com.example.data.createDatabase
import com.example.platform.Device
import com.example.platform.IosDevice
import com.example.ui.JayhawksApp
import com.example.ui.LocalDevice
import com.example.ui.theme.MyApplicationTheme
import platform.UIKit.UIViewController

// The iPhone app's way in. Swift calls MainViewController() (as
// MainViewControllerKt.MainViewController(), after this file's name) and shows
// what it returns; everything on screen from there is the shared code, the
// same screens the Android app draws.
//
// This is the iPhone's MainActivity, and it does the same three things: build
// the device, build the view model, provide the one and pass the other.

private val iosDevice: Device by lazy { IosDevice() }
private val database: JayhawksDatabase by lazy { createDatabase() }

fun MainViewController(): UIViewController = ComposeUIViewController {
    // One view model for the life of the app, as Android's ViewModel gives.
    val viewModel = remember { JayhawksViewModel(database.dao(), iosDevice) }
    CompositionLocalProvider(LocalDevice provides iosDevice) {
        MyApplicationTheme {
            JayhawksApp(viewModel)
        }
    }
}
