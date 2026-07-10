package com.puremusicplayer.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.puremusicplayer.databinding.SheetQueueBinding
import com.puremusicplayer.player.PlayerControls
import com.puremusicplayer.player.PlayerManager
import java.util.Collections

/**
 * 播放队列底部弹层（Salt 风）。
 * 直接操作全局 PlayerManager.playlist：
 *  - 点击：跳转播放该首
 *  - 拖拽：重排队列（自动修正 currentIndex）
 *  - 滑动：从队列移除（若移除的是当前曲则续播下一首，删空则停止）
 */
class QueueBottomSheet : BottomSheetDialogFragment() {

    private var _binding: SheetQueueBinding? = null
    private val binding get() = _binding!!

    // 与 PlayerManager.playlist 同一个可变列表引用，便于原地增删改并通知
    private val queueList = PlayerManager.playlist

    private val adapter = SongAdapter(queueList, PlayerManager.currentIndex) { index ->
        PlayerControls.jump(requireContext(), index)
        dismiss()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = SheetQueueBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(
                rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder
            ): Boolean {
                val from = vh.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from < 0 || to < 0) return false
                Collections.swap(queueList, from, to)
                adapter.notifyItemMoved(from, to)
                syncCurrentIndex()
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val pos = vh.bindingAdapterPosition
                if (pos >= 0) removeAt(pos)
            }
        })
        touchHelper.attachToRecyclerView(binding.recyclerView)

        refresh()
    }

    /** 用当前队列状态刷新高亮与空态 */
    fun refresh() {
        adapter.currentIndex = PlayerManager.currentIndex
        adapter.notifyDataSetChanged()
        binding.tvEmpty.visibility = if (queueList.isEmpty()) View.VISIBLE else View.GONE
    }

    /** 重排后重新定位当前曲目在列表中的新位置 */
    private fun syncCurrentIndex() {
        val cur = PlayerManager.current()
        PlayerManager.currentIndex = if (cur != null) queueList.indexOf(cur) else -1
        adapter.currentIndex = PlayerManager.currentIndex
    }

    /** 从队列移除指定位置；同步播放状态 */
    private fun removeAt(pos: Int) {
        if (pos !in queueList.indices) {
            adapter.notifyItemChanged(pos)
            return
        }
        val wasCurrent = PlayerManager.currentIndex == pos
        queueList.removeAt(pos)
        if (wasCurrent) {
            if (queueList.isNotEmpty()) {
                val next = pos.coerceAtMost(queueList.size - 1)
                PlayerControls.jump(requireContext(), next)
            } else {
                PlayerManager.currentIndex = -1
                PlayerManager.currentSong.value = null
                PlayerControls.pause(requireContext())
            }
        } else if (pos < PlayerManager.currentIndex) {
            PlayerManager.currentIndex--
        }
        refresh()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
