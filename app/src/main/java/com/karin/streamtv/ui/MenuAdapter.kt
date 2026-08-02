package com.karin.streamtv.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Button
import androidx.recyclerview.widget.RecyclerView
import com.karin.streamtv.R
import com.karin.streamtv.model.SiteMenuItem

class MenuAdapter(
    private val items: List<SiteMenuItem>,
    private val onItemClick: (SiteMenuItem) -> Unit
) : RecyclerView.Adapter<MenuAdapter.MenuViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MenuViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_menu, parent, false) as Button
        return MenuViewHolder(view)
    }

    override fun onBindViewHolder(holder: MenuViewHolder, position: Int) {
        holder.bind(items[position], onItemClick)
    }

    override fun getItemCount() = items.size

    class MenuViewHolder(private val btn: Button) : RecyclerView.ViewHolder(btn) {
        fun bind(item: SiteMenuItem, onClick: (SiteMenuItem) -> Unit) {
            btn.text = item.name
            btn.setOnClickListener { onClick(item) }
        }
    }
}
