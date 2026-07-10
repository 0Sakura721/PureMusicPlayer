package com.puremusicplayer.ui

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

/**
 * 播放页分页适配器：两页（封面 / 歌词），每页是一个预构建的 View。
 * 用 position 作为 viewType，使每页持有各自独立的视图实例。
 */
class NpPagerAdapter(private val pages: List<View>) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    override fun getItemCount(): Int = pages.size

    override fun getItemViewType(position: Int): Int = position

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        object : RecyclerView.ViewHolder(pages[viewType]) {}

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
}
