package com.puremusicplayer.ui

import android.content.DialogInterface
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.puremusicplayer.R
import com.puremusicplayer.databinding.FragmentSettingsBinding
import com.puremusicplayer.player.PlayerControls
import com.puremusicplayer.util.Prefs

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    /** 主题模式：跟随系统 / 浅色 / 深色 / 纯黑（AMOLED） */
    private val themeOptions = listOf(
        R.string.theme_follow_system to 0,
        R.string.theme_light to 1,
        R.string.theme_dark to 2,
        R.string.theme_black to 3
    )

    /** 可视化样式：条形 / 圆形 / 波形 */
    private val visOptions = listOf(
        R.string.vis_style_bars to 0,
        R.string.vis_style_circle to 1,
        R.string.vis_style_wave to 2
    )

    /** DIY 个性强调色（含「默认」）；-1 表示使用品牌默认色 */
    private fun accentOptions(): List<Pair<String, Int>> = listOf(
        getString(R.string.accent_default) to -1,
        "经典紫" to Color.parseColor("#6C5CE7"),
        "天青蓝" to Color.parseColor("#0984E3"),
        "薄荷绿" to Color.parseColor("#00B894"),
        "活力橙" to Color.parseColor("#E17055"),
        "樱花粉" to Color.parseColor("#E84393"),
        "烈焰红" to Color.parseColor("#D63031")
    )

    /** 音乐目录选择器（SAF 文档树） */
    private val pickDirectory = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        // 持久化权限，应用重启后仍能访问该目录
        requireContext().contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        )
        val name = DocumentFile.fromTreeUri(requireContext(), uri)?.name ?: uri.lastPathSegment
        Prefs.musicTreeUri = uri.toString()
        Prefs.musicDirName = name
        updateDirChip()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Prefs.init(requireContext())

        // 开关：复用既有偏好
        binding.switchVisualizer.isChecked = Prefs.visualizerEnabled
        binding.switchLyrics.isChecked = Prefs.lyricsAnimEnabled
        binding.switchTheme.isChecked = Prefs.dynamicThemeEnabled
        binding.switchUnplug.isChecked = Prefs.pauseOnUnplug

        binding.switchVisualizer.setOnCheckedChangeListener { _, v ->
            Prefs.visualizerEnabled = v
            updatePreview()
        }
        binding.switchLyrics.setOnCheckedChangeListener { _, v -> Prefs.lyricsAnimEnabled = v }
        binding.switchTheme.setOnCheckedChangeListener { _, v -> Prefs.dynamicThemeEnabled = v }
        binding.switchUnplug.setOnCheckedChangeListener { _, v -> Prefs.pauseOnUnplug = v }

        // 均衡器
        binding.switchEq.isChecked = Prefs.equalizerEnabled
        binding.switchEq.setOnCheckedChangeListener { _, v ->
            Prefs.equalizerEnabled = v
            PlayerControls.toggleEq(requireContext(), v)
        }
        binding.rowEqualizer.setOnClickListener { showEqPresetDialog() }
        refreshEqSummary()

        // 外观：主题模式 / 个性强调色
        binding.rowTheme.setOnClickListener { showThemeDialog() }
        binding.rowAccent.setOnClickListener { showAccentDialog() }

        // 播放：可视化样式
        binding.rowVisStyle.setOnClickListener { showVisStyleDialog() }

        // 曲库：目录选择
        binding.btnPickDir.setOnClickListener { pickDirectory.launch(null) }
        binding.dirClear.setOnClickListener {
            Prefs.musicTreeUri = null
            Prefs.musicDirName = null
            updateDirChip()
        }

        // 初始化摘要与色块
        refreshThemeSummary()
        refreshVisStyleSummary()
        refreshSwatch()
        updateDirChip()
        setupPreview()
    }

    // ---------- 可视化实时预览（让设置项即时可见，避免“空壳”） ----------
    private fun previewAccent(): Int =
        if (Prefs.accentColor >= 0) Prefs.accentColor
        else androidx.core.content.ContextCompat.getColor(requireContext(), R.color.brand_primary)

    private fun setupPreview() {
        binding.visPreview.setStyle(Prefs.visualizerStyle)
        binding.visPreview.setColor(previewAccent())
        updatePreview()
    }

    private fun updatePreview() {
        if (Prefs.visualizerEnabled) {
            binding.visPreview.visibility = View.VISIBLE
            binding.visPreview.setStyle(Prefs.visualizerStyle)
            binding.visPreview.setColor(previewAccent())
            binding.visPreview.startPreview()
        } else {
            binding.visPreview.stopPreview()
            binding.visPreview.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        binding.visPreview.stopPreview()
        super.onDestroyView()
        _binding = null
    }

    // ---------- 主题模式 ----------
    private fun showThemeDialog() {
        val items = themeOptions.map { getString(it.first) }.toTypedArray()
        val current = Prefs.themeMode.coerceIn(0, 3)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.theme_mode)
            .setSingleChoiceItems(items, current, DialogInterface.OnClickListener { dlg, which ->
                Prefs.themeMode = themeOptions[which].second
                refreshThemeSummary()
                applyThemeMode()
                dlg.dismiss()
                // 重新创建 Activity 让明暗主题立即生效
                requireActivity().recreate()
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyThemeMode() {
        val mode = when (Prefs.themeMode) {
            1 -> AppCompatDelegate.MODE_NIGHT_NO
            2 -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    private fun refreshThemeSummary() {
        val idx = Prefs.themeMode.coerceIn(0, 3)
        binding.tvThemeSummary.text = getString(themeOptions[idx].first)
    }

    // ---------- DIY 个性强调色 ----------
    private fun showAccentDialog() {
        val options = accentOptions()
        val items = options.map { it.first }.toTypedArray()
        val current = options.indexOfFirst { it.second == Prefs.accentColor }
            .let { if (it < 0) 0 else it }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.accent_color)
            .setSingleChoiceItems(items, current, DialogInterface.OnClickListener { dlg, which ->
                Prefs.accentColor = options[which].second
                refreshSwatch()
                updatePreview()
                dlg.dismiss()
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refreshSwatch() {
        val c = if (Prefs.accentColor >= 0) Prefs.accentColor
        else requireContext().let {
            androidx.core.content.ContextCompat.getColor(it, R.color.brand_primary)
        }
        binding.swatch.background.setTint(c)
    }

    // ---------- 可视化样式 ----------
    private fun showVisStyleDialog() {
        val items = visOptions.map { getString(it.first) }.toTypedArray()
        val current = Prefs.visualizerStyle.coerceIn(0, 2)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.visualizer_style)
            .setSingleChoiceItems(items, current, DialogInterface.OnClickListener { dlg, which ->
                Prefs.visualizerStyle = visOptions[which].second
                refreshVisStyleSummary()
                updatePreview()
                dlg.dismiss()
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refreshVisStyleSummary() {
        val idx = Prefs.visualizerStyle.coerceIn(0, 2)
        binding.tvVisStyleSummary.text = getString(visOptions[idx].first)
    }

    // ---------- 均衡器 ----------
    private fun showEqPresetDialog() {
        val presets = com.puremusicplayer.player.EqualizerHelper.Preset.values()
        val items = presets.map { it.label }.toTypedArray()
        val current = Prefs.equalizerPreset.coerceIn(0, presets.size - 1)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.eq_preset)
            .setSingleChoiceItems(items, current, DialogInterface.OnClickListener { dlg, which ->
                Prefs.equalizerPreset = which
                PlayerControls.setEqPreset(requireContext(), which)
                refreshEqSummary()
                dlg.dismiss()
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refreshEqSummary() {
        val presets = com.puremusicplayer.player.EqualizerHelper.Preset.values()
        val idx = Prefs.equalizerPreset.coerceIn(0, presets.size - 1)
        binding.tvEqSummary.text = if (Prefs.equalizerEnabled) presets[idx].label else getString(R.string.equalizer_summary)
    }

    // ---------- 音乐目录 ----------
    private fun updateDirChip() {
        val name = Prefs.musicDirName
        if (name.isNullOrEmpty()) {
            binding.dirChip.visibility = View.GONE
        } else {
            binding.tvDirName.text = name
            binding.dirChip.visibility = View.VISIBLE
        }
    }
}
