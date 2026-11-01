// The two binary bodies: a decoded bitmap, and a rendered PDF page stack.
// SPDX-License-Identifier: MIT
//
// Mirror source: `PreviewSurface.swift:451`–`:487` of iOS PR #15 — `imageBody`
// (`UIImage(contentsOfFile:)` + `scaledToFit`) and `pdfBody` (`PDFKitView`). Both
// are cheap on Android and neither touches the WebView invariant:
//
//   * `BitmapFactory` with a sampled decode (`inJustDecodeBounds` first) is the
//     `UIImage(contentsOfFile:)` equivalent. The sample step is not optional on a
//     phone: a 12 MP photo is 48 MB as ARGB_8888 and the preview only needs screen
//     pixels.
//   * `PdfRenderer` is the platform's own PDF rasterizer (no dependency, no
//     WebView). Pages are rendered up to the screen width on an IO dispatcher and
//     laid out lazily; a long manual is capped at [MAX_PDF_PAGES] with the count
//     stated, because 900 full-screen bitmaps would OOM long before they scrolled.
//
// Both decoders report failure as a fact ("无法显示图片" / "无法打开 PDF" with the
// baseline's own hint) instead of an empty frame — a file that exists and cannot be
// decoded is a *different* answer from a file that is gone.
package com.phonedsh.ui.surface.preview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.phonedsh.ui.UiStrings
import com.phonedsh.ui.page.UiPageTags
import com.phonedsh.ui.theme.LocalUiTokens
import com.phonedsh.ui.theme.LocalUiType
import java.io.File
import java.io.IOException
import java.nio.file.Path
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The image body: one decoded bitmap, `ContentScale.Fit`, on the page fill.
 *
 * @param file the resolved file, or `null` while the route has not resolved it yet.
 */
@Composable
internal fun ImageBody(file: Path?) {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    if (file == null) {
        PreviewCenteredLine(UiStrings.FILES_PREVIEW_LOADING, UiPageTags.PREVIEW_IMAGE)
        return
    }
    var bitmap by remember(file) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(file) { mutableStateOf(false) }
    LaunchedEffect(file) {
        val decoded = withContext(Dispatchers.IO) { decodeSampled(file.toFile(), MAX_IMAGE_DIMENSION) }
        bitmap = decoded?.asImageBitmap()
        failed = decoded == null
    }
    when {
        bitmap != null -> Image(
            bitmap = bitmap!!,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .testTag(UiPageTags.PREVIEW_IMAGE)
                .padding(
                    horizontal = tokens.geometry.pageOuterMarginH,
                    vertical = tokens.spacing.badgeInsetV,
                ),
        )

        failed -> Column(
            modifier = Modifier
                .fillMaxSize()
                .testTag(UiPageTags.PREVIEW_IMAGE)
                .padding(
                    horizontal = tokens.geometry.pageOuterMarginH,
                    vertical = tokens.spacing.cardInset,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(tokens.spacing.iconText),
        ) {
            BasicText(
                text = UiStrings.PREVIEW_IMAGE_FAILED,
                style = type.headline.copy(
                    color = tokens.colors.primary,
                    textAlign = TextAlign.Center,
                ),
            )
            BasicText(
                text = UiStrings.PREVIEW_IMAGE_FAILED_HINT,
                style = type.secondary.copy(
                    color = tokens.colors.secondary,
                    textAlign = TextAlign.Center,
                ),
            )
        }

        else -> PreviewCenteredLine(UiStrings.FILES_PREVIEW_LOADING, UiPageTags.PREVIEW_IMAGE)
    }
}

/**
 * The PDF body: a lazy vertical stack of rendered pages.
 *
 * @param file the resolved file, or `null` while the route has not resolved it yet.
 */
@Composable
internal fun PdfBody(file: Path?) {
    val tokens = LocalUiTokens.current
    if (file == null) {
        PreviewCenteredLine(UiStrings.PREVIEW_PDF_RENDERING, UiPageTags.PREVIEW_PDF)
        return
    }
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    // The render width is the screen width minus the page's own two margins, so a
    // page is never rasterized larger than it can be shown.
    val targetWidth = with(density) {
        (configuration.screenWidthDp.dp - tokens.geometry.pageOuterMarginH * 2).toPx().toInt()
    }.coerceAtLeast(MIN_PDF_WIDTH_PX)
    val background = tokens.colors.selector.toArgb()
    var pages by remember(file) { mutableStateOf<PdfPages?>(null) }
    LaunchedEffect(file, targetWidth) {
        pages = withContext(Dispatchers.IO) { renderPdf(file.toFile(), targetWidth, background) }
    }
    val rendered = pages
    when {
        rendered == null -> PreviewCenteredLine(UiStrings.PREVIEW_PDF_RENDERING, UiPageTags.PREVIEW_PDF)

        rendered.pages.isEmpty() -> Column(
            modifier = Modifier
                .fillMaxSize()
                .testTag(UiPageTags.PREVIEW_PDF)
                .padding(
                    horizontal = tokens.geometry.pageOuterMarginH,
                    vertical = tokens.spacing.cardInset,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(tokens.spacing.iconText),
        ) {
            BasicText(
                text = UiStrings.PREVIEW_PDF_FAILED,
                style = LocalUiType.current.headline.copy(
                    color = tokens.colors.primary,
                    textAlign = TextAlign.Center,
                ),
            )
            BasicText(
                text = UiStrings.PREVIEW_PDF_FAILED_HINT,
                style = LocalUiType.current.secondary.copy(
                    color = tokens.colors.secondary,
                    textAlign = TextAlign.Center,
                ),
            )
        }

        else -> LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag(UiPageTags.PREVIEW_PDF)
                .padding(horizontal = tokens.geometry.pageOuterMarginH),
            verticalArrangement = Arrangement.spacedBy(tokens.spacing.badgeInsetV),
        ) {
            items(items = rendered.pages) { page ->
                Image(
                    bitmap = page,
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (rendered.total > rendered.pages.size) {
                item {
                    // Android-only truth: the body stops at the cap and says so.
                    BasicText(
                        text = UiStrings.PREVIEW_NOTICE_PDF_CAPPED.format(
                            rendered.pages.size,
                            rendered.total,
                        ),
                        style = LocalUiType.current.caption.copy(color = tokens.colors.secondary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = tokens.spacing.badgeInsetV),
                    )
                }
            }
        }
    }
}

/** One rendered document: its first pages, the total count, and the background. */
private data class PdfPages(val pages: List<ImageBitmap>, val total: Int)

/**
 * Rasterizes up to [maxPages] pages at [targetWidthPx], or an empty list on failure.
 *
 * `PdfRenderer` is used and closed inside this one call: the platform requires the
 * descriptor to stay open while the renderer lives, and the renderer to be touched
 * from the thread that opened it — both are what the `use` blocks guarantee.
 */
private fun renderPdf(file: File, targetWidthPx: Int, background: Int): PdfPages? = try {
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            val total = renderer.pageCount
            val count = min(total, MAX_PDF_PAGES)
            val pages = (0 until count).mapNotNull { index ->
                renderer.openPage(index).use { page ->
                    if (page.width <= 0 || page.height <= 0) return@use null
                    val scale = targetWidthPx.toFloat() / page.width
                    val width = max(MIN_PDF_WIDTH_PX, (page.width * scale).toInt())
                    val height = max(MIN_PDF_WIDTH_PX, (page.height * scale).toInt())
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    // The page's own paper is the app's surface fill (a token), so a
                    // dark page cannot end up with a white gutter.
                    bitmap.eraseColor(background)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap.asImageBitmap()
                }
            }
            PdfPages(pages = pages, total = total)
        }
    }
} catch (_: IOException) {
    null
} catch (_: IllegalStateException) {
    // `PdfRenderer` throws this for a malformed or password-protected document.
    null
}

/**
 * Decodes [file] with a power-of-two sample so neither side exceeds [maxDimension].
 *
 * Returns `null` for anything the platform cannot decode — the caller turns that
 * into 无法显示图片, which is the baseline's own `imageBody` failure card.
 */
private fun decodeSampled(file: File, maxDimension: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
    }
    return try {
        BitmapFactory.decodeFile(file.path, options)
    } catch (_: OutOfMemoryError) {
        null
    }
}

/** The smallest power of two that brings both sides under [maxDimension]. */
internal fun sampleSize(width: Int, height: Int, maxDimension: Int): Int {
    var sample = 1
    while (width / (sample * 2) >= maxDimension && height / (sample * 2) >= maxDimension) {
        sample *= 2
    }
    // A single side can still be over the bound when the image is a panorama: halve
    // until both fit, so ``4096 × 64`` does not allocate 8 MB for nothing.
    while (width / sample > maxDimension || height / sample > maxDimension) {
        sample *= 2
    }
    return max(sample, 1)
}

/** The longest side a decoded image keeps; the rest is sampled away. */
private const val MAX_IMAGE_DIMENSION: Int = 2048

/** How many PDF pages are rasterized before the body states the cap. */
private const val MAX_PDF_PAGES: Int = 12

/** The narrowest render target, so a tiny screen still yields a readable page. */
private const val MIN_PDF_WIDTH_PX: Int = 320
