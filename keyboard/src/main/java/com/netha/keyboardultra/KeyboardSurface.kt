package com.netha.keyboardultra

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import kotlin.math.max
import kotlin.math.min

private data class KeySpec(
    val label: String,
    val action: String,
    val weight: Float = 1f,
    val isLetter: Boolean = false,
    val isDelete: Boolean = false
)

class KeyboardSurface(
    context: Context,
    private val onLetter: (String) -> Unit,
    private val onSuggestionSelected: (String) -> Unit,
    private val onAction: (String) -> Unit,
    private val onHistoryRequest: () -> Unit,
    private val onPasteHistory: (String) -> Unit,
    private val onPinHistory: (String) -> Unit,
    private val onDeleteHistory: (String) -> Unit
) : FrameLayout(context) {

    private val keyboardColumn = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(5), dp(2), dp(5), dp(4))
    }
    private val toolbar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(7), dp(3), dp(7), dp(3))
        background = rounded(0xDD171D2A.toInt(), 24)
    }
    private val suggestionScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        isFillViewport = false
    }
    private val suggestionRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(2), 0, dp(2), 0)
    }
    private val keysContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(2), 0, 0)
    }
    private val clipboardPanel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(7), dp(8), dp(5))
        visibility = View.GONE
    }
    private val wallpaperView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        adjustViewBounds = false
        visibility = View.GONE
    }
    private val dimView = View(context).apply {
        setBackgroundColor(Color.BLACK)
        isClickable = false
    }
    private val effectView = FallingEffectView(context)
    private var languageButton: TextView? = null
    private var shiftButton: TextView? = null
    private var previewBubble: TextView? = null
    private var symbols = false
    private var shift = false
    private var showingClipboard = false
    private var currentHistory: List<ClipboardEntry> = emptyList()
    private val repeatHandler = Handler(Looper.getMainLooper())
    private var deleteRunnable: Runnable? = null
    private val rgbLetterKeys = mutableListOf<TextView>()
    private val rgbHandler = Handler(Looper.getMainLooper())
    private var rgbStartedAt = 0L
    private val rgbThemes = setOf("RGB", "Neon", "Aurora", "Prism", "Cyber", "Candy", "Fire")
    private val rgbTicker = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow || rgbLetterKeys.isEmpty() ||
                KeyboardPrefs.theme(context) !in rgbThemes) return
            val elapsed = SystemClock.uptimeMillis() - rgbStartedAt
            rgbLetterKeys.forEachIndexed { index, key ->
                val hue = (index * 31f + elapsed * 0.018f) % 360f
                key.setTextColor(Color.HSVToColor(floatArrayOf(hue, 0.72f, 1f)))
            }
            rgbHandler.postDelayed(this, 80L)
        }
    }

    private val keyboardContentHeight = dp(300)
    private var navigationInsetBottom = dp(30)

    init {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, keyboardContentHeight + navigationInsetBottom)
        // Reserve space for Android's 3-button or gesture navigation area.
        // Some OEM IME windows report a zero nav inset, so keep a safe fallback.
        setOnApplyWindowInsetsListener { _, insets ->
            val reportedBottom = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }
            val safeBottom = max(dp(30), reportedBottom)
            if (safeBottom != navigationInsetBottom) {
                navigationInsetBottom = safeBottom
                layoutParams = layoutParams?.apply { height = keyboardContentHeight + navigationInsetBottom }
                requestLayout()
            }
            insets
        }
        clipChildren = false
        clipToPadding = false
        setBackgroundColor(Color.TRANSPARENT)
        addView(wallpaperView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(dimView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(keyboardColumn, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        keyboardColumn.addView(toolbar, LinearLayout.LayoutParams(-1, dp(36)).apply {
            setMargins(dp(2), 0, dp(2), dp(2))
        })
        buildSuggestionRow()
        keyboardColumn.addView(suggestionScroll, LinearLayout.LayoutParams(-1, dp(27)))
        keyboardColumn.addView(keysContainer, LinearLayout.LayoutParams(-1, -2))
        keyboardColumn.addView(clipboardPanel, LinearLayout.LayoutParams(-1, -2))
        addView(effectView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        buildToolbar()
        buildKeys()
        refreshAppearance()
    }

    // Keep the IME window compact so Android anchors the keyboard at the bottom.
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(keyboardContentHeight + navigationInsetBottom, MeasureSpec.EXACTLY)
        )
    }

    fun setLanguage(sinhala: Boolean) {
        languageButton?.text = if (sinhala) "සිංහල ▾" else "EN ▾"
    }

    fun setSuggestions(words: List<String>, hint: String = "") {
        suggestionRow.removeAllViews()
        val candidates = words.distinct().take(15)

        if (candidates.isEmpty()) {
            if (hint.isNotBlank()) {
                suggestionRow.addView(TextView(context).apply {
                    text = hint
                    gravity = Gravity.CENTER_VERTICAL
                    includeFontPadding = false
                    textSize = 11f
                    setTextColor(0xFFB7C0D0.toInt())
                    setPadding(dp(8), 0, dp(8), 0)
                }, LinearLayout.LayoutParams(-2, -1))
            }
            return
        }

        candidates.forEach { word ->
            val candidate = TextView(context).apply {
                text = word
                gravity = Gravity.CENTER
                includeFontPadding = false
                textSize = 13f
                maxLines = 1
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                minWidth = dp(54)
                background = rounded(0xB830384B.toInt(), 18)
                setPadding(dp(9), 0, dp(9), 0)
                setTextColor(Color.WHITE)
                isClickable = true
                setOnClickListener { onSuggestionSelected(word) }
            }
            suggestionRow.addView(candidate, LinearLayout.LayoutParams(-2, dp(24)).apply {
                setMargins(dp(2), 0, dp(2), 0)
            })
        }
        suggestionScroll.post { suggestionScroll.scrollTo(0, 0) }
    }

    private fun buildSuggestionRow() {
        suggestionScroll.removeAllViews()
        suggestionRow.removeAllViews()
        suggestionScroll.addView(
            suggestionRow,
            FrameLayout.LayoutParams(-2, -1)
        )
        setSuggestions(emptyList(), "Online word suggestions")
    }

    fun setShiftState(enabled: Boolean) {
        if (shift == enabled) return
        shift = enabled
        shiftButton?.text = if (enabled) "⇧" else "↑"
        buildKeys()
    }

    fun toggleSymbols() {
        symbols = !symbols
        buildKeys()
    }

    fun refreshAppearance() {
        loadWallpaper()
        effectView.setEffect(KeyboardPrefs.effect(context))
        buildKeys()
        syncRgbAnimation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncRgbAnimation()
    }

    override fun onDetachedFromWindow() {
        rgbHandler.removeCallbacks(rgbTicker)
        super.onDetachedFromWindow()
    }

    private fun syncRgbAnimation() {
        rgbHandler.removeCallbacks(rgbTicker)
        if (isAttachedToWindow && !showingClipboard &&
            KeyboardPrefs.theme(context) in rgbThemes && rgbLetterKeys.isNotEmpty()) {
            rgbStartedAt = SystemClock.uptimeMillis()
            rgbHandler.post(rgbTicker)
        }
    }

    private fun buildToolbar() {
        toolbar.removeAllViews()
        languageButton = toolbarPill("සිංහල ▾", 1.25f) { onAction("LANGUAGE") }
        toolbar.addView(languageButton, LinearLayout.LayoutParams(0, -1, 1.25f))
        toolbar.addView(toolbarPill("⧉  Copy", 1.05f) { onHistoryRequest() },
            LinearLayout.LayoutParams(0, -1, 1.05f).apply { setMargins(dp(4), 0, dp(4), 0) })
        toolbar.addView(toolbarPill("😊", 0.55f) { onAction("EMOJI") },
            LinearLayout.LayoutParams(0, -1, 0.55f))
        toolbar.addView(toolbarPill("⚙", 0.55f) { onAction("SETTINGS") },
            LinearLayout.LayoutParams(0, -1, 0.55f).apply { setMargins(dp(4), 0, 0, 0) })
        toolbar.addView(toolbarPill("⌄", 0.5f) { onAction("HIDE") },
            LinearLayout.LayoutParams(0, -1, 0.5f).apply { setMargins(dp(4), 0, 0, 0) })
    }

    private fun buildKeys() {
        rgbHandler.removeCallbacks(rgbTicker)
        rgbLetterKeys.clear()
        keysContainer.removeAllViews()
        if (showingClipboard) {
            suggestionScroll.visibility = View.GONE
            keysContainer.visibility = View.GONE
            clipboardPanel.visibility = View.VISIBLE
            return
        }
        suggestionScroll.visibility = View.VISIBLE
        clipboardPanel.visibility = View.GONE
        keysContainer.visibility = View.VISIBLE

        addRow((1..9).map { KeySpec(it.toString(), it.toString()) } + KeySpec("0", "0"))

        val first = if (symbols) {
            listOf("@", "#", "$", "%", "&", "*", "-", "+", "(", ")")
        } else {
            listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
        }
        addRow(first.map { KeySpec(displayKey(it), it, isLetter = !symbols) })

        val second = if (symbols) {
            listOf("!", "?", ":", ";", "'", "\"", "/", "\\", "=", "_")
        } else {
            listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
        }
        addRow(second.map { KeySpec(displayKey(it), it, isLetter = !symbols, weight = 1f) }, sideInset = 13)

        val third = if (symbols) {
            listOf("<", ">", "[", "]", "{", "}", "~")
        } else {
            listOf("z", "x", "c", "v", "b", "n", "m")
        }
        val thirdSpecs = mutableListOf<KeySpec>()
        thirdSpecs.add(KeySpec(if (shift) "⇧" else "↑", "SHIFT", 1.25f))
        third.forEach { thirdSpecs.add(KeySpec(displayKey(it), it, isLetter = !symbols)) }
        thirdSpecs.add(KeySpec("⌫", "DELETE", 1.45f, isDelete = true))
        addRow(thirdSpecs)

        val bottom = listOf(
            KeySpec(if (symbols) "ABC" else "?123", "SYMBOLS", 1.35f),
            KeySpec(",", ",", 0.78f),
            KeySpec("😊", "EMOJI", 0.9f),
            KeySpec(KeyboardPrefs.spaceLabel(context), "SPACE", 3.4f),
            KeySpec(".", ".", 0.78f),
            KeySpec("↵", "ENTER", 1.25f)
        )
        addRow(bottom, keyHeight = 46)
        syncRgbAnimation()
    }

    private fun displayKey(key: String): String =
        if (shift && key.length == 1 && key[0].isLetter()) key.uppercase() else key

    private fun addRow(specs: List<KeySpec>, sideInset: Int = 0, keyHeight: Int = 42) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(sideInset), 0, dp(sideInset), 0)
        }
        specs.forEachIndexed { index, spec ->
            val key = TextView(context).apply {
                text = spec.label
                gravity = Gravity.CENTER
                includeFontPadding = false
                textSize = when {
                    spec.action == "SPACE" -> 13f
                    spec.isLetter -> 20f
                    spec.action.length == 1 && spec.action[0].isDigit() -> 17f
                    else -> 16f
                }
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(keyTextColor(spec, index))
                background = keyBackground(spec)
                isClickable = true
                isFocusable = true
                setPadding(dp(2), dp(2), dp(2), dp(2))
                elevation = dp(1).toFloat()
            }
            val params = LinearLayout.LayoutParams(0, dp(keyHeight), spec.weight).apply {
                setMargins(dp(2), dp(2), dp(2), dp(2))
            }
            row.addView(key, params)
            if (spec.isLetter) rgbLetterKeys.add(key)

            var touchCancelled = false
            var deleteRepeater: Runnable? = null

            key.setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        touchCancelled = false
                        view.isPressed = true
                        view.performHapticFeedback(
                            if (Build.VERSION.SDK_INT >= 27) HapticFeedbackConstants.KEYBOARD_TAP
                            else HapticFeedbackConstants.VIRTUAL_KEY
                        )

                        if (spec.isDelete) {
                            onAction("DELETE")
                            val repeating = object : Runnable {
                                override fun run() {
                                    if (!touchCancelled && view.isPressed) {
                                        onAction("DELETE")
                                        repeatHandler.postDelayed(this, 55)
                                    }
                                }
                            }
                            deleteRepeater = repeating
                            repeatHandler.postDelayed(repeating, 360)
                        } else {
                            if (spec.isLetter) showPopup(view, spec.label)
                            // Dispatch key input on DOWN for immediate response,
                            // instead of waiting for the ACTION_UP click cycle.
                            if (spec.isLetter) onLetter(spec.action) else onAction(spec.action)
                        }
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val inside = event.x >= 0f && event.x < view.width &&
                            event.y >= 0f && event.y < view.height
                        if (!inside && !touchCancelled) {
                            touchCancelled = true
                            view.isPressed = false
                            deleteRepeater?.let { repeatHandler.removeCallbacks(it) }
                            hidePopup()
                        }
                        true
                    }

                    MotionEvent.ACTION_UP -> {
                        deleteRepeater?.let { repeatHandler.removeCallbacks(it) }
                        view.isPressed = false
                        hidePopup()

                        true
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        touchCancelled = true
                        view.isPressed = false
                        deleteRepeater?.let { repeatHandler.removeCallbacks(it) }
                        deleteRepeater = null
                        hidePopup()
                        true
                    }

                    else -> true
                }
            }
        }
        keysContainer.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    private fun keyTextColor(spec: KeySpec, index: Int): Int {
        val theme = KeyboardPrefs.theme(context)
        if (spec.isLetter) {
            if (theme in rgbThemes) {
                val hue = (index * 31f) % 360f
                return Color.HSVToColor(floatArrayOf(hue, 0.72f, 1f))
            }
            val seed = (spec.action.firstOrNull()?.lowercaseChar()?.code ?: index) + index * 3
            val palette = when (theme) {
                "RGB" -> intArrayOf(
                    0xFFFF5B79.toInt(), 0xFFFFB347.toInt(), 0xFFFFE66D.toInt(),
                    0xFF61E68D.toInt(), 0xFF42D9FF.toInt(), 0xFF8980FF.toInt(), 0xFFFF75D1.toInt()
                )
                "Neon" -> intArrayOf(
                    0xFF4DFBFF.toInt(), 0xFF55A7FF.toInt(), 0xFFB76BFF.toInt(),
                    0xFFFF5CC8.toInt(), 0xFFB8FF4D.toInt()
                )
                "Aurora" -> intArrayOf(
                    0xFF9BFFB3.toInt(), 0xFF55E8C1.toInt(), 0xFF54D9F5.toInt(),
                    0xFF8CBAFF.toInt(), 0xFFC29BFF.toInt()
                )
                "Prism" -> intArrayOf(
                    0xFFFF5470.toInt(), 0xFFFFA23D.toInt(), 0xFFFFE34D.toInt(),
                    0xFF4BE58C.toInt(), 0xFF40D9F5.toInt(), 0xFF777BFF.toInt(), 0xFFE16BFF.toInt()
                )
                "Cyber" -> intArrayOf(
                    0xFF45F2FF.toInt(), 0xFF36B7FF.toInt(), 0xFF638CFF.toInt(),
                    0xFF9B75FF.toInt(), 0xFF48E6D1.toInt()
                )
                "Candy" -> intArrayOf(
                    0xFFFF82B7.toInt(), 0xFFFFA5D8.toInt(), 0xFFD49BFF.toInt(),
                    0xFF9EBBFF.toInt(), 0xFF8DEBDF.toInt()
                )
                "Fire" -> intArrayOf(
                    0xFFFF5364.toInt(), 0xFFFF7548.toInt(), 0xFFFFA33D.toInt(),
                    0xFFFFD45B.toInt(), 0xFFFF7E9B.toInt()
                )
                else -> null
            }
            if (palette != null) return palette[Math.floorMod(seed, palette.size)]
        }
        return when (theme) {
            "Light" -> 0xFF202637.toInt()
            "Rose" -> 0xFFFFEAF2.toInt()
            "Ocean" -> 0xFFE2F6FF.toInt()
            "Sunset" -> 0xFFFFF0E4.toInt()
            else -> Color.WHITE
        }
    }

    private fun keyBackground(spec: KeySpec): GradientDrawable {
        val theme = KeyboardPrefs.theme(context)
        val special = !spec.isLetter && spec.action !in listOf("SPACE")
        val base = when (theme) {
            "Light" -> if (special) 0xFFD5DCE8.toInt() else 0xFFF4F6FA.toInt()
            "Ocean" -> if (special) 0xFF1C5269.toInt() else 0xFF173E52.toInt()
            "Rose" -> if (special) 0xFF75445D.toInt() else 0xFF4E3042.toInt()
            "Emerald" -> if (special) 0xFF28614B.toInt() else 0xFF1D4638.toInt()
            "Sunset" -> if (special) 0xFF80563C.toInt() else 0xFF573A2C.toInt()
            "Graphite" -> if (special) 0xFF4A4D54.toInt() else 0xFF30343B.toInt()
            "RGB" -> if (special) 0xFF252B3A.toInt() else 0xFF171C29.toInt()
            "Neon" -> if (special) 0xFF28213B.toInt() else 0xFF171A2B.toInt()
            "Aurora" -> if (special) 0xFF233A3D.toInt() else 0xFF172B31.toInt()
            "Prism" -> if (special) 0xFF342640.toInt() else 0xFF211A31.toInt()
            "Cyber" -> if (special) 0xFF1D3B50.toInt() else 0xFF142638.toInt()
            "Candy" -> if (special) 0xFF493047.toInt() else 0xFF302239.toInt()
            "Fire" -> if (special) 0xFF56352C.toInt() else 0xFF38231F.toInt()
            else -> if (special) 0xFF485064.toInt() else 0xFF303849.toInt()
        }
        val alpha = (KeyboardPrefs.keyAlpha(context) * 255 / 100).coerceIn(40, 255)
        return rounded(Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base)), 9)
    }

    private fun toolbarPill(label: String, weight: Float, action: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 11f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(Color.WHITE)
            background = rounded(0xFF30384B.toInt(), 30)
            isClickable = true
            setOnClickListener { action() }
        }

    fun showClipboardHistory(items: List<ClipboardEntry>) {
        currentHistory = items
        showingClipboard = true
        buildKeys()
        renderClipboardHistory()
    }

    fun updateClipboardHistory(items: List<ClipboardEntry>) {
        currentHistory = items
        if (showingClipboard) renderClipboardHistory()
    }

    fun hideClipboardHistory() {
        showingClipboard = false
        buildKeys()
    }

    private fun renderClipboardHistory() {
        clipboardPanel.removeAllViews()
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(context).apply {
            text = "CLIPBOARD HISTORY"
            textSize = 12f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(35), 1f))
        header.addView(toolbarPill("Close", 1f) { hideClipboardHistory() },
            LinearLayout.LayoutParams(dp(65), dp(31)))
        clipboardPanel.addView(header)

        val scroll = ScrollView(context)
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        if (currentHistory.isEmpty()) {
            list.addView(TextView(context).apply {
                text = "Copied text will appear here while NETHA Keyboard is active."
                textSize = 12f
                setTextColor(0xFFD4D9E4.toInt())
                setPadding(dp(8), dp(14), dp(8), dp(14))
            })
        }
        currentHistory.forEach { item ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(0xDD202838.toInt(), 12)
                setPadding(dp(8), dp(5), dp(6), dp(5))
            }
            val previewText = item.text.replace("\n", " ")
            val message = TextView(context).apply {
                text = (if (item.pinned) "📌 " else "") + previewText
                maxLines = 2
                textSize = 12f
                setTextColor(Color.WHITE)
                setPadding(dp(3), dp(4), dp(5), dp(4))
                setOnClickListener { onPasteHistory(item.text) }
            }
            row.addView(message, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(toolbarPill(if (item.pinned) "★" else "☆", 1f) { onPinHistory(item.text) },
                LinearLayout.LayoutParams(dp(35), dp(34)).apply { setMargins(dp(3), 0, dp(3), 0) })
            row.addView(toolbarPill("×", 1f) { onDeleteHistory(item.text) },
                LinearLayout.LayoutParams(dp(32), dp(34)))
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, dp(3), 0, dp(3))
            })
        }
        scroll.addView(list)
        clipboardPanel.addView(scroll, LinearLayout.LayoutParams(-1, dp(195)))
    }

    private fun showPopup(anchor: View, label: String) {
        // Reuse one in-keyboard preview bubble instead of creating a new OS window
        // for every tap. Its appearance and position remain the same.
        val bubble = previewBubble ?: TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 25f
            setTextColor(Color.WHITE)
            background = rounded(0xFF222A3B.toInt(), 14)
            elevation = dp(7).toFloat()
            isClickable = false
            isFocusable = false
        }.also {
            previewBubble = it
            addView(it, FrameLayout.LayoutParams(dp(52), dp(55)))
        }

        bubble.text = label
        val anchorLocation = IntArray(2)
        val rootLocation = IntArray(2)
        anchor.getLocationOnScreen(anchorLocation)
        getLocationOnScreen(rootLocation)
        val params = (bubble.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(dp(52), dp(55))
        params.width = dp(52)
        params.height = dp(55)
        params.gravity = Gravity.TOP or Gravity.START
        params.leftMargin = (anchorLocation[0] - rootLocation[0] + anchor.width / 2 - dp(26))
            .coerceIn(0, max(0, width - dp(52)))
        params.topMargin = (anchorLocation[1] - rootLocation[1] - dp(59)).coerceAtLeast(dp(2))
        bubble.layoutParams = params
        bubble.visibility = View.VISIBLE
        bringChildToFront(bubble)
    }

    private fun hidePopup() {
        previewBubble?.visibility = View.GONE
    }


    private fun loadWallpaper() {
        wallpaperView.setImageDrawable(null)
        val preferences = KeyboardPrefs.get(context)
        val uriValue = preferences.getString(KeyboardPrefs.WALLPAPER_URI, null)
        val fileValue = preferences.getString(KeyboardPrefs.WALLPAPER_FILE, null)
        val bitmap = try {
            when {
                !uriValue.isNullOrBlank() -> decodeUri(Uri.parse(uriValue))
                !fileValue.isNullOrBlank() -> decodeFile(File(fileValue))
                else -> null
            }
        } catch (_: Exception) {
            null
        }
        if (bitmap == null) {
            wallpaperView.visibility = View.GONE
        } else {
            wallpaperView.setImageBitmap(bitmap)
            wallpaperView.visibility = View.VISIBLE
        }
        dimView.alpha = KeyboardPrefs.wallpaperDim(context) / 100f
    }

    private fun decodeUri(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun decodeFile(file: File): Bitmap? {
        if (!file.exists() || !file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (width / sample > 1600 || height / sample > 1100) sample *= 2
        return sample
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
