package com.cloner.multiinstance.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.cloner.multiinstance.CloneLauncherActivity
import com.cloner.multiinstance.model.CloneEntity

object ShortcutHelper {

    fun pinCloneShortcut(context: Context, clone: CloneEntity) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return
        val intent = Intent(context, CloneLauncherActivity::class.java).apply {
            action = "com.cloner.multiinstance.LAUNCH_CLONE"
            putExtra("clone_id", clone.id)
        }
        val info = ShortcutInfoCompat.Builder(context, "clone_${clone.id}")
            .setShortLabel(clone.displayName.take(25))
            .setLongLabel(clone.displayName)
            .setIcon(IconCompat.createWithBitmap(makeBadgedIcon(context, clone)))
            .setIntent(intent)
            .build()
        ShortcutManagerCompat.requestPinShortcut(context, info, null)
    }

    private fun makeBadgedIcon(context: Context, clone: CloneEntity): Bitmap {
        val size = 192
        val base = context.packageManager.getApplicationIcon(clone.sourcePackage)
            .toBitmap(size, size)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(base, 0f, 0f, null)
        val r = size * 0.26f
        val cx = size - r
        val cy = size - r
        canvas.drawCircle(
            cx, cy, r,
            Paint().apply { color = clone.badgeColor.toInt(); isAntiAlias = true }
        )
        canvas.drawText(
            clone.badgeNumber.toString(), cx, cy + r * 0.35f,
            Paint().apply {
                color = Color.WHITE
                textSize = r
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
                typeface = Typeface.DEFAULT_BOLD
            }
        )
        return out
    }
}
