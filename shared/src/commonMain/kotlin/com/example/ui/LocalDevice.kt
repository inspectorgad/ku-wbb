package com.example.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.example.platform.Device

/**
 * The phone the screens are running on, provided once at the top of the tree
 * so no screen has to reach for Android (or iPhone) itself. Only Ask needs
 * it, for the API key and the chosen model; everything else gets what it
 * needs from the view model.
 *
 * The volleyball app wraps this in a wider `Phone` interface, because its
 * screens also ask the phone for match alerts and for the share sheet. This
 * app has neither, so what is left is the device.
 */
val LocalDevice = staticCompositionLocalOf<Device> {
    error("No Device provided: wrap the screens in CompositionLocalProvider(LocalDevice provides ...)")
}
