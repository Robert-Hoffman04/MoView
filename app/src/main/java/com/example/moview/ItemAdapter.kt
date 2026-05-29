package com.example.moview

import android.graphics.Color
import android.text.Html
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

class ItemAdapter(private val clickListener : ItemAdapterListener, private val videoList : List<String>, val wrap : Int): RecyclerView.Adapter<ItemAdapter.ViewHolder>() {

    interface ItemAdapterListener {
        fun click(videoKey : String)
    }

    class ViewHolder(private val clickListener : ItemAdapterListener, itemView: View, private val itemCount: Int, private val wrap : Int) : RecyclerView.ViewHolder(itemView) {

        private val card : CardView = itemView.findViewById(R.id.recycle_Card)
        private val moviePoster: ImageView = itemView.findViewById(R.id.recyclePoster)
        private val videoYear : TextView = itemView.findViewById(R.id.videoItem_YearLabel)
        private val movieTitle: TextView = itemView.findViewById(R.id.recycleTitle)
        private var videoKey: String = ""

        init {
            itemView.setOnClickListener {
                clickListener.click(videoKey)
            }

            itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    card.setCardBackgroundColor(Color.parseColor("#404757"))
                } else {
                    card.setCardBackgroundColor(Color.parseColor("#2f3441"))
                }
            }

            // Set up the key listener to handle scrolling on DPAD_LEFT and DPAD_RIGHT
            itemView.setOnKeyListener { v, keyCode, _ ->
                val rv = v.parent as RecyclerView
                // Only proceed if the itemView has focus
                if (!itemView.hasFocus()) {
                    return@setOnKeyListener false // Return false if itemView does not have focus
                }
                var position = absoluteAdapterPosition

                if (wrap > 0 && position == itemCount - wrap)
                {
                    rv.scrollToPosition(0)
                    rv.post {
                        val targetView = rv.layoutManager?.findViewByPosition(2)
                        targetView?.requestFocus()
                    }
                    return@setOnKeyListener false
                }

                val actualPosition = rv.computeHorizontalScrollOffset()
                val desirePosition = (position - 2) * 260
                val dif = desirePosition - actualPosition

                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (position < itemCount - 3)
                            rv.scrollBy(dif, 0)
                        false
                    }

                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (position > 2)
                            rv.scrollBy(dif, 0)
                        false
                    }

                    else -> false
                }
            }


        }

        fun update(key : String) {
            videoKey = key
            val video = MainActivity.jointCatalog[key] ?: return
            Glide
                .with(itemView)
                .load(MainActivity.settings.ip + "/media/" + video.LocalPoster)
                .into(moviePoster)

            Log.d("TEST", MainActivity.settings.ip + "/media/" + video.LocalPoster)

            videoYear .text = video.Date.let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString() }.take(4)
            movieTitle.text = Html.fromHtml(video.Title as String?, 0).toString()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_layout, parent, false)
        return ViewHolder(clickListener, view, itemCount, (wrap - 2))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int)
    {
        var title : String
        if ((itemCount - wrap) > 0)
            title = videoList[position % (itemCount - wrap)]
        else
            title = ""
        holder.update(title)
    }

    override fun getItemCount(): Int {
        return videoList.size + wrap
    }
}
