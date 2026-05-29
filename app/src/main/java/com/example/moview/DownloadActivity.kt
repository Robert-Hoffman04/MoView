package com.example.moview

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.moview.MainActivity.Companion.settings
import com.example.moview.databinding.ActivityDownloadBinding
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class DownloadActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDownloadBinding

    private val handler = Handler(Looper.getMainLooper())
    private val refreshIntervalMs = 5000L

    /**
     * Change this to wherever your Flask server is running.
     * For Android emulator use 10.0.2.2.
     * For a physical device use your server's LAN IP.
     */
    val baseUrl = if (settings.ip.startsWith("http")) {
        settings.ip
    } else {
        "http://${settings.ip}"
    }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            fetchDownloadStatus()
            handler.postDelayed(this, refreshIntervalMs)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityDownloadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initial UI state
        showNoCurrentDownload()
        showEmptyQueue()
    }

    override fun onResume() {
        super.onResume()
        refreshRunnable.run()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshRunnable)
    }

    // -------------------------------------------------------------------------
    // Networking
    // -------------------------------------------------------------------------

    private fun fetchDownloadStatus() {
        thread {
            try {
                val url = URL("$baseUrl/api/download/status")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw Exception("HTTP $responseCode")
                }

                val json = connection.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(json)

                runOnUiThread {
                    updateCurrent(root.optJSONObject("current"))
                    updateQueue(root)
                }

                connection.disconnect()
            } catch (e: Exception) {
                runOnUiThread {
                    binding.textProgress.text = "Unable to load download status."
                    binding.currentStatus.text = e.message ?: "Network error"
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Current Download
    // -------------------------------------------------------------------------

    private fun updateCurrent(current: JSONObject?) {
        if (current == null) {
            showNoCurrentDownload()
            return
        }

        val title = current.optString("title", "Untitled")
        val status = current.optString("status", "unknown")
        val progress = current.optDouble("progress", 0.0)
        val etaSeconds =
            if (current.isNull("eta_seconds")) null else current.optDouble("eta_seconds")
        val localPath = current.optString("local_path", "")

        // Split the overall 0.0 - 1.0 progress into two bars
        val downloadProgress: Double
        val encodeProgress: Double

        when (status) {
            "downloading" -> {
                downloadProgress = (progress * 2.0).coerceIn(0.0, 1.0)
                encodeProgress = 0.0
            }

            "converting" -> {
                downloadProgress = 1.0
                encodeProgress = ((progress - 0.5) * 2.0).coerceIn(0.0, 1.0)
            }

            "finished" -> {
                downloadProgress = 1.0
                encodeProgress = 1.0
            }

            else -> {
                downloadProgress = progress.coerceIn(0.0, 1.0)
                encodeProgress = 0.0
            }
        }

        binding.header.text = title
        binding.progressBar.progress = (downloadProgress * 100).toInt()
        binding.encodeProgressBar.progress = (encodeProgress * 100).toInt()

        binding.textProgress.text =
            "Overall ${toPercent(progress)} • ETA ${formatEta(etaSeconds)}"

        binding.currentStatus.text =
            buildString {
                append(status.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
                })

                if (localPath.isNotBlank()) {
                    append("\n")
                    append(localPath)
                }

                if (current.has("error")) {
                    append("\n")
                    append(current.optString("error"))
                }
            }
    }

    private fun showNoCurrentDownload() {
        binding.header.text = "No active download."
        binding.textProgress.text = "Waiting for jobs..."
        binding.progressBar.progress = 0
        binding.encodeProgressBar.progress = 0
        binding.currentStatus.text = "Ready"
    }

    // -------------------------------------------------------------------------
    // Queue
    // -------------------------------------------------------------------------

    private fun updateQueue(root: JSONObject) {
        val queue = root.optJSONArray("queue")
        val queueLength = root.optInt(
            "queue_length",
            queue?.length() ?: 0
        )

        binding.queueCount.text =
            if (queueLength == 1) "1 pending" else "$queueLength pending"

        val container = binding.outputBox as LinearLayout
        container.removeAllViews()

        if (queue == null || queue.length() == 0) {
            showEmptyQueue()
            return
        }

        for (i in 0 until queue.length()) {
            val item = queue.getJSONObject(i)

            val title = item.optString("title", "Untitled")
            val localPath = item.optString("local_path", "")

            val view = createQueueItemView(i + 1, title, localPath)
            container.addView(view)
        }
    }

    private fun showEmptyQueue() {
        binding.queueCount.text = "0 pending"

        val container = binding.outputBox as LinearLayout
        container.removeAllViews()

        val textView = TextView(this).apply {
            text = "Queue is empty."
            textSize = 16f
            alpha = 0.6f
            setTextColor(getColor(android.R.color.white))
        }

        container.addView(textView)
    }

    private fun createQueueItemView(
        index: Int,
        title: String,
        localPath: String
    ): View {
        val density = resources.displayMetrics.density

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            val marginBottom = (12 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = marginBottom
            }

            val padding = (16 * density).toInt()
            setPadding(padding, padding, padding, padding)

            // Reuse your queue item background drawable if you have one.
            // Otherwise create a simple rounded drawable.
            setBackgroundResource(R.drawable.terminal_background)

            addView(TextView(context).apply {
                text = "$index. $title"
                textSize = 18f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(getColor(android.R.color.white))
            })

            if (localPath.isNotBlank()) {
                addView(TextView(context).apply {
                    text = localPath
                    textSize = 14f
                    alpha = 0.7f
                    setTextColor(getColor(android.R.color.white))

                    val topMargin = (6 * density).toInt()
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        this.topMargin = topMargin
                    }
                })
            }
        }
    }

    // -------------------------------------------------------------------------
    // Formatting Helpers
    // -------------------------------------------------------------------------

    private fun toPercent(value: Double): String {
        val clamped = value.coerceIn(0.0, 1.0)
        return "${(clamped * 100.0).toInt()}%"
    }

    private fun formatEta(seconds: Double?): String {
        if (seconds == null) return "—"

        val total = seconds.toInt().coerceAtLeast(0)
        val minutes = total / 60
        val remaining = total % 60

        return String.format(Locale.US, "%d:%02d", minutes, remaining)
    }
}