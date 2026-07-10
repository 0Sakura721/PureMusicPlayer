# PureMusic

一款**轻量、本地优先**的 Android 音乐播放器。用原生 Kotlin + 极简 AndroidX 依赖打造，融合三款优秀播放器的设计理念，同时保持安装包精简、运行流畅。

> 适配 **Android 16（API 36）** 与低版本；仅打包 `armeabi-v7a` 与 `arm64-v8a` 两种 ABI。

---

## 功能

- **本地曲库扫描**：通过 `MediaStore` 读取设备音乐，按 歌曲 / 专辑 / 艺术家 分组浏览
- **播放控制**：播放 / 暂停 / 上一首 / 下一首 / 拖动进度；顺序、随机、单曲循环、列表循环
- **后台播放**：媒体播放前台服务，锁屏与控制中心可控，媒体通知常驻
- **歌词**：优先匹配同目录同名 `.lrc`，其次读取文件内嵌歌词——FLAC / OGG（Vorbis Comment `LYRICS`/`UNSYNCEDLYRICS`）、MP3（ID3v2 `USLT`）、M4A / MP4（`©lyr` 原子）、WAV（RIFF LIST/INFO 的 `lyr`/`ILT` 块）；当前行高亮 + 平滑滚动；无时间戳的纯文本歌词逐行兜底展示。解析为纯 Kotlin 字节解析，零额外依赖
- **可视化**：`Visualizer` 同时采集 FFT 与波形数据，纯 `Canvas` 绘制三种样式——**条形**（频谱柱）、**圆形**（环绕径向频谱）、**波形**（居中音波线）；可在设置中开关与切换样式
- **音乐目录**：在「设置 → 曲库」用系统目录选择器限定仅扫描指定文件夹（SAF 文档树 + 持久化权限，重启后依然有效），清空即恢复扫描整个媒体库
- **明暗主题**：设置中可选 **跟随系统 / 浅色 / 深色**，基于 `AppCompatDelegate` 即时切换并记忆
- **DIY 个性强调色**：设置中自选播放页强调色（经典紫 / 天青蓝 / 薄荷绿 / 活力橙 / 樱花粉 / 烈焰红 或保留默认）；用户选定后优先于动态取色
- **动态主题**：用 `Palette` 从专辑封面取主色渲染播放页强调色（未手动设定 DIY 强调色时生效）
- **播放队列**：在“正在播放”页呼出底部弹层，查看队列并点击切歌；支持拖拽重排与滑动删除（自动同步当前曲目与播放服务）
- **预测性返回（Android 13+）**：从“正在播放 / 设置”返回自动回到曲库，曲库页按系统默认退出（Android 16 合规）
- **主页迷你播放器**：底部常驻迷你播放条，随时预览并跳转到「正在播放」
- **自适应播放页**：封面页按屏幕高度自适应（封面用 `layout_weight` 伸缩、限高 320dp），任意设备都不出现上下滑动
- **极简 UI**：浅色干净背景、清晰列表、大播放页、底部导航、控制按钮主题着色保证可见

---

## 技术栈

- 语言：**Kotlin**
- 构建：**Gradle Kotlin DSL**，Android Gradle Plugin 8.9，compileSdk / targetSdk = 36，minSdk = 21
- 播放内核：框架 `MediaPlayer`（零额外依赖，覆盖 MP3 / AAC / FLAC / WAV）
- 可视化：`android.media.audiofx.Visualizer`（按音频会话取输出，无需麦克风权限）
- 媒体会话：框架原生 `android.media.session.MediaSession`（锁屏 / 控制中心 / 媒体键；API 21+ 自带，零额外依赖）
- 依赖精简：仅 AndroidX + Material + `palette-ktx` + `coil`（专辑封面异步加载）

不引入 ExoPlayer / Flutter / React Native 等重型框架，保持“精简”。

---

## 构建与运行

### 用 Android Studio（推荐）
1. 克隆仓库：`git clone https://github.com/0Sakura721/PureMusicPlayer.git`
2. 用 Android Studio 打开项目，等待 Gradle 同步
3. 连接设备或启动模拟器（需 API 21+）
4. 点击 ▶ Run，或 `Build → Build Bundle(s) / APK(s) → Build APK(s)`

### 用命令行
确保已安装 Android SDK（含 platform `android-36`、build-tools）与 JDK 17：

```bash
./gradlew assembleDebug      # 生成 debug APK
./gradlew assembleRelease    # 生成开启混淆/压缩的 release APK
```

> 首次构建会自动下载 Gradle 与依赖。若在受限网络/无 SDK 环境，请用 Android Studio 打开以自动配置。

### 自动构建（GitHub Actions）
仓库已配置 `.github/workflows/build-apk.yml`：每次向 `main` 推送（或手动 `workflow_dispatch`）会自动：
检出 → 安装 JDK 17 + Android SDK（API 36）→ Gradle 构建 `assembleDebug` 与 `assembleRelease` → 上传 APK 产物。

获取 APK：进入仓库 **Actions → 最近一次 Build APK 运行 → 右侧 Artifacts `puremusic-apks`** 下载。
- `app-debug.apk`：已用默认 debug keystore 签名，**可直接安装到 armeabi-v7a / arm64-v8a 设备**。
- `app-release-unsigned.apk`：已开启混淆与资源压缩（约 2.2MB），需自行签名后才能安装。

若要让 CI 直接产出**可安装的签名 release**，在仓库 `Settings → Secrets` 中添加：
`SIGNING_KEY`（base64 编码的 keystore，命令 `base64 -w0 keystore.jks`）、
`KEY_ALIAS`、`KEY_PASSWORD`、`STORE_PASSWORD`，并在 workflow 中追加签名步骤即可。

### 权限说明
- 读取音频：Android 13+ 为 `READ_MEDIA_AUDIO`，低版本为 `READ_EXTERNAL_STORAGE`（应用内动态申请）
- 显示媒体通知：Android 13+ 需 `POST_NOTIFICATIONS`
- 播放后台音乐：需 `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK`

---

## 目录结构

```
app/src/main/
├── AndroidManifest.xml
├── java/com/puremusicplayer/
│   ├── MainActivity.kt
│   ├── data/        # Song 模型、MediaStore 扫描、.lrc 匹配
│   ├── player/      # PlayerService、PlayerManager、LyricsParser、PlayerControls
│   ├── ui/          # Library / NowPlaying / Settings Fragment、适配器、LyricsView、VisualizerView
│   └── util/        # 权限、偏好、时间格式化
└── res/             # 布局、矢量图标、主题、mipmap 启动图标
```

---

## 许可证

本项目原创代码采用 **MIT License**，详见 [LICENSE](LICENSE)。
