package com.netha.keyboardultra

import android.content.Intent
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.ImageView
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONArray
import java.io.File
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private lateinit var remoteList: LinearLayout
    private lateinit var remoteStatus: TextView

    private val galleryPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) { }
            KeyboardPrefs.get(this).edit()
                .putString(KeyboardPrefs.WALLPAPER_URI, uri.toString())
                .remove(KeyboardPrefs.WALLPAPER_FILE)
                .apply()
            toast("Gallery wallpaper applied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(9, 11, 18)
        window.navigationBarColor = Color.rgb(9, 11, 18)
        buildScreen()
    }

    private fun buildScreen() {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.rgb(9, 11, 18))
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(28))
        }
        scroll.addView(root)
        setContentView(scroll)

        val title = text("NETHA", 15, 0xFF9B8CFF.toInt(), true)
        title.letterSpacing = 0.22f
        root.addView(title)
        root.addView(text("KEYBOARD ULTRA", 29, Color.WHITE, true))
        root.addView(text("A smoother Sinhala + English typing experience.", 14, 0xFF9BA3B5.toInt(), false).apply {
            setPadding(0, dp(3), 0, dp(18))
        })

        val preview = card()
        preview.addView(text("LIVE KEYBOARD PREVIEW", 10, 0xFF9B8CFF.toInt(), true))
        val previewLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(13), 0, dp(8))
        }
        listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P").forEach { key ->
            val keyView = text(key, 13, Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                background = rounded(0xFF303747.toInt(), 9)
                setPadding(dp(5), dp(11), dp(5), dp(11))
            }
            previewLine.addView(keyView, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                setMargins(dp(2), 0, dp(2), 0)
            })
        }
        preview.addView(previewLine)
        preview.addView(text("සිංහල   •   English   •   1234567890", 13, 0xFFCFD5E2.toInt(), false))
        root.addView(preview)

        val activation = card()
        activation.addView(sectionTitle("SET UP KEYBOARD"))
        activation.setPadding(dp(12), dp(12), dp(12), dp(12))
        activation.addView(text("Enable NETHA, then select it as your keyboard.", 12, 0xFFADB6C8.toInt(), false))
        activation.addView(actionButton("Enable keyboard", 0xFF6557E8.toInt(),
            LinearLayout.LayoutParams(-1, dp(42))) {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        })
        activation.addView(actionButton("Select keyboard", 0xFF273247.toInt(),
            LinearLayout.LayoutParams(-1, dp(42))) {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        })

        val suggestionsCard = card()
        suggestionsCard.setPadding(dp(12), dp(12), dp(12), dp(12))
        suggestionsCard.addView(sectionTitle("ONLINE SUGGESTIONS"))
        suggestionsCard.addView(text(
            "Sinhala + English word ideas. Only the unfinished word is sent online; messages are not read. Disabled in password and private fields.",
            12, 0xFFADB6C8.toInt(), false
        ))
        val suggestionsSwitch = Switch(this).apply {
            text = "Online suggestions"
            textSize = 12f
            setTextColor(Color.WHITE)
            isChecked = KeyboardPrefs.onlineSuggestionsEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, enabled ->
                KeyboardPrefs.get(this@MainActivity).edit()
                    .putBoolean(KeyboardPrefs.ONLINE_SUGGESTIONS, enabled)
                    .apply()
            }
        }
        suggestionsCard.addView(suggestionsSwitch)
        addSideBySide(activation, suggestionsCard)

        val themeCard = card()
        themeCard.addView(sectionTitle("PREMIUM KEYBOARD THEMES"))
        themeCard.addView(text("Classic looks and multi-colour RGB letter styles.", 13, 0xFFADB6C8.toInt(), false))
        val themeGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val themes = listOf(
            "Light", "Dark", "Ocean", "Rose",
            "Graphite", "Emerald", "Sunset", "RGB",
            "Neon", "Aurora", "Prism", "Cyber",
            "Candy", "Fire"
        )
        themes.chunked(4).forEach { group ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            group.forEach { name ->
                val selected = KeyboardPrefs.theme(this) == name
                row.addView(actionButton((if (selected) "✓ " else "") + name,
                    if (selected) 0xFF6557E8.toInt() else 0xFF273247.toInt()) {
                    KeyboardPrefs.get(this).edit().putString(KeyboardPrefs.THEME, name).apply()
                    toast(name + " theme selected")
                    buildScreen()
                }, LinearLayout.LayoutParams(0, dp(43), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            }
            themeGrid.addView(row)
        }
        themeCard.addView(themeGrid)
        root.addView(themeCard)

        val spaceCard = card()
        spaceCard.addView(sectionTitle("CUSTOM SPACEBAR NAME"))
        spaceCard.addView(text("Choose the text shown on your keyboard space key. Default: NETHA.", 13, 0xFFADB6C8.toInt(), false))
        val spaceNameInput = EditText(this).apply {
            setSingleLine(true)
            setText(KeyboardPrefs.spaceLabel(this@MainActivity))
            textSize = 15f
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF8D96A8.toInt())
            hint = "NETHA"
            filters = arrayOf(android.text.InputFilter.LengthFilter(14))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(0xFF202838.toInt(), 12)
        }
        spaceCard.addView(spaceNameInput, LinearLayout.LayoutParams(-1, dp(48)))
        spaceCard.addView(actionButton("Save spacebar name", 0xFF6557E8.toInt()) {
            val label = spaceNameInput.text?.toString()?.trim().orEmpty().take(14).ifBlank { "NETHA" }
            KeyboardPrefs.get(this).edit().putString(KeyboardPrefs.SPACE_LABEL, label).apply()
            spaceNameInput.setText(label)
            toast("Spacebar name saved")
        })
        root.addView(spaceCard)

        val wallpaperCard = card()
        wallpaperCard.addView(sectionTitle("YOUR KEYBOARD WALLPAPER"))
        wallpaperCard.addView(text("Use a picture from your Gallery, or apply one uploaded to the project's GitHub wallpapers folder.", 13, 0xFFADB6C8.toInt(), false))
        wallpaperCard.addView(actionButton("Choose from Gallery", 0xFF273247.toInt()) {
            galleryPicker.launch(arrayOf("image/jpeg", "image/png", "image/webp", "image/*"))
        })
        wallpaperCard.addView(actionButton("Clear wallpaper", 0xFF273247.toInt()) {
            KeyboardPrefs.get(this).edit()
                .remove(KeyboardPrefs.WALLPAPER_URI)
                .remove(KeyboardPrefs.WALLPAPER_FILE)
                .apply()
            toast("Wallpaper cleared")
        })
        wallpaperCard.addView(sectionTitle("NETHA WALLPAPER"))
        wallpaperCard.addView(actionButton("Refresh NETHA wallpaper list", 0xFF6557E8.toInt()) {
            loadGithubWallpapers()
        })
        remoteStatus = text("Tap refresh to check images uploaded to GitHub.", 12, 0xFF9BA3B5.toInt(), false)
        wallpaperCard.addView(remoteStatus)
        remoteList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wallpaperCard.addView(remoteList)
        root.addView(wallpaperCard)

        val transparencyCard = card().apply { setPadding(dp(12), dp(12), dp(12), dp(12)) }
        transparencyCard.addView(sectionTitle("KEY OPACITY"))
        transparencyCard.addView(text("Set key transparency over your wallpaper.", 12, 0xFFADB6C8.toInt(), false))
        addSlider(transparencyCard, KeyboardPrefs.keyAlpha(this), 20, 100, "Opacity") { value ->
            KeyboardPrefs.get(this).edit().putInt(KeyboardPrefs.KEY_ALPHA, value).apply()
        }

        val dimCard = card().apply { setPadding(dp(12), dp(12), dp(12), dp(12)) }
        dimCard.addView(sectionTitle("WALLPAPER DIM"))
        dimCard.addView(text("Darken the photo to keep letters clear.", 12, 0xFFADB6C8.toInt(), false))
        addSlider(dimCard, KeyboardPrefs.wallpaperDim(this), 0, 85, "Dim level") { value ->
            KeyboardPrefs.get(this).edit().putInt(KeyboardPrefs.WALLPAPER_DIM, value).apply()
        }
        addSideBySide(transparencyCard, dimCard)

        val effectsCard = card()
        effectsCard.addView(sectionTitle("FALLING FEELING EFFECTS"))
        effectsCard.addView(text("Choose subtle falling effects for your keyboard.", 13, 0xFFADB6C8.toInt(), false))
        val effectNames = listOf("None", "Flowers", "Gems", "Leaves", "Hearts", "Roses", "Sakura", "Sparkles")
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, effectNames)
            setSelection(effectNames.indexOf(KeyboardPrefs.effect(this@MainActivity)).coerceAtLeast(0))
            setBackground(rounded(0xFF202838.toInt(), 12))
        }
        spinner.setPadding(dp(12), dp(8), dp(12), dp(8))
        spinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                KeyboardPrefs.get(this@MainActivity).edit().putString(KeyboardPrefs.EFFECT, effectNames[position]).apply()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        })
        effectsCard.addView(spinner)
        root.addView(effectsCard)

        root.addView(text("NETHA KEYBOARD ULTRA  •  Built for Sinhala & English", 11, 0xFF6F788A.toInt(), true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(8))
        })
    }

    private fun loadGithubWallpapers() {
        remoteStatus.text = "Checking GitHub wallpapers…"
        remoteList.removeAllViews()
        io.execute {
            try {
                val endpoint = URL("https://api.github.com/repos/nethmindaimesh786-stack/Netha-xBoostNow/contents/wallpapers?ref=keyboard-zip-import")
                val connection = endpoint.openConnection() as HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 12000
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "NETHA-KEYBOARD-ULTRA")
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()
                val array = JSONArray(response)
                val images = mutableListOf<Pair<String, String>>()
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    if (item.optString("type") != "file") continue
                    val name = item.optString("name")
                    val url = item.optString("download_url")
                    val valid = name.substringAfterLast('.', "").lowercase() in listOf("jpg", "jpeg", "png", "webp")
                    if (valid && url.startsWith("https://raw.githubusercontent.com/")) images.add(name to url)
                }
                mainHandler.post {
                    remoteList.removeAllViews()
                    remoteStatus.text = if (images.isEmpty()) {
                        "No image files yet. Upload JPG, PNG or WebP files into the wallpapers/ folder on the keyboard-zip-import branch."
                    } else {
                        images.size.toString() + " wallpaper(s) found on GitHub."
                    }
                    showRemoteWallpapers(images)
                }
            } catch (error: Exception) {
                mainHandler.post {
                    remoteStatus.text = "Could not load GitHub list. Check your internet connection and try again."
                }
            }
        }
    }

    private fun showRemoteWallpapers(images: List<Pair<String, String>>) {
        remoteList.removeAllViews()
        var index = 0
        while (index < images.size) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            val first = images[index]
            addRemoteWallpaper(first.first, first.second, row)
            if (index + 1 < images.size) {
                val second = images[index + 1]
                addRemoteWallpaper(second.first, second.second, row)
            }
            remoteList.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, dp(3), 0, dp(3))
            })
            index += 2
        }
    }

    private fun addRemoteWallpaper(name: String, url: String, parent: LinearLayout) {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = rounded(0xFF202838.toInt(), 12)
        }
        val preview = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(112))
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(0xFF273247.toInt(), 10)
            clipToOutline = true
            contentDescription = "NETHA wallpaper preview"
        }
        tile.addView(preview)
        tile.addView(actionButton("Apply", 0xFF6557E8.toInt(),
            LinearLayout.LayoutParams(-1, dp(38))) {
            downloadRemoteWallpaper(name, url)
        })
        parent.addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply {
            setMargins(dp(4), dp(4), dp(4), dp(4))
        })
        loadWallpaperThumbnail(preview, url)
    }

    private fun loadWallpaperThumbnail(target: ImageView, url: String) {
        io.execute {
            var connection: HttpURLConnection? = null
            try {
                connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 8000
                connection.readTimeout = 10000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "NETHA-KEYBOARD-ULTRA")

                val bytes = connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > 8 * 1024 * 1024) {
                            throw IllegalStateException("Wallpaper preview is too large")
                        }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }

                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@execute

                val targetSize = dp(112)
                var sampleSize = 1
                while (bounds.outWidth / sampleSize > targetSize * 2 ||
                    bounds.outHeight / sampleSize > targetSize * 2) {
                    sampleSize *= 2
                }
                val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    ?: return@execute

                mainHandler.post {
                    if (!isFinishing && !isDestroyed) {
                        target.setImageBitmap(bitmap)
                    }
                }
            } catch (_: Exception) {
                // Keep the clean placeholder if a preview cannot be loaded.
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun downloadRemoteWallpaper(name: String, url: String) {
        remoteStatus.text = "Downloading " + name + "…"
        io.execute {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 15000
                connection.instanceFollowRedirects = true
                val folder = File(filesDir, "wallpapers")
                if (!folder.exists()) folder.mkdirs()
                val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val target = File(folder, safeName)
                var total = 0L
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > 8L * 1024L * 1024L) throw IllegalStateException("Image is larger than 8 MB")
                            output.write(buffer, 0, count)
                        }
                    }
                }
                connection.disconnect()
                val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(target.absolutePath, options)
                if (options.outWidth <= 0 || options.outHeight <= 0 || options.outWidth > 16000 || options.outHeight > 16000) {
                    target.delete()
                    throw IllegalStateException("Unsupported or invalid image")
                }
                KeyboardPrefs.get(this).edit()
                    .putString(KeyboardPrefs.WALLPAPER_FILE, target.absolutePath)
                    .remove(KeyboardPrefs.WALLPAPER_URI)
                    .apply()
                mainHandler.post {
                    remoteStatus.text = name + " applied as wallpaper."
                    toast("GitHub wallpaper applied")
                }
            } catch (error: Exception) {
                mainHandler.post {
                    remoteStatus.text = "Wallpaper could not be downloaded. Try a smaller JPG, PNG or WebP image."
                    toast("Wallpaper download failed")
                }
            }
        }
    }

    private fun addSideBySide(left: LinearLayout, right: LinearLayout) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        row.addView(left, LinearLayout.LayoutParams(0, -2, 1f).apply {
            setMargins(0, dp(8), dp(5), dp(8))
        })
        row.addView(right, LinearLayout.LayoutParams(0, -2, 1f).apply {
            setMargins(dp(5), dp(8), 0, dp(8))
        })
        root.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    private fun addSlider(parent: LinearLayout, initial: Int, minimum: Int, maximum: Int, label: String, save: (Int) -> Unit) {
        val valueLabel = text(label + ": " + initial + "%", 12, 0xFFD5DAE6.toInt(), true)
        parent.addView(valueLabel)
        val bar = SeekBar(this).apply {
            max = maximum - minimum
            progress = initial.coerceIn(minimum, maximum) - minimum
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress + minimum
                valueLabel.text = label + ": " + value + "%"
                save(value)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        parent.addView(bar)
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(15), dp(15), dp(15), dp(15))
        background = rounded(0xFF151B28.toInt(), 18)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(8), 0, dp(8)) }
    }

    private fun sectionTitle(value: String) = text(value, 13, 0xFFE9EAF3.toInt(), true).apply {
        setPadding(0, dp(7), 0, dp(10))
        letterSpacing = 0.04f
    }

    private fun text(value: String, size: Int, color: Int, bold: Boolean) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        includeFontPadding = true
    }

    private fun actionButton(label: String, color: Int, action: () -> Unit) =
        actionButton(label, color, LinearLayout.LayoutParams(-1, dp(46)), action)

    private fun actionButton(label: String, color: Int, params: LinearLayout.LayoutParams, action: () -> Unit) =
        Button(this).apply {
            text = label
            textSize = 13f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = rounded(color, 12)
            setOnClickListener { action() }
            layoutParams = params.apply { if (height == -2) height = dp(46) }
        }

    private fun rounded(color: Int, radius: Int) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }
}
