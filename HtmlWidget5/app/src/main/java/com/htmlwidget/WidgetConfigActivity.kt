package com.htmlwidget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class WidgetConfigActivity : AppCompatActivity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var selectedUri: Uri? = null
    private var selectedName = ""
    private var syncing = false
    private var intervalSec = 900L
    private var currentZoom = 100
    private var currentCols = 2
    private var currentRows = 2

    private val UNITS = arrayOf("saniye", "dakika", "saat")

    private val wvPreview get() = findViewById<WebView>(R.id.wv_preview)
    private val overlay get() = findViewById<SelectionOverlayView>(R.id.overlay)
    private val tvHint get() = findViewById<TextView>(R.id.tv_preview_hint)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        setContentView(R.layout.activity_widget_config)

        setupFile()
        setupCellSize()
        setupScrollZoom()
        setupInterval()
        setupButtons()
    }

    // ── DOSYA ──────────────────────────────────────────────────────────────
    private fun setupFile() {
        findViewById<Button>(R.id.btn_file).setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/html", "text/htm", "application/xhtml+xml"))
            }, REQ_FILE)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_FILE && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            selectedUri = uri
            selectedName = queryName(uri)
            findViewById<TextView>(R.id.tv_file).text = selectedName
            loadPreview(uri)
        }
    }

    private fun queryName(uri: Uri): String {
        var name = ""
        try {
            contentResolver.query(uri, null, null, null, null)?.use {
                if (it.moveToFirst()) {
                    val i = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) name = it.getString(i)
                }
            }
        } catch (_: Exception) {}
        return name.ifEmpty { uri.lastPathSegment ?: "dosya.html" }
    }

    // ── HÜCRE BOYUTU ───────────────────────────────────────────────────────
    private fun setupCellSize() {
        val seekCols = findViewById<SeekBar>(R.id.seek_cols)
        val seekRows = findViewById<SeekBar>(R.id.seek_rows)
        val tvCols = findViewById<TextView>(R.id.tv_cols)
        val tvRows = findViewById<TextView>(R.id.tv_rows)
        val tvLabel = findViewById<TextView>(R.id.tv_cell_label)

        fun applyCell(cols: Int, rows: Int) {
            currentCols = cols; currentRows = rows
            tvCols.text = cols.toString()
            tvRows.text = rows.toString()
            tvLabel.text = "${cols}×${rows} hücre  (~${cols * 74}×${rows * 74} dp)"
            seekCols.progress = cols - 1
            seekRows.progress = rows - 1
            // Önizleme çerçevesini güncelle
            overlay.post { overlay.setCellSize(cols, rows) }
        }

        seekCols.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) applyCell(p + 1, currentRows)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        seekRows.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) applyCell(currentCols, p + 1)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        // Hızlı seçim butonları
        mapOf(
            R.id.btn_1x1 to (1 to 1), R.id.btn_1x2 to (1 to 2),
            R.id.btn_2x1 to (2 to 1), R.id.btn_2x2 to (2 to 2),
            R.id.btn_2x4 to (2 to 4), R.id.btn_4x2 to (4 to 2),
            R.id.btn_4x4 to (4 to 4), R.id.btn_5x4 to (5 to 4)
        ).forEach { (btnId, pair) ->
            findViewById<Button>(btnId).setOnClickListener { applyCell(pair.first, pair.second) }
        }

        applyCell(2, 2)
    }

    // ── ÖNİZLEME ──────────────────────────────────────────────────────────
    private fun loadPreview(uri: Uri) {
        tvHint.text = getString(R.string.preview_loading)
        tvHint.visibility = android.view.View.VISIBLE

        wvPreview.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            allowFileAccess = true
            @Suppress("DEPRECATION") allowFileAccessFromFileURLs = true
            @Suppress("DEPRECATION") allowUniversalAccessFromFileURLs = true
            setSupportZoom(false)
            builtInZoomControls = false
            textZoom = currentZoom
        }
        wvPreview.setInitialScale(currentZoom)

        wvPreview.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                tvHint.visibility = android.view.View.GONE
                Handler(Looper.getMainLooper()).postDelayed({
                    wvPreview.evaluateJavascript(
                        "(function(){return JSON.stringify({w:document.body.scrollWidth,h:document.body.scrollHeight});})()"
                    ) { result ->
                        try {
                            val w = Regex("\"w\":(\\d+)").find(result)?.groupValues?.get(1)?.toIntOrNull() ?: 1000
                            val h = Regex("\"h\":(\\d+)").find(result)?.groupValues?.get(1)?.toIntOrNull() ?: 3000
                            overlay.contentWidth = w
                            overlay.contentHeight = h
                            overlay.setCellSize(currentCols, currentRows)
                        } catch (_: Exception) {}
                    }
                }, 700)
            }
        }

        overlay.onPositionChanged = { sx, sy ->
            syncing = true
            findViewById<SeekBar>(R.id.seek_scroll_x).progress = sx.coerceIn(0, 5000)
            findViewById<EditText>(R.id.et_scroll_x).setText(sx.toString())
            findViewById<SeekBar>(R.id.seek_scroll_y).progress = sy.coerceIn(0, 10000)
            findViewById<EditText>(R.id.et_scroll_y).setText(sy.toString())
            syncing = false
        }

        try {
            val stream = contentResolver.openInputStream(uri)
            val html = stream?.bufferedReader()?.readText() ?: ""
            stream?.close()
            val base = uri.path?.let { java.io.File(it).parent?.let { p -> "file://$p/" } }
            wvPreview.loadDataWithBaseURL(base, html, "text/html", "UTF-8", null)
        } catch (_: Exception) {
            tvHint.text = getString(R.string.error)
        }
    }

    private fun applyZoom(zoom: Int) {
        currentZoom = zoom
        wvPreview.settings.textZoom = zoom
        wvPreview.evaluateJavascript("document.body.style.zoom='${zoom / 100f}';", null)
        overlay.setZoomRatio(zoom)
    }

    // ── SCROLL / ZOOM ──────────────────────────────────────────────────────
    private fun setupScrollZoom() {
        bindInt(R.id.seek_scroll_x, R.id.et_scroll_x, 0, 5000, 0) { sx ->
            if (!syncing) overlay.setScrollPosition(sx, getInt(R.id.et_scroll_y, 0))
        }
        bindInt(R.id.seek_scroll_y, R.id.et_scroll_y, 0, 10000, 0) { sy ->
            if (!syncing) overlay.setScrollPosition(getInt(R.id.et_scroll_x, 0), sy)
        }
        bindInt(R.id.seek_zoom, R.id.et_zoom, 10, 500, 100) { applyZoom(it) }
        bindFloat(R.id.seek_delay, R.id.et_delay, 0f, 30f, 1.5f)
    }

    // ── YENİLEME ──────────────────────────────────────────────────────────
    private fun setupInterval() {
        val tvLabel = findViewById<TextView>(R.id.tv_interval_label)
        val seek = findViewById<SeekBar>(R.id.seek_interval)
        val etVal = findViewById<EditText>(R.id.et_interval_val)
        val spinner = findViewById<Spinner>(R.id.spinner_unit)

        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, UNITS).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinner.setSelection(1)

        fun fmt(sec: Long) = when {
            sec < 60 -> "$sec saniye"
            sec < 3600 && sec % 60 == 0L -> "${sec / 60} dakika"
            sec % 3600 == 0L -> "${sec / 3600} saat"
            else -> "${sec / 60} dk ${sec % 60} sn"
        }

        fun p2s(p: Int) = (Math.exp(p / 1000.0 * Math.log(86400.0)) + 0.5).toLong().coerceIn(1L, 86400L)
        fun s2p(s: Long) = (Math.log(s.toDouble()) / Math.log(86400.0) * 1000).toInt().coerceIn(0, 1000)

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser || syncing) return
                val sec = p2s(p); intervalSec = sec
                tvLabel.text = fmt(sec)
                syncing = true
                etVal.setText(when (spinner.selectedItemPosition) {
                    0 -> "$sec"
                    1 -> if (sec % 60 == 0L) "${sec / 60}" else String.format("%.1f", sec / 60.0)
                    else -> if (sec % 3600 == 0L) "${sec / 3600}" else String.format("%.2f", sec / 3600.0)
                }); syncing = false
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        etVal.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (syncing) return
                val v = s.toString().toDoubleOrNull() ?: return
                val sec = when (spinner.selectedItemPosition) {
                    0 -> v.toLong(); 1 -> (v * 60).toLong(); else -> (v * 3600).toLong()
                }.coerceAtLeast(1L)
                intervalSec = sec
                syncing = true; tvLabel.text = fmt(sec); seek.progress = s2p(sec); syncing = false
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                if (syncing) return
                syncing = true
                etVal.setText(when (pos) {
                    0 -> "$intervalSec"
                    1 -> if (intervalSec % 60 == 0L) "${intervalSec / 60}" else String.format("%.1f", intervalSec / 60.0)
                    else -> if (intervalSec % 3600 == 0L) "${intervalSec / 3600}" else String.format("%.2f", intervalSec / 3600.0)
                }); syncing = false
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        intervalSec = 900L
        seek.progress = s2p(900L)
        etVal.setText("15")
        tvLabel.text = fmt(900L)
    }

    // ── KAYDET ─────────────────────────────────────────────────────────────
    private fun setupButtons() {
        findViewById<Button>(R.id.btn_cancel).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_add).setOnClickListener {
            if (selectedUri == null) {
                Toast.makeText(this, "Lütfen bir HTML dosyası seçin", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Prefs.setUri(this, widgetId, selectedUri.toString())
            Prefs.setName(this, widgetId, selectedName)
            Prefs.setIntervalSec(this, widgetId, intervalSec.coerceAtLeast(1L))
            Prefs.setScrollX(this, widgetId, getInt(R.id.et_scroll_x, 0))
            Prefs.setScrollY(this, widgetId, getInt(R.id.et_scroll_y, 0))
            Prefs.setZoom(this, widgetId, getInt(R.id.et_zoom, 100).coerceIn(10, 500))
            Prefs.setDelaySec(this, widgetId, getFloat(R.id.et_delay, 1.5f).coerceIn(0f, 30f))
            Prefs.setCols(this, widgetId, currentCols)
            Prefs.setRows(this, widgetId, currentRows)

            val mgr = AppWidgetManager.getInstance(this)
            HtmlWidgetProvider.update(this, mgr, widgetId)
            HtmlWidgetProvider.scheduleAlarm(this, widgetId)

            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            finish()
        }
    }

    // ── YARDIMCI ──────────────────────────────────────────────────────────
    private fun bindInt(seekId: Int, editId: Int, min: Int, max: Int, def: Int, cb: ((Int) -> Unit)? = null) {
        val seek = findViewById<SeekBar>(seekId)
        val edit = findViewById<EditText>(editId)
        seek.max = max - min; seek.progress = def - min; edit.setText(def.toString())
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser || syncing) return
                val v = p + min; syncing = true; edit.setText(v.toString()); syncing = false; cb?.invoke(v)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        edit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (syncing) return
                val v = s.toString().toIntOrNull()?.coerceIn(min, max) ?: return
                syncing = true; seek.progress = v - min; syncing = false; cb?.invoke(v)
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    private fun bindFloat(seekId: Int, editId: Int, min: Float, max: Float, def: Float) {
        val seek = findViewById<SeekBar>(seekId)
        val edit = findViewById<EditText>(editId)
        seek.max = ((max - min) * 10).toInt()
        seek.progress = ((def - min) * 10).toInt()
        edit.setText(String.format("%.1f", def))
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser || syncing) return
                syncing = true; edit.setText(String.format("%.1f", min + p / 10f)); syncing = false
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        edit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (syncing) return
                val v = s.toString().toFloatOrNull()?.coerceIn(min, max) ?: return
                syncing = true; seek.progress = ((v - min) * 10).toInt(); syncing = false
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    private fun getInt(id: Int, def: Int) = findViewById<EditText>(id).text.toString().toIntOrNull() ?: def
    private fun getFloat(id: Int, def: Float) = findViewById<EditText>(id).text.toString().toFloatOrNull() ?: def

    companion object { private const val REQ_FILE = 1001 }
}
