package com.example.moview

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.moview.ItemAdapter.ItemAdapterListener
import kotlin.math.min

class CollectionAdapter(private val clickListener : ItemAdapterListener): RecyclerView.Adapter<CollectionAdapter.ViewHolder>()
{
    class ViewHolder(private val clickListener : ItemAdapterListener, itemView: View) : RecyclerView.ViewHolder(itemView)
    {
        private val collectionTitle: TextView = itemView.findViewById(R.id.collectionTitle)
        private val collectionTiles: RecyclerView = itemView.findViewById(R.id.collectionTiles)

        fun update(key : String)
        {
            val collections = MainActivity.rawCatalog.Collections
            val collection = collections[key] ?: return

            collectionTitle.text = key
            collectionTiles.adapter = ItemAdapter(clickListener, collection.toList(), 0)
            collectionTiles.layoutManager = GridLayoutManager(collectionTiles.context, min(collection.size, 7))
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.collection_layout, parent, false)
        return ViewHolder(clickListener, view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val key = MainActivity.rawCatalog.Collections.keys.toList()[position]
        holder.update(key)
    }

    override fun getItemCount(): Int
    {
        return MainActivity.rawCatalog.Collections.size
    }
}