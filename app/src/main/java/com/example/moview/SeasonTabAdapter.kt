package com.example.moview

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Data model for a single season tab (including the Random pseudo-season). */
data class SeasonTabItem(
    val label: String,
    val episodeCount: Int
)

/**
 * SeasonTabAdapter
 *
 * Horizontal strip of season tabs. Tab 0 is always "Random".
 * Tabs 1..N correspond to the show's actual seasons.
 */
class SeasonTabAdapter(
    private val items: List<SeasonTabItem>,
    private val onSeasonSelected: (index: Int) -> Unit
) : RecyclerView.Adapter<SeasonTabAdapter.SeasonVH>() {

    private var selectedIndex: Int = 1

    fun setSelected(index: Int) {
        val prev = selectedIndex
        selectedIndex = index
        notifyItemChanged(prev)
        notifyItemChanged(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SeasonVH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_season_tab, parent, false)
        return SeasonVH(view)
    }

    override fun onBindViewHolder(holder: SeasonVH, position: Int) {
        val item = items[position]
        holder.bind(item, isSelected = position == selectedIndex, totalCount = items.size) {
            onSeasonSelected(position)
        }
    }

    override fun getItemCount() = items.size

    class SeasonVH(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val tvLabel: TextView = itemView.findViewById(R.id.tvSeasonLabel)
        private val tvCount: TextView = itemView.findViewById(R.id.tvEpisodeCount)
        private val root: View        = itemView.findViewById(R.id.seasonTabRoot)

        fun bind(item: SeasonTabItem, isSelected: Boolean, totalCount: Int, onClick: () -> Unit) {
            tvLabel.text = item.label
            tvCount.text = "${item.episodeCount} episodes"

            root.isSelected = isSelected
            root.setOnClickListener { onClick() }

            root.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER -> {
                            onClick()
                            return@setOnKeyListener true
                        }
                        // Prevent focus leaving the season row at boundaries.
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            val pos = bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
                                ?: layoutPosition
                            if (pos == 0) return@setOnKeyListener true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            val pos = bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
                                ?: layoutPosition
                            if (pos == totalCount - 1) return@setOnKeyListener true
                        }
                    }
                }
                false
            }

            // Focus: just dim when unfocused
            root.setOnFocusChangeListener { v, hasFocus ->
                v.alpha = if (hasFocus) 1f else 0.7f
            }
        }
    }
}