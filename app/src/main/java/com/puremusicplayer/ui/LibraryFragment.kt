package com.puremusicplayer.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
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

class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!

    private var allSongs = emptyList<Song>()
    private var albums = emptyList<MusicGroup>()
    private var artists = emptyList<MusicGroup>()

    private var query = ""
    private var currentTab = 0

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) loadLibrary() else showEmpty(true)
    }

    private val pickDirectory = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        // 持久化权限，重启后仍能访问该目录
        requireContext().contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        )
        val name = DocumentFile.fromTreeUri(requireContext(), uri)?.name ?: uri.lastPathSegment
        Prefs.musicTreeUri = uri.toString()
        Prefs.musicDirName = name
        updateDirChip()
        loadLibrary()
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
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentTab = tab?.position ?: 0
                applyView()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) = Unit
            override fun onTabReselected(tab: TabLayout.Tab?) = Unit
        })

        setupSearch()
        setupDirectory()

        if (Permissions.hasAudioPermission(requireContext())) loadLibrary()
        else requestPermission.launch(Permissions.audioPermissionName())
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

    // ---------- 音乐目录选择 ----------
    private fun setupDirectory() {
        updateDirChip()
        binding.btnPickDir.setOnClickListener {
            pickDirectory.launch(null)
        }
        binding.dirClear.setOnClickListener {
            Prefs.musicTreeUri = null
            Prefs.musicDirName = null
            updateDirChip()
            loadLibrary()
        }
    }

    private fun updateDirChip() {
        val name = Prefs.musicDirName
        if (name.isNullOrEmpty()) {
            binding.dirChip.visibility = View.GONE
        } else {
            binding.tvDirName.text = name
            binding.dirChip.visibility = View.VISIBLE
        }
    }

    private fun loadLibrary() {
        val treeUri = Prefs.musicTreeUri?.let { Uri.parse(it) }
        try {
            allSongs = MusicRepository.loadSongs(requireContext(), treeUri)
        } catch (e: Exception) {
            // 扫描失败（如 Android 16 上 MediaStore 行为差异）时安全降级为空列表，而非崩溃
            allSongs = emptyList()
        }
        PlayerManager.playlist.clear()
        PlayerManager.playlist.addAll(allSongs)
        PlayerManager.playMode = PlayMode.values().getOrElse(Prefs.playModeOrdinal) { PlayMode.REPEAT_ALL }

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

    /** 根据当前 tab 与搜索词刷新列表与空态 */
    private fun applyView() {
        val q = query.lowercase()

        val filteredSongs = if (q.isEmpty()) allSongs else allSongs.filter { matchesSong(it, q) }
        val filteredAlbums = if (q.isEmpty()) albums else albums.filter { it.title.lowercase().contains(q) }
        val filteredArtists = if (q.isEmpty()) artists else artists.filter { it.title.lowercase().contains(q) }

        val (adapter, listEmpty) = when (currentTab) {
            0 -> SongAdapter(filteredSongs) { playFrom(it, filteredSongs) } to filteredSongs.isEmpty()
            1 -> GroupAdapter(filteredAlbums) { playGroup(it) } to filteredAlbums.isEmpty()
            2 -> GroupAdapter(filteredArtists) { playGroup(it) } to filteredArtists.isEmpty()
            else -> null to true
        }
        binding.recyclerView.adapter = adapter

        showEmpty(listEmpty)
        if (listEmpty) {
            binding.tvEmpty.text = when {
                q.isNotEmpty() -> getString(R.string.search_empty)
                Prefs.musicTreeUri != null -> getString(R.string.dir_empty)
                else -> getString(R.string.empty_library)
            }
        }
    }

    private fun matchesSong(song: Song, q: String): Boolean =
        song.title.lowercase().contains(q) ||
                song.artist.lowercase().contains(q) ||
                song.album.lowercase().contains(q)

    private fun playFrom(index: Int, list: List<Song>) {
        if (index !in list.indices) return
        PlayerManager.playlist.clear()
        PlayerManager.playlist.addAll(list)
        PlayerManager.currentIndex = index
        PlayerControls.play(requireContext())
        (activity as? MainActivity)?.switchToNowPlaying()
    }

    private fun playGroup(songs: List<Song>) {
        PlayerManager.playlist.clear()
        PlayerManager.playlist.addAll(songs)
        PlayerManager.currentIndex = 0
        PlayerControls.play(requireContext())
        (activity as? MainActivity)?.switchToNowPlaying()
    }

    private fun showEmpty(empty: Boolean) {
        binding.tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (empty) View.GONE else View.VISIBLE
    }
}
