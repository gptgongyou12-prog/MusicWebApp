package com.musicapp.webview

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
import androidx.media.app.NotificationCompat.MediaStyle
import java.net.URL

class MusicService : Service() {

    inner class LocalBinder : Binder() {
        fun getService() = this@MusicService
    }

    interface PlaybackController {
        fun onPlay()
        fun onPause()
        fun onSkipNext()
        fun onSkipPrevious()
    }

    private val binder = LocalBinder()
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var notificationManager: NotificationManager
    private var customIcon: Bitmap? = null
    private var playbackController: PlaybackController? = null
    private var isPlaying = false

    companion object {
        const val CHANNEL_ID = "music_playback"
        const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        setupMediaSession()
        startForeground(NOTIFICATION_ID, buildNotification("재생 준비 중", "", "", false))
    }

    fun setPlaybackController(controller: PlaybackController) {
        playbackController = controller
    }

    fun setCustomIcon(bmp: Bitmap) {
        customIcon = bmp
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "음악 재생", NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "MusicService").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    isPlaying = true
                    playbackController?.onPlay()
                    updatePlaybackState(true)
                }
                override fun onPause() {
                    isPlaying = false
                    playbackController?.onPause()
                    updatePlaybackState(false)
                }
                override fun onSkipToNext() { playbackController?.onSkipNext() }
                override fun onSkipToPrevious() { playbackController?.onSkipPrevious() }
            })
            setPlaybackState(buildPlaybackState(false))
            isActive = true
        }
    }

    private fun buildPlaybackState(playing: Boolean) = PlaybackStateCompat.Builder()
        .setActions(
            PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        )
        .setState(
            if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
            PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f
        )
        .build()

    private fun updatePlaybackState(playing: Boolean) {
        mediaSession.setPlaybackState(buildPlaybackState(playing))
    }

    fun updateMetadata(title: String, artist: String, artworkUrl: String, playing: Boolean) {
        isPlaying = playing
        updatePlaybackState(playing)

        val metaBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title.ifEmpty { "재생 중" })
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)

        // 앨범아트 비동기 로드
        if (artworkUrl.isNotEmpty()) {
            Thread {
                try {
                    val bmp = BitmapFactory.decodeStream(URL(artworkUrl).openStream())
                    bmp?.let {
                        metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
                        mediaSession.setMetadata(metaBuilder.build())
                        notificationManager.notify(NOTIFICATION_ID, buildNotification(title, artist, artworkUrl, playing, it))
                    }
                } catch (e: Exception) { /* 앨범아트 로드 실패시 무시 */ }
            }.start()
        }

        mediaSession.setMetadata(metaBuilder.build())
        notificationManager.notify(NOTIFICATION_ID, buildNotification(title, artist, artworkUrl, playing))
    }

    private fun buildNotification(
        title: String, artist: String, artworkUrl: String,
        playing: Boolean, artBitmap: Bitmap? = null
    ): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val largeIcon = artBitmap ?: customIcon ?: makeDefaultIcon()

        // 이전곡 액션
        val prevIntent = PendingIntent.getBroadcast(this, 1,
            Intent("MUSIC_PREV"), PendingIntent.FLAG_IMMUTABLE)
        // 재생/정지 액션
        val playPauseIntent = PendingIntent.getBroadcast(this, 2,
            Intent("MUSIC_PLAYPAUSE"), PendingIntent.FLAG_IMMUTABLE)
        // 다음곡 액션
        val nextIntent = PendingIntent.getBroadcast(this, 3,
            Intent("MUSIC_NEXT"), PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title.ifEmpty { "재생 중" })
            .setContentText(artist)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setLargeIcon(largeIcon)
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setSilent(true)
            .addAction(android.R.drawable.ic_media_previous, "이전", prevIntent)
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "일시정지" else "재생",
                playPauseIntent
            )
            .addAction(android.R.drawable.ic_media_next, "다음", nextIntent)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun makeDefaultIcon(): Bitmap {
        val size = 128
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#7c6aff") }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        return bmp
    }

    override fun onBind(intent: Intent): IBinder = binder
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        mediaSession.release()
        super.onDestroy()
    }
}
