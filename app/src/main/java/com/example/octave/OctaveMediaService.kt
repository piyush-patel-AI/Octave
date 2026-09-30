package com.example.octave

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class OctaveMediaService : Service() {
    private lateinit var mediaSession: MediaSessionCompat
    private val CHANNEL_ID = "octave_playback_channel"
    private val NOTIFICATION_ID = 1001

    private val BASE_URL = "https://mobile-client-v3.vercel.app/"

    private var currentTitle = "Octave"
    private var currentArtist = "Streaming Music"
    private var currentAlbum = "Octave"
    private var currentBitmap: Bitmap? = null
    private var isCurrentlyPlaying = false
    private var currentDurationMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val mediaButtonReceiver = ComponentName(this, androidx.media.session.MediaButtonReceiver::class.java)
        mediaSession = MediaSessionCompat(this, "OctaveSession", mediaButtonReceiver, null).apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            isActive = true
        }

        currentBitmap = createPlaceholderBitmap("Octave")
        
        // Initial state to let system know we are ready
        updatePlaybackState(false, 0.0, 0.0)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            android.util.Log.d("OctaveService", "onStartCommand: Intent action = ${intent.action}")
            androidx.media.session.MediaButtonReceiver.handleIntent(mediaSession, intent)
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelName = "Octave Media Playback"
            val channel = NotificationChannel(CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createPlaceholderBitmap(text: String): Bitmap {
        val size = 512
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            color = Color.parseColor("#12131C")
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)

        val textPaint = Paint().apply {
            color = Color.parseColor("#F59E0B")
            textSize = 64f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("🎵 Octave", size / 2f, size / 2f + 20f, textPaint)
        return bitmap
    }

    fun updateMetadata(title: String, artist: String, album: String, artUrl: String, durationSec: Double = 0.0) {
        val displayTitle = title.ifEmpty { "Octave" }
        val displayArtist = artist.ifEmpty { "Streaming Music" }
        val displayAlbum = album.ifEmpty { "Octave" }

        currentTitle = displayTitle
        currentArtist = displayArtist
        currentAlbum = displayAlbum
        if (durationSec > 0) {
            currentDurationMs = (durationSec * 1000).toLong()
        }

        thread {
            var bitmap: Bitmap? = null
            if (artUrl.isNotEmpty()) {
                val fullUrl = when {
                    artUrl.startsWith("http://") || artUrl.startsWith("https://") -> artUrl
                    artUrl.startsWith("/") -> "$BASE_URL$artUrl"
                    else -> "$BASE_URL/$artUrl"
                }

                if (fullUrl != null) {
                    try {
                        val url = URL(fullUrl)
                        val conn = (url.openConnection() as HttpURLConnection).apply {
                            instanceFollowRedirects = true
                            setRequestProperty("ngrok-skip-browser-warning", "69420")
                            setRequestProperty("User-Agent", "OctaveApp/1.0 (Android Native)")
                            connectTimeout = 10000
                            readTimeout = 10000
                            connect()
                        }
                        bitmap = BitmapFactory.decodeStream(conn.inputStream)
                        if (bitmap != null) {
                            android.util.Log.d("OctaveService", "Successfully loaded album art: $fullUrl")
                        } else {
                            android.util.Log.e("OctaveService", "Failed to decode bitmap from: $fullUrl")
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("OctaveService", "Error downloading art: ${e.message}")
                    }
                }
            }

            currentBitmap = bitmap ?: createPlaceholderBitmap(currentTitle)

            val metadataBuilder = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, currentTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, currentArtist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, currentAlbum)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, currentAlbum)

            if (currentDurationMs > 0) {
                metadataBuilder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, currentDurationMs)
            }

            currentBitmap?.let {
                metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
                metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, it)
                metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, it)
            }

            mediaSession.setMetadata(metadataBuilder.build())
            showNotification(currentTitle, currentArtist, currentBitmap, isCurrentlyPlaying)
        }
    }

    fun updatePlaybackState(isPlaying: Boolean, positionSec: Double, durationSec: Double) {
        isCurrentlyPlaying = isPlaying
        if (durationSec > 0) {
            currentDurationMs = (durationSec * 1000).toLong()
        }

        mediaSession.isActive = true

        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val speed = if (isPlaying) 1.0f else 0.0f
        val positionMs = (positionSec * 1000).toLong().coerceAtLeast(0L)

        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_FAST_FORWARD or
                PlaybackStateCompat.ACTION_REWIND
            )
            .setState(state, positionMs, speed)
            .build()

        mediaSession.setPlaybackState(playbackState)

        if (currentDurationMs > 0) {
            val metadataBuilder = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, currentTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, currentArtist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, currentAlbum)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, currentAlbum)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, currentDurationMs)

            currentBitmap?.let {
                metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
                metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, it)
                metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, it)
            }
            mediaSession.setMetadata(metadataBuilder.build())
        }

        showNotification(currentTitle, currentArtist, currentBitmap, isPlaying)
    }

    fun clearMetadata() {
        currentTitle = "Octave"
        currentArtist = "Streaming Music"
        currentBitmap = createPlaceholderBitmap("Octave")
        currentDurationMs = 0L
        mediaSession.setMetadata(null)
    }

    private fun showNotification(title: String, artist: String, bitmap: Bitmap?, isPlaying: Boolean) {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause, "Pause",
                androidx.media.session.MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PAUSE)
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play, "Play",
                androidx.media.session.MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY)
            )
        }

        val prevAction = NotificationCompat.Action(
            android.R.drawable.ic_media_previous, "Previous",
            androidx.media.session.MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
        )

        val nextAction = NotificationCompat.Action(
            android.R.drawable.ic_media_next, "Next",
            androidx.media.session.MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT)
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(artist)
            .setSubText("Octave")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setLargeIcon(bitmap ?: createPlaceholderBitmap(title))
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(prevAction)
            .addAction(playPauseAction)
            .addAction(nextAction)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setOngoing(isPlaying)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    fun getMediaSession(): MediaSessionCompat = mediaSession

    fun setCallback(callback: MediaSessionCompat.Callback) {
        mediaSession.setCallback(callback)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        try {
            if (::mediaSession.isInitialized) {
                mediaSession.isActive = false
                mediaSession.release()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
            // FORCE KILL the process to ensure WebView audio stops
            android.os.Process.killProcess(android.os.Process.myPid())
        } catch (e: Exception) {
            android.util.Log.e("OctaveService", "Error stopping service on task removed", e)
        }
    }

    override fun onDestroy() {
        if (::mediaSession.isInitialized) {
            mediaSession.isActive = false
            mediaSession.release()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = OctaveBinder()
    inner class OctaveBinder : Binder() { fun getService(): OctaveMediaService = this@OctaveMediaService }
}
