# PureMusicPlayer ProGuard 规则（release 混淆+压缩）
# 原则：只保留 R8 无法自行推断的必需项，其余全由 R8 自动处理。

# 保持行号与源文件（便于 crash 日志定位）
-keepattributes SourceFile,LineNumberTable

# ViewBinding 生成的绑定类
-keep class * implements androidx.viewbinding.ViewBinding {
    *;
}

# PlayerService（前台服务，由 manifest/intent 反射启动）
-keep class com.puremusicplayer.player.PlayerService { *; }

# VisualizerView / LyricsView（自定义 View，由布局 XML 反射实例化）
-keep class com.puremusicplayer.ui.VisualizerView { <init>(...); }
-keep class com.puremusicplayer.ui.LyricsView { <init>(...); }

# 枚举值处理（PlayMode 等，但 PlayMode 已经在 PlayerManager 中被引用）

# Coil：保留图片加载相关的必要类
-dontwarn io.coil.**

# OkHttp：仅本地文件加载，不需要 publicsuffixes 域名表
-dontwarn okhttp3.internal.publicsuffix.**
-keepclassmembers class okhttp3.internal.publicsuffix.PublicSuffixDatabase {
    void readTheList(...);
}
