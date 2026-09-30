package com.example.octave

import android.util.Log
import android.webkit.JavascriptInterface

class AndroidMediaBridge(private val serviceProvider: () -> OctaveMediaService?) {
    private val TAG = "OctaveBridge"

    @JavascriptInterface
    fun updateMetadata(title: String, artist: String, album: String, artUrl: String, durationSec: Double = 0.0) {
        Log.d(TAG, "Metadata received: Title=$title, Artist=$artist, Art=$artUrl, Dur=$durationSec")
        serviceProvider.invoke()?.updateMetadata(title, artist, album, artUrl, durationSec)
    }

    @JavascriptInterface
    fun updatePlaybackState(isPlaying: Boolean, positionSec: Double, durationSec: Double) {
        Log.d(TAG, "PlaybackState received: isPlaying=$isPlaying, pos=$positionSec, dur=$durationSec")
        serviceProvider.invoke()?.updatePlaybackState(isPlaying, positionSec, durationSec)
    }

    @JavascriptInterface
    fun logDebug(message: String) {
        Log.d(TAG, "JS DEBUG: $message")
    }

    @JavascriptInterface
    fun clearMetadata() {
        Log.d(TAG, "Metadata cleared")
        serviceProvider.invoke()?.clearMetadata()
    }
}
