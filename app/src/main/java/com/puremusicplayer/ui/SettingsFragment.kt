package com.puremusicplayer.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.puremusicplayer.databinding.FragmentSettingsBinding
import com.puremusicplayer.util.Prefs

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Prefs.init(requireContext())

        binding.switchVisualizer.isChecked = Prefs.visualizerEnabled
        binding.switchLyrics.isChecked = Prefs.lyricsAnimEnabled
        binding.switchTheme.isChecked = Prefs.dynamicThemeEnabled

        binding.switchVisualizer.setOnCheckedChangeListener { _, v ->
            Prefs.visualizerEnabled = v
        }
        binding.switchLyrics.setOnCheckedChangeListener { _, v ->
            Prefs.lyricsAnimEnabled = v
        }
        binding.switchTheme.setOnCheckedChangeListener { _, v ->
            Prefs.dynamicThemeEnabled = v
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
