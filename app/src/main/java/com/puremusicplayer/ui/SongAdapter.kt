package com.puremusicplayer.ui

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.puremusicplayer.R
import com.puremusicplayer.data.Song
import com.puremusicplayer.databinding.ItemSongBinding
import com.puremusicplayer.player.PlayerManager
import com.puremusicplayer.util.formatMs

class SongAdapter(
    private val items: List<Song>,
    var currentIndex: Int = -1,
    private val onFavClick: (Int) -> Unit = {},
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<SongAdapter.VH>() {

    inner class VH(val binding: ItemSongBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemSongBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val song = items[position]
        val ctx = holder.binding.root.context
        holder.binding.tvTitle.text = song.title
        holder.binding.tvArtist.text = song.artist
        holder.binding.tvDuration.text = formatMs(song.duration)
        song.albumArtUri?.let { holder.binding.ivArt.load(it) }
        holder.binding.root.setOnClickListener { onClick(position) }

        // 收藏爱心：已收藏用品牌色实心，否则用主题色描边（保证深色/AMOLED 可见）
        val fav = PlayerManager.favorites.value?.contains(song.favKey()) == true
        holder.binding.btnFav.setImageResource(
            if (fav) R.drawable.ic_heart else R.drawable.ic_heart_outline
        )
        if (fav) {
            holder.binding.btnFav.setColorFilter(
                ContextCompat.getColor(ctx, R.color.brand_primary)
            )
        } else {
            val tv2 = TypedValue()
            ctx.theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, tv2, true)
            holder.binding.btnFav.setColorFilter(tv2.data)
        }
        holder.binding.btnFav.setOnClickListener { onFavClick(position) }

        // 高亮当前正在播放的曲目（播放队列场景）
        val isCurrent = position == currentIndex
        if (isCurrent) {
            holder.binding.tvTitle.setTextColor(
                ContextCompat.getColor(ctx, R.color.brand_primary)
            )
        } else {
            // 跟随主题：解析 colorOnSurface，保证浅色/深色/AMOLED 下均可见
            val tv = TypedValue()
            ctx.theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, tv, true)
            holder.binding.tvTitle.setTextColor(tv.data)
        }
    }

    override fun getItemCount(): Int = items.size
}
