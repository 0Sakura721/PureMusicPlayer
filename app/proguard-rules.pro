# PureMusicPlayer ProGuard 规则（release 混淆保留）
# 保持 ViewBinding / 数据类 / 播放服务
-keepattributes *Annotation*,SourceFile,LineNumberTable
-keep class com.puremusicplayer.** { *; }
-keep interface com.puremusicplayer.** { *; }

# MediaPlayer / Visualizer / MediaSession 属 Android 框架，无需特别处理
