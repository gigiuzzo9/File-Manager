package com.filemanager

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
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
    private lateinit var imgBackground: ImageView
    private lateinit var controls: View
    private lateinit var btnPlayPause: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var txtTime: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var isAudio = false
    private var isPrepared = false

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

        isAudio = isAudioFile(file.name)

        videoView = findViewById(R.id.videoView)
        imgBackground = findViewById(R.id.imgBackground)
        controls = findViewById(R.id.controls)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        seekBar = findViewById(R.id.seekBar)
        txtTime = findViewById(R.id.txtTime)

        // Audio → mostra sfondo; Video → nascondi sfondo
        if (isAudio) {
            imgBackground.visibility = View.VISIBLE
            videoView.setBackgroundColor(Color.TRANSPARENT)
        } else {
            imgBackground.visibility = View.GONE
        }

        videoView.setVideoURI(Uri.fromFile(file))

        videoView.setOnPreparedListener { mp ->
            isPrepared = true
            mp.isLooping = false
            seekBar.max = videoView.duration
            updateTimeLabel()
            videoView.start()
            updatePlayPauseIcon()
            startProgressUpdater()
        }

        videoView.setOnCompletionListener {
            btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
            seekBar.progress = seekBar.max
        }

        videoView.setOnErrorListener { _, what, extra ->
            Toast.makeText(this, "Errore riproduzione ($what)", Toast.LENGTH_LONG).show()
            true
        }

        btnPlayPause.setOnClickListener {
            if (!isPrepared) return@setOnClickListener
            if (videoView.isPlaying) {
                videoView.pause()
                btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
            } else {
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

        // Tap sullo schermo → mostra/nascondi controlli
        videoView.setOnClickListener { toggleControls() }
        imgBackground.setOnClickListener { toggleControls() }
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

    private fun updatePlayPauseIcon() {
        btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
    }

    private fun formatTime(ms: Int): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format("%02d:%02d", min, sec)
    }

    private fun isAudioFile(name: String): Boolean {
        val l = name.lowercase()
        return l.endsWith(".mp3") || l.endsWith(".wav") || l.endsWith(".ogg") ||
               l.endsWith(".flac") || l.endsWith(".m4a") || l.endsWith(".aac")
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
    }
}
