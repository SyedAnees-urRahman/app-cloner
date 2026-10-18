package com.cloner.multiinstance.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object IconCache {
    private val mem = LruCache<String, ImageBitmap>(64)

    suspend fun load(context: Context, packageName: String): ImageBitmap? =
        withContext(Dispatchers.IO) {
            mem.get(packageName) ?: runCatching {
                context.packageManager.getApplicationIcon(packageName)
                    .toBitmap(144, 144)
                    .asImageBitmap()
                    .also { mem.put(packageName, it) }
            }.getOrNull()
        }
}

fun Drawable.toBitmap(width: Int, height: Int): Bitmap {
    val w = if (width > 0) width else 96
    val h = if (height > 0) height else 96
    if (this is BitmapDrawable && bitmap != null && bitmap.width == w && bitmap.height == h) {
        return bitmap
    }
    return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
        val canvas = Canvas(bmp)
        setBounds(0, 0, w, h)
        draw(canvas)
    }
}
