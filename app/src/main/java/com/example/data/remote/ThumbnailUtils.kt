package com.example.data.remote

object ThumbnailUtils {
    /**
     * Upgrades low-res YouTube, Google User Content, and YouTube Music thumbnails to high resolution.
     * - For lh3.googleusercontent.com or yt3.ggpht.com: replaces size params like =w60-h60 with =w544-h544-l90-rj.
     * - For i.ytimg.com/vi/<id>/...: upgrades default.jpg / mqdefault.jpg / sddefault.jpg to hqdefault.jpg or maxresdefault.jpg.
     */
    fun upgradeThumbnailUrl(url: String?): String {
        return InnerTubeResolver.upgradeThumbnailUrl(url)
    }
}
