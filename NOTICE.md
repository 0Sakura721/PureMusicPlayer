# NOTICE — 设计灵感与许可证归属

**PureMusic** 是一款原创的轻量 Android 本地音乐播放器。其代码均为原创实现，
未直接复制下列项目的源代码。下列项目仅作为**设计思路**的借鉴来源：

| 项目 | 仓库 | 许可证 | 借鉴的设计思路 |
|---|---|---|---|
| Salt Player | https://github.com/Moriafly/SaltPlayerSource | MIT | 极简本地音乐 UX、干净列表与播放页、Material 化设计语言 |
| folia-major | https://github.com/chthollyphile/folia-major | AGPL-3.0 | 全屏歌词的当前行高亮与平滑滚动、按专辑封面自动取色生成主题 |
| Mineradio | https://github.com/XxHuberrr/Mineradio | GPL-3.0 | 音频反应式视觉舞台（FFT 频谱可视化）、歌词与视觉联动的氛围感 |

## 许可说明

- 本项目（PureMusic）的源代码以 **MIT License** 发布（见 `LICENSE`）。
- 由于 folia-major（AGPL-3.0）与 Mineradio（GPL-3.0）均为强 copyleft 许可，
  本项目**未复制其任何受保护代码**，仅以原创方式实现相似的用户体验，
  因此本项目整体**不**承担 AGPL / GPL 的传染义务。
- Salt Player 采用 MIT 许可，其设计理念可自由借鉴，已在上方注明来源。
- 各依赖库（AndroidX、Material Components、Coil、Palette）遵循各自的开放源代码许可。

若上述项目的作者认为本项目存在需要调整的归属或许可事宜，欢迎通过仓库 Issue 联系。
