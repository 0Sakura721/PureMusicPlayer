package com.puremusicplayer.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.puremusicplayer.databinding.SheetQueueBinding
import com.puremusicplayer.player.PlayerControls
import com.puremusicplayer.player.PlayerManager

/**
 * 播放队列底部弹层（Salt 风：从正在播放页呼出，查看并点击切歌）。
 * 列表直接复用全局 PlayerManager.playlist，点击即跳转播放。
 */
class QueueBottomSheet : BottomSheetDialogFragment() {

    private var _binding: SheetQueueBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = SheetQueueBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        refresh()
    }

    /** 用当前队列快照刷新列表与高亮 */
    fun refresh() {
        val list = ArrayList(PlayerManager.playlist)
        binding.recyclerView.adapter = SongAdapter(
            list,
            PlayerManager.currentIndex
        ) { index ->
            PlayerControls.jump(requireContext(), index)
            dismiss()
        }
        binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
