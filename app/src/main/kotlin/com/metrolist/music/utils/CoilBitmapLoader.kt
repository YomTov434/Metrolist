/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.BitmapLoader
import com.google.common.util.concurrent.ListenableFuture
import com.metrolist.music.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.future
import timber.log.Timber

/**
 * Brand policy: notification, lock screen, Android Auto and widget artwork must never show
 * real song/album/artist images. This loader ignores whatever artwork data or URI media3
 * hands it and always returns the local brand bitmap instead — independent of Coil's global
 * interceptor (BrandImageInterceptor), since [decodeBitmap] here handles embedded artwork
 * bytes that never go through Coil at all.
 */
class CoilBitmapLoader(
    private val context: Context,
    private val scope: CoroutineScope,
) : BitmapLoader {
    override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

    private fun createFallbackBitmap(): Bitmap = createBitmap(64, 64)

    private val brandBitmap: Bitmap by lazy { renderBrandBitmap() }

    private fun renderBrandBitmap(): Bitmap =
        try {
            val drawable = ContextCompat.getDrawable(context, R.drawable.ic_brand_logo_full)
            if (drawable == null) {
                createFallbackBitmap()
            } else {
                val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 512
                val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 512
                val bitmap = createBitmap(width, height)
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, width, height)
                drawable.draw(canvas)
                bitmap
            }
        } catch (e: Exception) {
            Timber.tag("CoilBitmapLoader").w(e, "Failed to render brand bitmap")
            createFallbackBitmap()
        }

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        scope.future(Dispatchers.IO) { brandBitmap }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
        scope.future(Dispatchers.IO) { brandBitmap }

    override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap> =
        scope.future(Dispatchers.IO) { brandBitmap }
}
