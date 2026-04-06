package com.htmlwidget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

object HtmlRenderer {

    /**
     * HTML'yi render eder. Sadece (scrollX, scrollY) noktasından başlayarak
     * (widthPx x heightPx) boyutunda bir kesit alır — başka hiçbir şey görünmez.
     */
    fun render(
        ctx: Context,
        uri: String,
        widthPx: Int,
        heightPx: Int,
        scrollX: Int,
        scrollY: Int,
        zoom: Int,
        delayMs: Long,
        onDone: (Bitmap?) -> Unit
    ) {
        Handler(Looper.getMainLooper()).post {
            try {
                // WebView, scroll konumunu da kapsayacak kadar büyük olmalı
                val renderW = (widthPx + scrollX + 100).coerceAtLeast(widthPx)
                val renderH = (heightPx + scrollY + 100).coerceAtLeast(heightPx)

                val wv = WebView(ctx)
                wv.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(renderW, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(renderH, android.view.View.MeasureSpec.EXACTLY)
                )
                wv.layout(0, 0, renderW, renderH)
                wv.setBackgroundColor(Color.WHITE)
                wv.setInitialScale(zoom)

                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    allowFileAccess = true
                    allowContentAccess = true
                    @Suppress("DEPRECATION") allowFileAccessFromFileURLs = true
                    @Suppress("DEPRECATION") allowUniversalAccessFromFileURLs = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    textZoom = zoom
                }

                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            try {
                                wv.scrollTo(scrollX, scrollY)

                                Handler(Looper.getMainLooper()).postDelayed({
                                    try {
                                        // Tam boyutlu bitmap oluştur
                                        val fullBmp = Bitmap.createBitmap(renderW, renderH, Bitmap.Config.ARGB_8888)
                                        val canvas = Canvas(fullBmp)
                                        canvas.drawColor(Color.WHITE)
                                        wv.draw(canvas)
                                        wv.destroy()

                                        // Sadece seçilen alanı kes — widget boyutunda tam kesit
                                        val cropX = scrollX.coerceAtLeast(0)
                                        val cropY = scrollY.coerceAtLeast(0)
                                        val cropW = widthPx.coerceAtMost(renderW - cropX)
                                        val cropH = heightPx.coerceAtMost(renderH - cropY)

                                        val croppedBmp = Bitmap.createBitmap(fullBmp, cropX, cropY, cropW, cropH)
                                        fullBmp.recycle()

                                        // Widget boyutuna ölçekle (piksel mükemmel)
                                        val finalBmp = if (croppedBmp.width == widthPx && croppedBmp.height == heightPx) {
                                            croppedBmp
                                        } else {
                                            val scaled = Bitmap.createScaledBitmap(croppedBmp, widthPx, heightPx, true)
                                            croppedBmp.recycle()
                                            scaled
                                        }

                                        onDone(finalBmp)
                                    } catch (e: Exception) {
                                        try { wv.destroy() } catch (_: Exception) {}
                                        onDone(null)
                                    }
                                }, 300)
                            } catch (e: Exception) {
                                try { wv.destroy() } catch (_: Exception) {}
                                onDone(null)
                            }
                        }, delayMs)
                    }

                    override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                        try { wv.destroy() } catch (_: Exception) {}
                        onDone(null)
                    }
                }

                try {
                    val parsedUri = Uri.parse(uri)
                    val stream = ctx.contentResolver.openInputStream(parsedUri)
                    val html = stream?.bufferedReader()?.readText() ?: ""
                    stream?.close()
                    val basePath = parsedUri.path?.let {
                        val parent = java.io.File(it).parent
                        if (parent != null) "file://$parent/" else null
                    }
                    wv.loadDataWithBaseURL(basePath, html, "text/html", "UTF-8", null)
                } catch (e: Exception) {
                    try { wv.destroy() } catch (_: Exception) {}
                    onDone(null)
                }
            } catch (e: Exception) {
                onDone(null)
            }
        }
    }

    fun dpToPx(ctx: Context, dp: Int): Int =
        (dp * ctx.resources.displayMetrics.density).toInt().coerceAtLeast(50)
}
