package com.karin.streamtv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.karin.streamtv.R
import com.karin.streamtv.model.Site

class SiteAdapter(
    private val sites: List<Site>,
    private val onClick: (Site) -> Unit
) : RecyclerView.Adapter<SiteAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_site_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val site = sites[position]
        holder.tvInitial.text = site.name.take(1).uppercase()
        holder.tvTitle.text = site.name
        holder.tvDescription.text = site.description
        holder.itemView.setOnClickListener { onClick(site) }
        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            v?.elevation = if (hasFocus) 8f else 0f
        }
    }

    override fun getItemCount() = sites.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvInitial: TextView = view.findViewById(R.id.tv_initial)
        val tvTitle: TextView = view.findViewById(R.id.tv_title)
        val tvDescription: TextView = view.findViewById(R.id.tv_description)
    }
}
