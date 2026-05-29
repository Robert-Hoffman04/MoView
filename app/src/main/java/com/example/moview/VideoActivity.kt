package com.example.moview

import android.app.Activity
import androidx.core.app.ActivityOptionsCompat
import androidx.core.util.Pair
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import java.util.Locale
import java.util.concurrent.TimeUnit


class VideoActivity : AppCompatActivity()
{

    private lateinit var film : Film
    private lateinit var show : Show

    private lateinit var episodeBackdrop: FrameLayout
    private lateinit var episodeSelector: Button

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var controlsContainer: RelativeLayout
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnRewind: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalTime: TextView

    private var controlsVisible = false
    private var episodesVisible = false

    private val updateProgressHandler = Handler(Looper.getMainLooper())
    private val progressUpdatePeriod = 500L // Update every second

    private val hideControlsHandler = Handler(Looper.getMainLooper())
    private val hideControlsDelayMs = 30_000L
    private val hideControlsRunnable = Runnable {
        if (controlsVisible) toggleControlsVisibility()
    }

    private val episodeActivityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val season = data?.getIntExtra("seasonIndex", -1) ?: -1
            val episode = data?.getIntExtra("episodeIndex", -1) ?: -1

            if (season != -1 && episode != -1) {
                changeEpisode(season, episode)
                player.play()
            }
        }
    }

    fun seasonMenu(view: View) {
        player.pause()
        toggleControlsVisibility()

        val myIntent = Intent(this, EpisodeActivity::class.java)
        myIntent.putExtra("videoKey", show.LocalTitle)

        val options = ActivityOptionsCompat.makeCustomAnimation(
            this,
            R.anim.slide_in_bottom,
            R.anim.slide_out_top
        )

        episodeActivityLauncher.launch(myIntent, options)
    }

    private fun loadVideo(uri : Uri)
    {
        val mediaItem = MediaItem.fromUri(uri)
        player.setMediaItem(mediaItem)
        player.prepare()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_video)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        episodeSelector = findViewById(R.id.episodeSelector)
        episodeBackdrop = findViewById(R.id.episodeSelectorBackdrop)

        playerView = findViewById(R.id.player_view)
        controlsContainer = findViewById(R.id.controls_container)
        btnPlayPause = findViewById(R.id.btn_play_pause)
        btnRewind = findViewById(R.id.btn_rewind)
        btnForward = findViewById(R.id.btn_forward)
        seekBar = findViewById(R.id.seek_bar)
        tvCurrentTime = findViewById(R.id.tv_current_time)
        tvTotalTime = findViewById(R.id.tv_total_time)

        val videoKey = intent.getStringExtra("videoKey") ?: return
        if (MainActivity.rawCatalog.Films.containsKey(videoKey)) {
            film = MainActivity.rawCatalog.Films[videoKey] ?: return
        } else if (MainActivity.rawCatalog.Shows.containsKey(videoKey)) {
            show = MainActivity.rawCatalog.Shows[videoKey] ?: return
        }

        setupPlayer()
        setupButtonListeners()
        setupFocusNavigation()
        setupSeekBar()

        // Handle initial focus
        playerView.requestFocus()
        player.play()

        // Open episode picker immediately if this is a show
        if (this::show.isInitialized) {
            seasonMenu(playerView)
        }
    }

    @OptIn(UnstableApi::class)
    private fun setupPlayer() {
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(30000) // 30 seconds connection timeout
            .setReadTimeoutMs(30000)    // 30 seconds read timeout
            .setAllowCrossProtocolRedirects(true)

        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
        playerView.player = player

        // Hide default controls to use our custom ones
        playerView.useController = false

        // Sample video setup - replace with your video sources
        val videoUri : Uri = if (this::film.isInitialized)
            Uri.parse(MainActivity.settings.ip + "/media/" + film.LocalFile)
        else if (this::show.isInitialized)
            Uri.parse(MainActivity.settings.ip + "/media/" + show.LocalFile)
        else
            return
        loadVideo(videoUri)

        // Listen for player ready to setup the seekbar max value
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    seekBar.max = player.duration.toInt()
                    tvTotalTime.text = formatTime(player.duration)
                    startProgressTracking()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayPauseButton(isPlaying)
            }
        })
    }

    private fun updatePlayPauseButton(isPlaying: Boolean) {
        btnPlayPause.setImageResource(
            if (isPlaying) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
    }

    private fun setupButtonListeners() {
        btnPlayPause.setOnClickListener {
            if (player.isPlaying) {
                player.pause()
            } else {
                player.play()
            }
        }

        btnRewind.setOnClickListener {
            player.seekTo(player.currentPosition - 10000) // Rewind 10 seconds
        }

        btnForward.setOnClickListener {
            player.seekTo(player.currentPosition + 10000) // Forward 10 seconds
        }
    }

    private fun setupSeekBar() {
        seekBar.setOnKeyListener { _, keyCode, _ ->
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val seekAmount = if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -5000L else 5000L
                    val newPosition = (player.currentPosition + seekAmount).coerceIn(0L, player.duration)

                    // Update both player and seekBar
                    player.seekTo(newPosition)
                    seekBar.progress = newPosition.toInt()
                    tvCurrentTime.text = formatTime(newPosition)
                    return@setOnKeyListener true
                }
                else -> false
            }
        }
    }

    private fun setupFocusNavigation() {
        // Set up directional navigation for remote control
        btnPlayPause.nextFocusRightId = R.id.btn_forward
        btnPlayPause.nextFocusLeftId = R.id.btn_rewind
        btnRewind.nextFocusRightId = R.id.btn_play_pause
        btnForward.nextFocusLeftId = R.id.btn_play_pause

        // Add focus navigation for the seekbar
        btnPlayPause.nextFocusUpId = R.id.seek_bar
        btnRewind.nextFocusUpId = R.id.seek_bar
        btnForward.nextFocusUpId = R.id.seek_bar
        seekBar.nextFocusDownId = R.id.btn_play_pause
    }

    private fun startProgressTracking() {
        updateProgressHandler.post(progressUpdateRunnable)
    }

    private val progressUpdateRunnable = object : Runnable {
        override fun run() {
            val currentPosition = player.currentPosition
            seekBar.progress = currentPosition.toInt()
            tvCurrentTime.text = formatTime(currentPosition)
            updateProgressHandler.postDelayed(this, progressUpdatePeriod)
        }
    }

    private fun formatTime(timeMs: Long): String
    {
        val hours   = TimeUnit.MILLISECONDS.toHours(timeMs)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(timeMs) -
                TimeUnit.HOURS.toMinutes(hours)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(timeMs) -
                TimeUnit.MINUTES.toSeconds(minutes) -
                TimeUnit.HOURS.toSeconds(hours)
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun resetHideTimer() {
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
        if (controlsVisible) {
            hideControlsHandler.postDelayed(hideControlsRunnable, hideControlsDelayMs)
        }
    }

    private fun toggleControlsVisibility()
    {
        if (controlsVisible) {
            controlsContainer.animate().alpha(0f).setDuration(300).withEndAction {
                controlsContainer.visibility = View.GONE
            }

            if (this::show.isInitialized) {
                episodeBackdrop.animate().alpha(0f).setDuration(300).withEndAction {
                    episodeBackdrop.visibility = View.GONE
                }
            }

        } else {
            controlsContainer.alpha = 0f
            controlsContainer.visibility = View.VISIBLE
            controlsContainer.animate().alpha(1f).setDuration(300)

            if (this::show.isInitialized)
            {
                episodeBackdrop.alpha = 0f
                episodeBackdrop.visibility = View.VISIBLE
                episodeBackdrop.animate().alpha(1f).setDuration(300)
            }

            // Always ensure something focusable has focus when controls appear
            if (!btnPlayPause.hasFocus() && !btnRewind.hasFocus() && !btnForward.hasFocus() && !seekBar.hasFocus()) {
                seekBar.requestFocus()
            }
        }
        controlsVisible = !controlsVisible
        resetHideTimer()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        resetHideTimer()

        if (episodesVisible)
        {
            return super.onKeyDown(keyCode, event)
        }

        // Add handling for left/right keys when seekbar has focus
        if (seekBar.hasFocus() && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT))
            return true

        if (seekBar.hasFocus() && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            toggleControlsVisibility()
            return true
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (!controlsVisible) {
                    toggleControlsVisibility()
                    episodeSelector.requestFocus()
                    true
                } else {
                    super.onKeyDown(keyCode, event)
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (!controlsVisible) {
                    toggleControlsVisibility()
                    seekBar.requestFocus()
                    true
                } else {
                    super.onKeyDown(keyCode, event)
                }
            }
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (player.isPlaying) {
                    player.pause()
                } else {
                    player.play()
                }
                if (!controlsVisible) toggleControlsVisibility()
                btnPlayPause.requestFocus()
                true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                player.seekTo(player.currentPosition - 10000)
                if (!controlsVisible) toggleControlsVisibility()
                btnRewind.requestFocus()
                true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                player.seekTo(player.currentPosition + 10000)
                if (!controlsVisible) toggleControlsVisibility()
                btnForward.requestFocus()
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (controlsVisible) {
                    toggleControlsVisibility()
                    true
                } else {
                    super.onKeyDown(keyCode, event)
                }
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onPause() {
        super.onPause()
        player.pause()
        updateProgressHandler.removeCallbacks(progressUpdateRunnable)
    }

    override fun onResume() {
        super.onResume()
        if (player.playbackState == Player.STATE_READY) {
            startProgressTracking()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        updateProgressHandler.removeCallbacks(progressUpdateRunnable)
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
        player.release()
    }

    fun changeEpisode(season : Int, episode : Int)
    {
        loadVideo(Uri.parse(MainActivity.settings.ip + "/media/" + show.Episodes[season][episode].LocalFile))
    }

}