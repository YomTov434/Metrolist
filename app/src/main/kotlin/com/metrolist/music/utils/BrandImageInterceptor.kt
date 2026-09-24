/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.size.Dimension
import com.metrolist.music.R

/**
 * Global Coil interceptor: replaces every requested image (song/album/artist artwork,
 * banners, thumbnails, etc.) with a local brand asset, regardless of the original request
 * data or which code issued it. No network artwork is ever fetched or rendered.
 *
 * Small targets (list rows, mini player) get the compact monogram; larger targets
 * (full player, headers) get the full logo. Light/dark variants are resolved
 * automatically via drawable/drawable-night resource qualifiers.
 */
class BrandImageInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val widthPx = when (val w = chain.size.width) {
            is Dimension.Pixels -> w.px
            Dimension.Undefined -> Int.MAX_VALUE
        }
        val brandRes = if (widthPx in 1 until SMALL_TARGET_THRESHOLD_PX) {
            R.drawable.ic_brand_monogram
        } else {
            R.drawable.ic_brand_logo_full
        }
        val request = chain.request
            .newBuilder()
            .data(brandRes)
            .build()
        return chain.withRequest(request).proceed()
    }

    companion object {
        private const val SMALL_TARGET_THRESHOLD_PX = 400
    }
}
