package com.example.moview

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

/**
 * EpisodeCardAdapter
 *
 * Horizontal strip of episode cards. Each card shows:
 *  • Thumbnail image (loaded from Episode.LocalImage)
 *  • Episode number badge  (e.g. "E03")
 *  • Episode title
 *  • Short description (2-line truncated)
 */
class EpisodeCardAdapter(
    private val listener: ClickListener
) : ListAdapter<Episode, EpisodeCardAdapter.EpisodeVH>(DIFF) {

    /** Implemented by EpisodeSelectorActivity to return the result. */
    interface ClickListener {
        fun click(episode: Int)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpisodeVH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_episode_card, parent, false)
        return EpisodeVH(view)
    }

    override fun onBindViewHolder(holder: EpisodeVH, position: Int) {
        holder.bind(getItem(position), episodeNumber = position + 1, onClick = {
            listener.click(position)
        })
    }

    // ── ViewHolder ────────────────────────────────────────────────────

    inner class EpisodeVH(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val root: View            = itemView.findViewById(R.id.episodeCardRoot)
        private val ivThumb: ImageView    = itemView.findViewById(R.id.ivEpisodeThumbnail)
        private val tvNumber: TextView    = itemView.findViewById(R.id.tvEpisodeNumber)
        private val tvTitle: TextView     = itemView.findViewById(R.id.tvEpisodeTitle)
        private val tvDesc: TextView      = itemView.findViewById(R.id.tvEpisodeDescription)

        fun bind(episode: Episode, episodeNumber: Int, onClick: () -> Unit) {

            tvNumber.text = "E%02d".format(episodeNumber)
            tvTitle.text  = android.text.Html.fromHtml(episode.Title, android.text.Html.FROM_HTML_MODE_LEGACY)
            tvDesc.text   = android.text.Html.fromHtml(episode.Description, android.text.Html.FROM_HTML_MODE_LEGACY)

            ivThumb.setImageDrawable(null)
            if (episode.LocalImage.isNotBlank()) {
                Glide.with(ivThumb)
                    .load(MainActivity.settings.ip + "/media/" + episode.LocalImage)
                    .centerCrop()
                    .into(ivThumb)
            }

            // Click / D-pad center
            root.setOnClickListener { onClick() }
            root.setOnKeyListener { _, keyCode, event ->
                if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                    when (keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                        android.view.KeyEvent.KEYCODE_ENTER -> {
                            onClick()
                            return@setOnKeyListener true
                        }
                        // Block LEFT/RIGHT when position is unknown during rebinding,
                        // or at the actual boundaries. This prevents focus escaping to
                        // the season row during fast scrolling when bindingAdapterPosition
                        // is transiently NO_POSITION.
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                            val pos = bindingAdapterPosition
                            if (pos == RecyclerView.NO_POSITION || pos == 0)
                                return@setOnKeyListener true
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            val pos = bindingAdapterPosition
                            if (pos == RecyclerView.NO_POSITION || pos == itemCount - 1)
                                return@setOnKeyListener true
                        }
                    }
                }
                false
            }

            // Focus animation — scale up slightly and brighten
            root.setOnFocusChangeListener { v, hasFocus ->
                v.animate()
                    .scaleX(if (hasFocus) 1.05f else 1f)
                    .scaleY(if (hasFocus) 1.05f else 1f)
                    .setDuration(140)
                    .start()
            }
        }
    }

    // ── DiffUtil ──────────────────────────────────────────────────────

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<Episode>() {
            override fun areItemsTheSame(a: Episode, b: Episode) =
                a.LocalFile == b.LocalFile

            override fun areContentsTheSame(a: Episode, b: Episode) =
                a == b
        }
    }
}