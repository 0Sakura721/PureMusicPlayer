package com.puremusicplayer.ui

import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.puremusicplayer.MainActivity
import com.puremusicplayer.R
import com.puremusicplayer.data.MusicRepository
import com.puremusicplayer.data.Song
import com.puremusicplayer.databinding.FragmentLibraryBinding
import com.puremusicplayer.player.PlayerControls
import com.puremusicplayer.player.PlayerManager
import com.puremusicplayer.player.PlayMode
import com.puremusicplayer.util.Permissions
import com.puremusicplayer.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!

    private var allSongs = emptyList<Song>()
    private var albums = emptyList<MusicGroup>()
    private var artists = emptyList<MusicGroup>()
    private var favoritesList = emptyList<Song>()

    private var query = ""
    private var currentTab = 0
    /** 曲库排序方式：0 标题 / 1 艺术家 / 2 专辑 / 3 时长（降序）；持久化于 Prefs */
    private var sortMode = 0
    /** 记录上次加载所用的目录 Uri，仅在变更时（如设置页改了目录）才重新扫描 */
    private var lastLoadedTreeUri: String? = "<<init>>"
    /** 防止重复扫描 */
    private var isScanning = false

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) loadLibrary() else showEmpty(true)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Prefs.init(requireContext())
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())

        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.tab_songs))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.tab_albums))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.tab_artists))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.tab_favorites))
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentTab = tab?.position ?: 0
                applyView()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) = Unit
            override fun onTabReselected(tab: TabLayout.Tab?) = Unit
        })

        setupSearch()

        // 曲库排序：从偏好恢复上次选择，点击“排序”弹出选择
        sortMode = Prefs.sortModeOrdinal
        binding.sortButton.setOnClickListener { showSortDialog() }

        // 收藏集合变化时刷新（迷你栏/播放页也可能修改收藏）
        PlayerManager.favorites.observe(viewLifecycleOwner) { set ->
            favoritesList = allSongs.filter { set.contains(it.favKey()) }
            if (currentTab == 3) applyView()
        }

        if (Permissions.hasAudioPermission(requireContext())) loadLibrary()
        else requestPermission.launch(Permissions.audioPermissionName())
    }

    override fun onResume() {
        super.onResume()
        // 设置页切换了音乐目录后返回时，重新扫描曲库
        val cur = Prefs.musicTreeUri
        if (cur != lastLoadedTreeUri) {
            lastLoadedTreeUri = cur
            if (Permissions.hasAudioPermission(requireContext())) loadLibrary()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupSearch() {
        binding.searchView.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim().orEmpty()
                binding.searchClear.visibility =
                    if (query.isEmpty()) View.GONE else View.VISIBLE
                applyView()
            }
        })

        binding.searchClear.setOnClickListener {
            binding.searchView.setText("")
            binding.searchView.clearFocus()
        }
    }

    // ---------- 曲库加载（异步协程，避免 SAF 目录扫描阻塞 UI） ----------
    private fun loadLibrary() {
        if (isScanning) return
        isScanning = true
        lastLoadedTreeUri = Prefs.musicTreeUri

        showLoading(true)
        val treeUri = Prefs.musicTreeUri?.let { Uri.parse(it) }
        PlayerManager.syncFavorites()

        lifecycleScope.launch {
            val songs = withContext(Dispatchers.IO) {
                try {
                    MusicRepository.loadSongs(requireContext(), treeUri)
                } catch (e: Exception) {
                    // 扫描失败（如 Android 16 上 MediaStore 行为差异）时安全降级为空列表，而非崩溃
                    emptyList()
                }
            }
            applySongs(songs)
            isScanning = false
        }
    }

    private fun applySongs(songs: List<Song>) {
        allSongs = songs
        favoritesList = allSongs.filter { PlayerManager.favorites.value?.contains(it.favKey()) == true }
        PlayerManager.playlist.clear()
        PlayerManager.playlist.addAll(allSongs)
        PlayerManager.playMode = PlayMode.values().getOrElse(Prefs.playModeOrdinal) { PlayMode.REPEAT_ALL }
        PlayerManager.saveQueue(requireContext())
        showLoading(false)

        albums = allSongs.groupBy { it.album }
            .map { (name, list) ->
                MusicGroup(name, list.first().artist, list.first().albumArtUri, list)
            }
            .sortedBy { it.title }

        artists = allSongs.groupBy { it.artist }
            .map { (name, list) ->
                MusicGroup(name, "${list.size} 首", list.first().albumArtUri, list)
            }
            .sortedBy { it.title }

        currentTab = binding.tabLayout.selectedTabPosition
        applyView()
    }

    private fun showLoading(loading: Boolean) {
        binding.loadingView.visibility = if (loading) View.VISIBLE else View.GONE
        binding.tvLoading.text = if (loading && Prefs.musicTreeUri != null) {
            getString(R.string.scanning)
        } else {
            getString(R.string.scanning)
        }
    }

    /** 根据当前 tab 与搜索词刷新列表与空态 */
    private fun applyView() {
        val q = query.lowercase()

        val filteredSongs = if (q.isEmpty()) allSongs else allSongs.filter { matchesSong(it, q) }
        val filteredAlbums = if (q.isEmpty()) albums else albums.filter { it.title.lowercase().contains(q) }
        val filteredArtists = if (q.isEmpty()) artists else artists.filter { it.title.lowercase().contains(q) }
        val filteredFavs = if (q.isEmpty()) favoritesList else favoritesList.filter { matchesSong(it, q) }

        val shownSongs = sortSongs(filteredSongs)
        val shownFavs = sortSongs(filteredFavs)

        val (adapter, listEmpty) = when (currentTab) {
            0 -> SongAdapter(shownSongs,
                onFavClick = { pos ->
                    shownSongs.getOrNull(pos)?.let { PlayerManager.toggleFav(it.favKey()) }
                    binding.recyclerView.adapter?.notifyItemChanged(pos)
                }) { playFrom(it, shownSongs) } to shownSongs.isEmpty()
            1 -> GroupAdapter(filteredAlbums) { playGroup(it) } to filteredAlbums.isEmpty()
            2 -> GroupAdapter(filteredArtists) { playGroup(it) } to filteredArtists.isEmpty()
            3 -> SongAdapter(shownFavs,
                onFavClick = { pos ->
                    shownFavs.getOrNull(pos)?.let { PlayerManager.toggleFav(it.favKey()) }
                    favoritesList = allSongs.filter { PlayerManager.favorites.value?.contains(it.favKey()) == true }
                    applyView()
                }) { playFrom(it, shownFavs) } to shownFavs.isEmpty()
            else -> null to true
        }
        binding.recyclerView.adapter = adapter

        showEmpty(listEmpty)
        if (listEmpty) {
            binding.tvEmpty.text = when {
                q.isNotEmpty() -> getString(R.string.search_empty)
                currentTab == 3 -> getString(R.string.empty_favorites)
                Prefs.musicTreeUri != null -> getString(R.string.dir_empty)
                else -> getString(R.string.empty_library)
            }
        }
    }

    private fun matchesSong(song: Song, q: String): Boolean =
        song.title.lowercase().contains(q) ||
                song.artist.lowercase().contains(q) ||
                song.album.lowercase().contains(q)

    /** 按当前排序方式对歌曲列表排序（标题/艺术家/专辑 升序，时长降序） */
    private fun sortSongs(list: List<Song>): List<Song> = when (sortMode) {
        1 -> list.sortedBy { it.artist.lowercase() }
        2 -> list.sortedBy { it.album.lowercase() }
        3 -> list.sortedByDescending { it.duration }
        else -> list.sortedBy { it.title.lowercase() }
    }

    /** 排序方式选择对话框（借鉴 Music Player GO / Auxio 的曲库排序） */
    private fun showSortDialog() {
        val options = listOf(
            R.string.sort_title to 0,
            R.string.sort_artist to 1,
            R.string.sort_album to 2,
            R.string.sort_duration to 3
        )
        val items = options.map { getString(it.first) }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.sort_by)
            .setSingleChoiceItems(items, sortMode) { dlg, which ->
                sortMode = options[which].second
                Prefs.sortModeOrdinal = sortMode
                dlg.dismiss()
                applyView()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun playFrom(index: Int, list: List<Song>) {
        if (index !in list.indices) return
        PlayerManager.playlist.clear()
        PlayerManager.playlist.addAll(list)
        PlayerManager.currentIndex = index
        PlayerControls.play(requireContext())
        (activity as? MainActivity)?.switchToNowPlaying()
    }

    private fun playGroup(songs: List<Song>) {
        // 单曲或少于 5 首直接播放
        if (songs.size <= 5) {
            playGroupDirect(songs)
            return
        }
        // 超过 5 首时弹出选择：播放全部 / 从某首开始
        val names = songs.mapIndexed { i, s -> "${i + 1}. ${s.title} — ${s.artist}" }
        val items = arrayOf("播放全部 (${songs.size} 首)") + names
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(songs.first().let { if (it.album != "未知专辑") it.album else it.artist })
            .setItems(items) { dlg, which ->
                when (which) {
                    0 -> playGroupDirect(songs)
                    else -> {
                        val idx = which - 1
                        PlayerManager.playlist.clear()
                        PlayerManager.playlist.addAll(songs)
                        PlayerManager.currentIndex = idx.coerceIn(0, songs.size - 1)
                        PlayerControls.play(requireContext())
                        (activity as? MainActivity)?.switchToNowPlaying()
                    }
                }
                dlg.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun playGroupDirect(songs: List<Song>) {
        PlayerManager.playlist.clear()
        PlayerManager.playlist.addAll(songs)
        PlayerManager.currentIndex = 0
        PlayerControls.play(requireContext())
        (activity as? MainActivity)?.switchToNowPlaying()
    }

    private fun showEmpty(empty: Boolean) {
        binding.tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (empty) View.GONE else View.VISIBLE
        // 隐藏加载指示器（如果有数据或空态，加载已经完成）
        binding.loadingView.visibility = View.GONE
    }
}
