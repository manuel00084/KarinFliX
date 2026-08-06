package com.karin.streamtv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.karin.streamtv.R
import com.karin.streamtv.util.onActionKey

data class FolderItem(
    val name: String,
    val path: String,
    val count: Int
)

class FolderAdapter(
    private var items: List<FolderItem>,
    private val onItemClick: (FolderItem) -> Unit
) : RecyclerView.Adapter<FolderAdapter.ViewHolder>() {

    fun submitList(newItems: List<FolderItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_folder, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val ivIcon: ImageView = view.findViewById(R.id.iv_icon)
        private val tvName: TextView = view.findViewById(R.id.tv_folder_name)
        private val tvPath: TextView = view.findViewById(R.id.tv_folder_path)
        private val tvCount: TextView = view.findViewById(R.id.tv_count)

        fun bind(item: FolderItem) {
            tvName.text = item.name
            tvPath.text = item.path
            tvCount.text = item.count.toString()

            itemView.setOnClickListener { onItemClick(item) }
            itemView.onActionKey { onItemClick(item) }
        }
    }
}
