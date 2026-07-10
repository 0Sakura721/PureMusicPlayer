package com.puremusicplayer.ui

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.puremusicplayer.data.Song
import com.puremusicplayer.databinding.ItemSongBinding

/**
 * 专辑 / 艺术家 分组项：点击后播放该分组内的全部歌曲。
 */
data class MusicGroup(
    val title: String,
    val subtitle: String,
    val artUri: Uri?,
    val songs: List<Song>
)

class GroupAdapter(
    private val items: List<MusicGroup>,
    private val onGroupClick: (List<Song>) -> Unit
) : RecyclerView.Adapter<GroupAdapter.VH>() {

    inner class VH(val binding: ItemSongBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemSongBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val group = items[position]
        holder.binding.tvTitle.text = group.title
        holder.binding.tvArtist.text = group.subtitle
        holder.binding.tvDuration.text = "${group.songs.size} 首"
        group.artUri?.let { holder.binding.ivArt.load(it) }
        holder.binding.root.setOnClickListener { onGroupClick(group.songs) }
    }

    override fun getItemCount(): Int = items.size
}
