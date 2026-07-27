package com.karin.streamtv.ui

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.karin.streamtv.R

class DayTabAdapter(
    private val days: List<String>,
    private val selectedIndex: Int,
    private val onDayClick: (Int) -> Unit
) : RecyclerView.Adapter<DayTabAdapter.VH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_calendar_day, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.name.text = days[position]
        val isSelected = position == selectedIndex
        holder.name.setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
        holder.name.setTextColor(
            ContextCompat.getColor(holder.itemView.context,
                if (isSelected) R.color.accent else R.color.text_primary
            )
        )
        holder.name.alpha = if (isSelected) 1.0f else 0.6f
        holder.name.setOnClickListener { onDayClick(position) }
        holder.name.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                    android.view.KeyEvent.KEYCODE_ENTER -> {
                        onDayClick(position)
                        true
                    }
                    else -> false
                }
            } else false
        }
    }

    override fun getItemCount() = days.size

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tv_day_name)
    }
}
