package com.example.moview

import android.graphics.Color
import android.text.Html
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

class ExternalItemAdapter(private val clickListener : ExternalItemAdapterListener, private val videoList : List<ExternalResult>, val wrap : Int): RecyclerView.Adapter<ExternalItemAdapter.ViewHolder>()
{
    interface ExternalItemAdapterListener {
        fun download(video : ExternalResult)
    }


    class ViewHolder(private val clickListener : ExternalItemAdapterListener, itemView: View, private val itemCount: Int, private val wrap : Int) : RecyclerView.ViewHolder(itemView) {

        private val card : CardView = itemView.findViewById(R.id.recycle_Card)
        private val moviePoster: ImageView = itemView.findViewById(R.id.recyclePoster)
        private val movieTitle: TextView = itemView.findViewById(R.id.recycleTitle)
        private lateinit var video: ExternalResult
        init {
            itemView.setOnClickListener {
                clickListener.download(video)
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

        fun update(item : ExternalResult)
        {
            video = item
            movieTitle.text = item.name
            Glide
                .with(itemView)
                .load(item.poster)
                .into(moviePoster)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder
    {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_layout, parent, false)
        return ViewHolder(clickListener, view, itemCount, (wrap - 2))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int)
    {
        holder.update(videoList[position % videoList.size])
    }

    override fun getItemCount(): Int {
        return videoList.size + wrap
    }
}
