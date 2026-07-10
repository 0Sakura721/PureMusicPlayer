package com.puremusicplayer.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
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
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) = selectTab(tab?.position ?: 0)
            override fun onTabUnselected(tab: TabLayout.Tab?) = Unit
            override fun onTabReselected(tab: TabLayout.Tab?) = Unit
        })

        if (Permissions.hasAudioPermission(requireContext())) loadLibrary()
        else requestPermission.launch(Permissions.audioPermissionName())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun loadLibrary() {
        allSongs = MusicRepository.loadSongs(requireContext())
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

        showEmpty(allSongs.isEmpty())
        selectTab(binding.tabLayout.selectedTabPosition)
    }

    private fun selectTab(index: Int) {
        val adapter = when (index) {
            0 -> SongAdapter(allSongs) { playFromAll(it) }
            1 -> GroupAdapter(albums) { playGroup(it) }
            2 -> GroupAdapter(artists) { playGroup(it) }
            else -> null
        }
        binding.recyclerView.adapter = adapter
    }

    private fun playFromAll(index: Int) {
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
