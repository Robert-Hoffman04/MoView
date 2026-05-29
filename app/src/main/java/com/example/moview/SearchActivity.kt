package com.example.moview

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.moview.MainActivity.Companion.settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale


class SearchActivity : AppCompatActivity(), ItemAdapter.ItemAdapterListener, ExternalItemAdapter.ExternalItemAdapterListener
{

    private lateinit var searchBar : EditText
    private var searchString : String = ""

    private lateinit var autofillList : ListView
    private lateinit var searchGrid : RecyclerView
    private var currentData: List<String> = listOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_search)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        searchBar = findViewById(R.id.searchBar)
        autofillList = findViewById(R.id.autofill)
        searchGrid = findViewById(R.id.searchGrid)

        autofillList.setOnItemClickListener { _, _, position, _ ->
            val selectedItem = autofillList.getItemAtPosition(position) as String
            searchString = selectedItem // Set the searchString to the selected item
            searchBar.setText(searchString) // Set the text of the searchBar to the selected item
            updateSearch() // Update the search results
        }
    }

    fun appendChar(view : View)
    {
        val letter : Char = view.tag.toString()[0]

        if (letter == ' ' && searchString.isEmpty())
            return


        searchString += letter
        searchString = searchString.titleCase()

        searchBar.setText(searchString)
        updateSearch()
    }

    @Suppress("UNUSED_PARAMETER")
    fun backspace(view : View)
    {
        searchString = searchString.dropLast(1)

        searchBar.setText(searchString)
        updateSearch()
    }

    private fun String.titleCase(): String {
        return this.split(" ").joinToString(" ") { it.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(
                Locale.ROOT
            ) else it.toString()
        } }
    }

    private fun updateSearch()
    {
        if (searchString.isEmpty()) {
            limitListToVisibleItems(autofillList, emptyList())

            val emptyAdapter = ItemAdapter(
                this@SearchActivity,
                emptyList(),
                0
            )
            initializeRecycler(searchGrid, emptyAdapter)
            return
        }

        lifecycleScope.launch(Dispatchers.IO)
        {
            val titleResults = ArrayList<String>()
            val genreResults = ArrayList<String>()
            val actorResults = ArrayList<String>()
            val keywordResults = ArrayList<String>()
            val titleKeys = ArrayList<String>()  // List to store title keys
            val genreKeys = ArrayList<String>()  // List to store genre keys
            val actorKeys = ArrayList<String>()  // List to store actor keys
            val keywordKeys = ArrayList<String>()  // List to store keyword keys

            val searchStringLower = searchString.lowercase() // Convert searchString to lowercase

            val keys = MainActivity.jointCatalog.keys.toTypedArray()
            outerLoop@ for (key in keys) {
                val video = MainActivity.jointCatalog[key] ?: continue@outerLoop

                // Case-insensitive comparison of the title
                if (searchStringLower in video.Title.lowercase()) {
                    titleResults.add(video.Title)
                    titleKeys.add(key)  // Add the corresponding title key
                } else {
                    for (genre in video.Genre) {
                        // Case-insensitive comparison of genres
                        if (searchStringLower in genre.lowercase()) {
                            genreResults.add(genre)
                            genreKeys.add(key)  // Add the corresponding genre key
                            continue@outerLoop
                        }
                    }

                    for (actor in video.Actor) {
                        // Case-insensitive comparison of actors
                        if (searchStringLower in actor.lowercase()) {
                            actorResults.add(actor)
                            actorKeys.add(key)  // Add the corresponding actor key
                            continue@outerLoop
                        }
                    }

                    for (keyword in video.Keywords) {
                        // Case-insensitive comparison of keywords
                        if (searchStringLower in keyword.lowercase()) {
                            keywordResults.add(keyword)
                            keywordKeys.add(key)  // Add the corresponding keyword key
                            continue@outerLoop
                        }
                    }
                }
            }

            // Combine all results and keys in order
            val combinedResults = ArrayList<String>()
            val combinedKeys = ArrayList<String>()

            combinedResults.addAll(titleResults)
            combinedResults.addAll(genreResults)
            combinedResults.addAll(actorResults)
            combinedResults.addAll(keywordResults)

            combinedKeys.addAll(titleKeys)
            combinedKeys.addAll(genreKeys)
            combinedKeys.addAll(actorKeys)
            combinedKeys.addAll(keywordKeys)

            // Remove duplicates while preserving order
            val uniqueResults = combinedResults.distinct()
            var uniqueKeys = combinedKeys.distinct()

            // Ensure that the results and keys are in the same order
            val titleCasedResults = uniqueResults.map { it.titleCase() }

            limitListToVisibleItems(autofillList, titleCasedResults)


            lifecycleScope.launch(Dispatchers.Main)
            {
                val adapter = ItemAdapter(this@SearchActivity, uniqueKeys, 0)
                initializeRecycler(searchGrid, adapter)
            }
        }
    }

    private fun initializeRecycler(recyclerView: RecyclerView, adapter: ItemAdapter) {
        recyclerView.adapter = adapter
        val layoutManager = GridLayoutManager(this, 5)
        recyclerView.layoutManager = layoutManager
        recyclerView.isFocusable = true

    }

    private fun limitListToVisibleItems(listView: ListView, data: List<String>) {
        val currentAdapter = listView.adapter as? ArrayAdapter<String> // Cast to ArrayAdapter<String>

        listView.viewTreeObserver.addOnGlobalLayoutListener(object :
            ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                listView.viewTreeObserver.removeOnGlobalLayoutListener(this)

                val listViewHeight = listView.height // The available space for ListView

                if (listViewHeight == 0) return // Prevent issues if layout not ready

                val itemHeight = listView.getChildAt(0)?.height ?: 100 // Estimate if null
                val maxVisibleItems = listViewHeight / itemHeight // Items that fit

                // Limit data to visible items
                val limitedData = data.take(maxVisibleItems)

                // Check if the data has changed
                if (currentData != limitedData) {
                    // Update the adapter with the new limited data
                    currentData = limitedData // Update the current data tracker

                    if (currentAdapter != null) {
                        currentAdapter.clear()
                        currentAdapter.addAll(limitedData)
                        currentAdapter.notifyDataSetChanged()
                    } else {
                        // If currentAdapter is null, initialize it
                        val newAdapter = object : ArrayAdapter<String>(
                            listView.context,
                            android.R.layout.simple_list_item_1,
                            limitedData
                        ) {
                            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                                // Inflate or reuse the item layout
                                val view = convertView ?: LayoutInflater.from(context).inflate(android.R.layout.simple_list_item_1, parent, false)

                                // Get the TextView inside the item layout
                                val textView = view.findViewById<TextView>(android.R.id.text1)

                                // Set the default text color
                                textView.setTextColor(Color.WHITE)

                                // Set an OnFocusChangeListener to handle background color change on focus
                                view.setOnFocusChangeListener { v, hasFocus ->
                                    if (hasFocus) {
                                        // Change the background color of the item view when focused
                                        v.setBackgroundColor(ContextCompat.getColor(context, R.color.sidePanel))
                                    } else {
                                        // Revert the background color when focus is lost
                                        v.setBackgroundColor(ContextCompat.getColor(context, R.color.standardBackground))
                                    }
                                }

                                // Set the text for the item (if needed)
                                textView.text = getItem(position)

                                return view
                            }
                        }
                        listView.adapter = newAdapter
                    }
                }
            }
        })
    }

    override fun click(videoKey: String) {
        val myIntent: Intent = Intent(
            this@SearchActivity,
            VideoActivity::class.java
        )
        myIntent.putExtra("videoKey", videoKey)
        this@SearchActivity.startActivity(myIntent)
    }

    fun external(view: View) {
        val searchStringLower = searchString.lowercase()

        CoroutineScope(Dispatchers.IO).launch {
            val urlString = "${MainActivity.settings.ip}/api/search?filter=${searchStringLower}&type=Film"
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection

            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 15000
                connection.readTimeout = 15000

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }

                    val json = Json { ignoreUnknownKeys = true }
                    val searchResponse: SearchResponse = json.decodeFromString(response)

                    lifecycleScope.launch(Dispatchers.Main) {
                        val adapter = ExternalItemAdapter(this@SearchActivity, searchResponse.results, 0)
                        searchGrid.adapter = adapter
                    }
                } else {
                    println("Error: $responseCode")
                }
            } catch (e: java.net.SocketTimeoutException) {
                println("Request timed out: ${e.message}")
                lifecycleScope.launch(Dispatchers.Main) {
                    // Show a toast or error message to the user
                    android.widget.Toast.makeText(
                        this@SearchActivity,
                        "Search timed out. Check server connection.",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                println("Network error: ${e.message}")
            } finally {
                connection.disconnect()
            }
        }
    }

    override fun download(video: ExternalResult) {
        Thread {
            try {
                val baseURL = if (settings.ip.startsWith("http")) {
                    settings.ip
                } else {
                    "http://${settings.ip}"
                }

                // Send POST /api/download to enqueue the job first.
                val url = URL("${baseURL}/api/download")
                val connection = url.openConnection() as HttpURLConnection

                connection.requestMethod = "POST"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")

                val payload = JSONObject().apply {
                    put("source_url", video.source_url)
                    put("title", video.name)
                    put("type", video.type?.lowercase())   // "movie" or "tv"
                    if (!video.date.isNullOrBlank()) {
                        put("date", video.date)
                    }
                }

                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write(payload.toString())
                }

                val responseCode = connection.responseCode

                // Read response body so the request completes properly.
                val stream =
                    if (responseCode in 200..299) connection.inputStream
                    else connection.errorStream

                val responseText = stream?.bufferedReader()?.use { it.readText() } ?: ""

                android.util.Log.i(
                    "Download",
                    "POST /api/download -> HTTP $responseCode: $responseText"
                )

                connection.disconnect()

                // If the server accepted or found the file already,
                // launch the DownloadActivity on the UI thread.
                if (responseCode == 200 || responseCode == 202) {
                    runOnUiThread {
                        val intent = Intent(this, DownloadActivity::class.java).apply {
                            putExtra("title", video.name)
                            putExtra("source_url", video.source)
                            putExtra("type", video.type)
                            putExtra("date", video.date)
                        }
                        startActivity(intent)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("Download", "Failed to enqueue download", e)
            }
        }.start()
    }
}