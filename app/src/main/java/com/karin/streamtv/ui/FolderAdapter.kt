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
            tvPath.text = friendlyPath(item.path)
            tvCount.text = if (item.count > 0) item.count.toString() else ""
            tvPath.visibility = if (tvPath.text.isBlank()) View.GONE else View.VISIBLE

            itemView.setOnClickListener { onItemClick(item) }
            itemView.onActionKey { onItemClick(item) }
        }

        // No exponer prefijos internos (fs:/storage/..., __net_host__:ip) en
        // una UI de TV con mando. (Las rutas de red/nube manuales se
        // eliminaron con la función Añadir; la red ahora es automática.)
        private fun friendlyPath(path: String): String {
            return when {
                path == "__all__" || path == "__net__" || path == "__net_rescan__" ||
                    path.startsWith("__videos") || path.startsWith("__all_in") -> ""
                path.startsWith("__net_host__:") ->
                    path.removePrefix("__net_host__:").substringBeforeLast(":")
                path.startsWith("fs:") -> path.removePrefix("fs:")
                else -> path
            }
        }
    }
}
