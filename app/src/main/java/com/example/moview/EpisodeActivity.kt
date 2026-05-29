package com.example.moview

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * EpisodeActivity
 *
 * Overlay activity that lets a user pick a season and episode using a
 * TV remote / D-pad. Launched with:
 *
 *   startActivityForResult(
 *       Intent(this, EpisodeActivity::class.java).apply {
 *           putExtra("videoKey", key)
 *       },
 *       REQUEST_EPISODE
 *   )
 *
 * Result extras on RESULT_OK:
 *   "seasonIndex" → Int  — 0 = Random, 1..N = actual season
 *   "episodeIndex" → Int — position within the submitted episode list
 *
 * The show data is pulled from MainActivity.rawCatalog.Shows[videoKey].
 */
class EpisodeActivity : AppCompatActivity(), EpisodeCardAdapter.ClickListener {

    private lateinit var show: Show
    private lateinit var rvSeasons: RecyclerView
    private lateinit var rvEpisodes: RecyclerView

    private lateinit var seasonAdapter: SeasonTabAdapter
    private lateinit var episodeAdapter: EpisodeCardAdapter

    /**
     * Tracks the season currently displayed in the episode row.
     * 0 = Random, 1..N = actual season number.
     * Exposed as a field so [click] can read it without a capture.
     */
    var currentSeason: Int = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Transparent window so the previous activity shows through
        window.setBackgroundDrawableResource(android.R.color.transparent)
        setContentView(R.layout.activity_episode)

        // ── Resolve show from global catalog ──────────────────────────
        val videoKey = intent.getStringExtra("videoKey") ?: run { finish(); return }
        show = MainActivity.rawCatalog.Shows[videoKey] ?: run { finish(); return }

        rvSeasons  = findViewById(R.id.rvSeasons)
        rvEpisodes = findViewById(R.id.rvEpisodes)

        setupSeasonRow()
        setupEpisodeRow()

        // Start with Season 1 selected
        selectSeason(1)
    }

    // ─────────────────────────────────────────────────────────────────
    // Season row
    // ─────────────────────────────────────────────────────────────────

    private fun setupSeasonRow() {
        val seasonCount = show.Episodes.size

        // Build label list: index 0 = Random, indices 1..N = actual seasons
        val labels = buildList {
            add(SeasonTabItem(label = "Random", episodeCount = show.EpisodeNumber))
            for (i in 1..seasonCount) {
                add(SeasonTabItem(label = "Season $i", episodeCount = show.Episodes[i - 1].size))
            }
        }

        seasonAdapter = SeasonTabAdapter(labels) { tabIndex ->
            selectSeason(tabIndex)
        }

        rvSeasons.apply {
            layoutManager = LinearLayoutManager(
                this@EpisodeActivity,
                LinearLayoutManager.HORIZONTAL,
                false
            )
            adapter = seasonAdapter
            addOnScrollListener(object : RecyclerView.OnScrollListener() {})
            // LEFT/RIGHT navigation is handled natively by RecyclerView.
            // UP/DOWN focus traversal between rows is controlled by the layout
            // via android:nextFocusDown / android:nextFocusUp on each RecyclerView.
        }
    }

    /**
     * @param index 0 = Random, 1..N = Season N
     */
    private fun selectSeason(index: Int) {
        currentSeason = index
        seasonAdapter.setSelected(index)

        val episodes: List<Episode> = when (index) {
            0    -> show.Episodes.flatten().shuffled() // Random: all episodes shuffled
            else -> show.Episodes.getOrElse(index - 1) { emptyList() }
        }

        episodeAdapter.submitList(emptyList()) {
            episodeAdapter.submitList(episodes) {
                // Scroll episode list back to the beginning after the new list
                // has been fully applied.
                rvEpisodes.scrollToPosition(0)

                // Move focus to the first episode so the highlighted item
                // matches the newly randomized order.
                rvEpisodes.post {
                    rvEpisodes.findViewHolderForAdapterPosition(0)
                        ?.itemView
                        ?.requestFocus()
                }
            }
        }

        // Scroll episode list back to start
        rvEpisodes.scrollToPosition(0)
    }

    // ─────────────────────────────────────────────────────────────────
    // Episode row
    // ─────────────────────────────────────────────────────────────────

    private fun setupEpisodeRow() {
        episodeAdapter = EpisodeCardAdapter(this)

        rvEpisodes.apply {
            layoutManager = LinearLayoutManager(
                this@EpisodeActivity,
                LinearLayoutManager.HORIZONTAL,
                false
            )
            adapter = episodeAdapter

            setItemViewCacheSize(20)
            // LEFT/RIGHT navigation is handled natively by RecyclerView.
            // UP/DOWN focus traversal between rows is controlled by the layout
            // via android:nextFocusUp / android:nextFocusDown on each RecyclerView.
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // EpisodeCardAdapter.ClickListener
    // ─────────────────────────────────────────────────────────────────

    override fun click(episode: Int) {
        val resultIntent = Intent().apply {
            if (currentSeason == 0) {
                // In Random mode, the adapter position refers to a shuffled list
                // containing Episode objects from all seasons. We need to locate
                // the selected object in the original show.Episodes structure to
                // determine its real season and episode indices.
                val selected = episodeAdapter.currentList.getOrNull(episode)

                if (selected != null) {
                    var resolvedSeason = 1
                    var resolvedEpisode = 0
                    var found = false

                    for ((seasonIndex, seasonEpisodes) in show.Episodes.withIndex()) {
                        val episodeIndex = seasonEpisodes.indexOf(selected)
                        if (episodeIndex >= 0) {
                            resolvedSeason = seasonIndex + 1   // convert to 1-based
                            resolvedEpisode = episodeIndex     // keep 0-based
                            found = true
                            break
                        }
                    }

                    if (found) {
                        putExtra("seasonIndex", resolvedSeason)
                        putExtra("episodeIndex", resolvedEpisode)
                    } else {
                        // Fallback if the object cannot be located
                        putExtra("seasonIndex", 0)
                        putExtra("episodeIndex", 0)
                    }
                } else {
                    // Fallback if the adapter position is invalid
                    putExtra("seasonIndex", 0)
                    putExtra("episodeIndex", 0)
                }
            } else {
                // Normal season mode
                putExtra("seasonIndex", currentSeason - 1)
                putExtra("episodeIndex", episode)
            }
        }

        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    // ─────────────────────────────────────────────────────────────────
    // D-pad: dismiss on BACK
    // ─────────────────────────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}