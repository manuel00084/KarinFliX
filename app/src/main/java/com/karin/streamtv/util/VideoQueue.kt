package com.karin.streamtv.util

import com.karin.streamtv.model.PlaylistItem

object VideoQueue {

    private val queue = mutableListOf<PlaylistItem>()
    private val listeners = mutableListOf<() -> Unit>()

    fun add(item: PlaylistItem) {
        queue.add(item)
        notifyListeners()
    }

    fun addAll(items: List<PlaylistItem>) {
        queue.addAll(items)
        notifyListeners()
    }

    fun peek(): PlaylistItem? = queue.firstOrNull()

    fun poll(): PlaylistItem? {
        if (queue.isEmpty()) return null
        val item = queue.removeAt(0)
        notifyListeners()
        return item
    }

    fun removeAt(index: Int): PlaylistItem {
        val item = queue.removeAt(index)
        notifyListeners()
        return item
    }

    fun getAll(): List<PlaylistItem> = queue.toList()

    fun size(): Int = queue.size

    fun isEmpty(): Boolean = queue.isEmpty()

    fun clear() {
        queue.clear()
        notifyListeners()
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        listeners.forEach { it() }
    }
}
