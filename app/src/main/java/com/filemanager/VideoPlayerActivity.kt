package com.filemanager

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class VideoPlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATH = "media_path"
    }

    private lateinit var videoView: VideoView
    private lateinit var controls: View
    private lateinit var btnPlayPause: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var txtTime: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var isPrepared = false

    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (isPrepared && videoView.isPlaying) {
                    videoView.pause()
                    btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {}
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_player)

        val path = intent.getStringExtra(EXTRA_PATH) ?: run {
            finish()
            return
        }

        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(this, "File non trovato", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        videoView = findViewById(R.id.videoView)
        controls = findViewById(R.id.controls)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        seekBar = findViewById(R.id.seekBar)
        txtTime = findViewById(R.id.txtTime)

        videoView.setVideoURI(Uri.fromFile(file))

        videoView.setOnPreparedListener { mp ->
            isPrepared = true
            mp.isLooping = false
            seekBar.max = videoView.duration
            updateTimeLabel()
            requestAudioFocus()
            videoView.start()
            btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
            startProgressUpdater()
        }

        videoView.setOnCompletionListener {
            btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
            seekBar.progress = seekBar.max
        }

        videoView.setOnErrorListener { _, what, _ ->
            Toast.makeText(this, "Errore riproduzione ($what)", Toast.LENGTH_LONG).show()
            true
        }

        btnPlayPause.setOnClickListener {
            if (!isPrepared) return@setOnClickListener
            if (videoView.isPlaying) {
                videoView.pause()
                btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
            } else {
                requestAudioFocus()
                videoView.start()
                btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
            }
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && isPrepared) {
                    videoView.seekTo(progress)
                    updateTimeLabel()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        videoView.setOnClickListener { toggleControls() }
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(audioFocusListener)
                .build()
            audioFocusRequest?.let { audioManager.requestAudioFocus(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusListener)
        }
    }

    private fun toggleControls() {
        controls.visibility = if (controls.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun startProgressUpdater() {
        handler.post(object : Runnable {
            override fun run() {
                if (isPrepared && videoView.isPlaying) {
                    seekBar.progress = videoView.currentPosition
                    updateTimeLabel()
                }
                handler.postDelayed(this, 500)
            }
        })
    }

    private fun updateTimeLabel() {
        val cur = videoView.currentPosition
        val tot = videoView.duration
        if (tot > 0) {
            txtTime.text = "${formatTime(cur)} / ${formatTime(tot)}"
        }
    }

    private fun formatTime(ms: Int): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format("%02d:%02d", min, sec)
    }

    override fun onPause() {
        super.onPause()
        if (isPrepared && videoView.isPlaying) {
            videoView.pause()
            btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)

        try {
            if (videoView.isPlaying) {
                videoView.stopPlayback()
            }
            videoView.suspend()
        } catch (_: Exception) {}

        abandonAudioFocus()
    }
}
