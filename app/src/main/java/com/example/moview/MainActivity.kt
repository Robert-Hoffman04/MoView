package com.example.moview

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityOptionsCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.nes.emulator.ui.EmulatorActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL


class MainActivity : AppCompatActivity(), ItemAdapter.ItemAdapterListener {

    companion object {
        lateinit var rawCatalog: FinalCatalog
        lateinit var jointCatalog: Map<String, Media>
        lateinit var settings: Settings
    }

    lateinit var searchButton: ImageButton
    lateinit var homeButton: ImageButton
    lateinit var categoryButton: ImageButton
    lateinit var downloadButton: ImageButton
    lateinit var marioButton: ImageButton
    lateinit var settingsButton: ImageButton

    lateinit var filmList: IncrementalRecyclerView
    lateinit var showList: IncrementalRecyclerView
    lateinit var gameList: IncrementalRecyclerView

    // Counter for the secret settings easter egg
    private var downloadDownPressCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        settings = loadSettings(this, "settings.json")
            ?: Settings(ip = "0.0.0.0", recent = emptyList())

        searchButton   = findViewById(R.id.buttonSearch)
        homeButton     = findViewById(R.id.buttonHome)
        categoryButton = findViewById(R.id.buttonCategory)
        downloadButton = findViewById(R.id.buttonDownload)
        marioButton    = findViewById(R.id.buttonMario)
        settingsButton = findViewById(R.id.buttonSettings)
        filmList       = findViewById(R.id.home_Section1)
        showList       = findViewById(R.id.home_Section2)
        gameList       = findViewById(R.id.home_Section3)

        filmList.blockUp    = true
        gameList.blockDown  = true

        setupSidebarNavigation()
        setupRecyclerNavigation()

        getAll()
    }

    private fun setupSidebarNavigation() {
        for (button in listOf(
            searchButton,
            homeButton,
            categoryButton,
            downloadButton,
            marioButton,
            settingsButton
        )) {
            button.setOnFocusChangeListener { _, hasFocus ->
                button.setBackgroundColor(
                    Color.parseColor(if (hasFocus) "#404757" else "#2f3441")
                )
            }
        }

        searchButton.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    homeButton.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP -> true
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    filmList.requestFocus()
                    true
                }
                else -> false
            }
        }

        homeButton.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    searchButton.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    categoryButton.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    filmList.requestFocus()
                    true
                }
                else -> false
            }
        }

        categoryButton.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    homeButton.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    downloadDownPressCount = 0
                    downloadButton.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    filmList.requestFocus()
                    true
                }
                else -> false
            }
        }

        // Downloads -> Mario
        downloadButton.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    downloadDownPressCount = 0
                    marioButton.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    downloadDownPressCount = 0
                    categoryButton.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    downloadDownPressCount = 0
                    filmList.requestFocus()
                    true
                }
                else -> false
            }
        }

        // Mario contains the hidden 10x DOWN trigger.
        // Mario -> Settings
        marioButton.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    downloadDownPressCount++
                    if (downloadDownPressCount >= 10) {
                        downloadDownPressCount = 0
                        settings(marioButton)
                    } else {
                        settingsButton.requestFocus()
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    downloadDownPressCount = 0
                    downloadButton.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    downloadDownPressCount = 0
                    filmList.requestFocus()
                    true
                }
                else -> false
            }
        }

        // Settings <- Mario
        settingsButton.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    marioButton.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> true
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    filmList.requestFocus()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupRecyclerNavigation() {
        // Going left from either recycler always returns focus to homeButton
        filmList.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    homeButton.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    showList.requestFocus()
                    true
                }
                else -> false
            }
        }

        showList.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    homeButton.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    filmList.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    gameList.requestFocus()
                    true
                }
                else -> false
            }
        }

        gameList.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    homeButton.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    showList.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> true   // nothing below
                else -> false
            }
        }
    }

    private fun sortCollections(collection: Map<String, List<String>>): List<String> = listOf()

    private fun sortMedia(keys: List<String>, catalog: Map<String, Media>): List<String> {
        return keys.sortedWith(compareBy(
            { key ->
                val title = catalog[key]?.Title ?: key
                val words = title.trim().split("\\s+".toRegex())
                val firstWord = words.firstOrNull() ?: ""
                if (firstWord.equals("The", ignoreCase = true)) {
                    words.getOrElse(1) { firstWord }.lowercase()
                } else {
                    firstWord.lowercase()
                }
            },
            { key -> catalog[key]?.Date ?: "" }
        ))
    }

    fun getAll() {
        lifecycleScope.launch(Dispatchers.IO) {
            var result: String? = null
            var httpURLConnection: HttpURLConnection? = null
            var errorMessage: String? = null

            try {
                val baseUrl = if (settings.ip.startsWith("http")) {
                    settings.ip
                } else {
                    "http://${settings.ip}"
                }

                val url = URL("$baseUrl/api/catalog")
                Log.d("URL", url.toString())

                // Show the URL being used for debugging
                launch(Dispatchers.Main) {
                    findViewById<TextView>(R.id.debug).text = url.toString()
                }

                httpURLConnection = url.openConnection() as HttpURLConnection
                httpURLConnection.requestMethod = "GET"
                httpURLConnection.connectTimeout = 3000
                httpURLConnection.readTimeout = 5000

                if (httpURLConnection.responseCode != HttpURLConnection.HTTP_OK) {
                    errorMessage =
                        "Server returned HTTP ${httpURLConnection.responseCode}"
                } else {
                    result = httpURLConnection.inputStream.bufferedReader().use {
                        it.readText()
                    }
                }
            } catch (e: Exception) {
                // Catch EVERYTHING: bad IP, timeout, invalid JSON later, etc.
                Log.e("MainActivity", "Failed to load catalog", e)
                errorMessage = e.message ?: e.javaClass.simpleName
            } finally {
                httpURLConnection?.disconnect()
            }

            // If download failed, stay in the app and show the error instead of crashing.
            if (result == null) {
                launch(Dispatchers.Main) {
                    findViewById<TextView>(R.id.debug).text =
                        "Catalog load failed: $errorMessage\nOpen Settings and fix the server IP."

                    // Optional: automatically focus Settings so Enter opens it immediately.
                    settingsButton.requestFocus()
                }
                return@launch
            }

            // Parse JSON separately so malformed responses do not crash the app.
            try {
                val json = Json {
                    ignoreUnknownKeys = true   // ignores extra fields
                    isLenient = true           // optional, more permissive parsing
                    coerceInputValues = true   // optional, coerces invalid/missing values
                }
                val catalog: Catalog = json.decodeFromString(result)

                jointCatalog = catalog.Films + catalog.Shows + catalog.Games
                rawCatalog = FinalCatalog(
                    catalog.Films,
                    catalog.Shows,
                    catalog.Games,
                    catalog.Collections,
                    sortCollections(catalog.Collections)
                )

                val filmObject = sortMedia(rawCatalog.Films.keys.toList(), rawCatalog.Films)
                val showObject = sortMedia(rawCatalog.Shows.keys.toList(), rawCatalog.Shows)
                val gameObject = sortMedia(rawCatalog.Games.keys.toList(), rawCatalog.Games)

                launch(Dispatchers.Main) {
                    initializeRecycler(
                        filmList,
                        ItemAdapter(this@MainActivity, filmObject, 10)
                    )
                    initializeRecycler(
                        showList,
                        ItemAdapter(this@MainActivity, showObject, 10)
                    )
                    initializeRecycler(
                        gameList,
                        ItemAdapter(this@MainActivity, gameObject, 0)
                    )

                    findViewById<TextView>(R.id.debug).text =
                        "Loaded ${filmObject.size} films, ${showObject.size} shows, and ${gameObject.size} games."
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to parse catalog JSON", e)

                launch(Dispatchers.Main) {
                    findViewById<TextView>(R.id.debug).text =
                        "Invalid catalog data.\nOpen Settings and verify the server."

                    // Focus Settings so the user can immediately press Enter.
                    settingsButton.requestFocus()
                }
            }
        }
    }

    private fun initializeRecycler(recyclerView: IncrementalRecyclerView, adapter: ItemAdapter) {
        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(
            recyclerView.context, LinearLayoutManager.HORIZONTAL, false
        )
        recyclerView.isFocusable = true
        recyclerView.setHasFixedSize(true)
    }

    override fun click(mediaKey: String) {
        val media = jointCatalog[mediaKey] ?: return

        when (media) {
            is Game -> {
                launchGame(media)
            }

            is Film, is Show -> {
                startActivity(
                    Intent(this, VideoActivity::class.java).putExtra("videoKey", mediaKey)
                )
            }
        }
    }

    private fun launchGame(game: Game) {
        val baseUrl =
            if (settings.ip.startsWith("http")) settings.ip
            else "http://${settings.ip}"

        val url = "$baseUrl/media/${game.LocalFile}"

        val intent = Intent(this, EmulatorActivity::class.java).apply {
            putExtra(EmulatorActivity.EXTRA_ROM_URL, url)
        }

        startActivity(intent)
    }


    fun search(view: View) = startActivity(Intent(this, SearchActivity::class.java))

    fun collections(view: View) = startActivity(Intent(this, CollectionActivity::class.java))

    fun download(view: View) = startActivity(Intent(this, DownloadActivity::class.java))

    fun settings(view: View) {
        val options = ActivityOptionsCompat.makeCustomAnimation(
            this, android.R.anim.fade_in, android.R.anim.fade_out
        )
        settingsResultLauncher.launch(Intent(this, SettingsActivity::class.java), options)
    }

    private val settingsResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) getAll()
    }

    private fun loadSettings(context: Context, fileName: String): Settings? {
        return try {
            val jsonString = context.openFileInput(fileName).bufferedReader().use { it.readText() }
            Json.decodeFromString<Settings>(jsonString)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }


    fun launchTestRom(view: View) {
        val baseUrl = if (settings.ip.startsWith("http")) settings.ip else "http://${settings.ip}"
        val url = "$baseUrl/media/mario.nes"

        val intent = Intent(this, EmulatorActivity::class.java).apply {
            putExtra(EmulatorActivity.EXTRA_ROM_URL, url)
        }

        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()

        getAll()
    }
}