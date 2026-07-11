package com.puremusicplayer

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import java.io.File

/**
 * Application：统一配置 Coil 磁盘缓存上限，防止运行时膨胀。
 * Application：定期清理专辑封面缓存目录，防止无限制增长。
 */
class PureMusicApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        // 应用启动时清理过旧的专辑封面缓存（保留 48 小时内的）
        trimArtCache()
    }

    /** 配置 Coil：磁盘上限 5MB，内存缓存保持默认 */
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .diskCache {
                DiskCache.Builder()
                    .directory(File(cacheDir, "coil_cache"))
                    .maxSizeBytes(5 * 1024 * 1024)
                    .build()
            }
            .build()
    }

    /** 清理专辑封面缓存：仅保留最近 48 小时写入的文件 */
    private fun trimArtCache() {
        try {
            val dir = File(cacheDir, "art")
            if (!dir.exists()) return
            val cutoff = System.currentTimeMillis() - 48 * 3600_000L
            dir.listFiles()?.forEach { f ->
                if (f.lastModified() < cutoff) f.delete()
            }
        } catch (_: Exception) {
            // 清理失败不影响主流程
        }
    }
}
