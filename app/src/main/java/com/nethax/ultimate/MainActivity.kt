package com.netha.downloader

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val bg = Color.rgb(8, 13, 24)
    private val panel = Color.rgb(17, 27, 43)
    private val accent = Color.rgb(0, 220, 170)
    private val muted = Color.rgb(164, 180, 199)
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var linkInput: EditText
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var downloadManager: DownloadManager
    private var activeDownloadId = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        buildScreen()
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val shared = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        if (!shared.isNullOrBlank()) {
            linkInput.setText(shared.trim())
            status.text = "Link received. Check it, then tap Download."
        }
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(20), dp(18), dp(20), dp(20))
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(TextView(this).apply {
            text = "NETHA"
            textSize = 15f
            letterSpacing = 0.28f
            setTextColor(accent)
            gravity = Gravity.CENTER
        }, matchWrap())
        content.addView(TextView(this).apply {
            text = "DOWNLOADER"
            textSize = 30f
            letterSpacing = 0.06f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(4))
        }, matchWrap())
        content.addView(TextView(this).apply {
            text = "Save media you own or have permission to download."
            textSize = 13f
            setTextColor(muted)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(20))
        }, matchWrap())

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(16))
            setBackgroundColor(panel)
        }
        card.addView(label("PASTE A DIRECT MEDIA URL"))
        linkInput = EditText(this).apply {
            hint = "https://example.com/video.mp4"
            textSize = 14f
            singleLine = false
            minLines = 2
            maxLines = 3
            setTextColor(Color.WHITE)
            setHintTextColor(muted)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.rgb(29, 43, 62))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        card.addView(linkInput, LinearMatch(dp(12)))
        card.addView(Button(this).apply {
            text = "CHECK & DOWNLOAD"
            setTextColor(Color.BLACK)
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(accent))
            setOnClickListener { startDownload() }
        }, LinearMatch(dp(14)))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
        }
        card.addView(progress, LinearMatch(dp(10)))
        status = TextView(this).apply {
            text = "Ready. Paste a direct MP4, MP3, M4A, WebM or other media-file URL."
            textSize = 13f
            setTextColor(muted)
            setPadding(0, dp(12), 0, 0)
        }
        card.addView(status, matchWrap())
        content.addView(card, LinearMatch(dp(18)))

        content.addView(sectionTitle("SUPPORTED LINK TYPES"))
        content.addView(infoCard(
            "DIRECT VIDEO / AUDIO FILES",
            "Downloads supported direct media URLs to Downloads/NethaDownloader. The app checks the server response before starting."
        ))
        content.addView(infoCard(
            "YOUTUBE · TIKTOK · FACEBOOK",
            "Paste these links to identify the platform and open its official app/site. This app does not extract streams, bypass restrictions, or convert protected platform videos. Use the platform's own download/export option for your own content when available."
        ))
        content.addView(sectionTitle("HOW TO USE"))
        content.addView(infoCard("01  Copy or share a link", "Share a link to NETHA DOWNLOADER, or paste it into the box above."))
        content.addView(infoCard("02  Download permitted media", "Use a direct media-file URL that you own or are allowed to save. Platform page URLs are not direct media files."))
        content.addView(infoCard("03  Find your file", "Completed downloads are saved in your phone's Downloads/NethaDownloader folder."))
        content.addView(TextView(this).apply {
            text = "NETHA DOWNLOADER  •  PRIVATE & SIMPLE"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(muted)
            setPadding(0, dp(18), 0, dp(8))
        }, matchWrap())
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun startDownload() {
        val raw = linkInput.text.toString().trim()
        val uri = try { Uri.parse(raw) } catch (_: Exception) { null }
        if (raw.isBlank() || uri == null || (uri.scheme != "https" && uri.scheme != "http") || uri.host.isNullOrBlank()) {
            status.text = "Enter a valid http:// or https:// link."
            return
        }
        val host = uri.host!!.lowercase(Locale.US)
        val platform = when {
            host == "youtu.be" || host.endsWith("youtube.com") || host.endsWith("youtube-nocookie.com") -> "YouTube"
            host.endsWith("tiktok.com") -> "TikTok"
            host.endsWith("facebook.com") || host == "fb.watch" || host.endsWith("fb.watch") -> "Facebook"
            else -> null
        }
        if (platform != null) {
            status.text = "$platform link detected. Open the official platform and use its permitted Save/Download or creator-export feature. Page links cannot be downloaded as raw media files here."
            openPlatform(raw)
            return
        }
        hideKeyboard()
        status.text = "Checking whether this URL is a media file…"
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        executor.execute {
            var contentType = ""
            var checkError: String? = null
            try {
                val connection = URL(raw).openConnection() as HttpURLConnection
                connection.requestMethod = "HEAD"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "NethaDownloader/1.0")
                connection.connect()
                val code = connection.responseCode
                contentType = connection.contentType.orEmpty().lowercase(Locale.US)
                if (code !in 200..299) checkError = "Server returned HTTP $code."
                connection.disconnect()
            } catch (e: Exception) {
                checkError = e.message ?: "Could not check this URL."
            }
            runOnUiThread {
                progress.isIndeterminate = false
                progress.visibility = View.GONE
                val path = uri.lastPathSegment.orEmpty().lowercase(Locale.US)
                val knownExtension = listOf(".mp4", ".m4v", ".mov", ".webm", ".mkv", ".mp3", ".m4a", ".aac", ".wav", ".ogg", ".flac").any { path.endsWith(it) }
                val mediaType = contentType.startsWith("video/") || contentType.startsWith("audio/") || contentType.startsWith("application/octet-stream")
                if (checkError != null && !knownExtension) {
                    status.text = "Could not verify this link as a media file. " + checkError + " Try a direct file URL."
                    return@runOnUiThread
                }
                if (!mediaType && !knownExtension) {
                    status.text = "This link does not appear to be a direct video/audio file (server type: " + (contentType.ifBlank { "unknown" }) + ")."
                    return@runOnUiThread
                }
                enqueueDownload(raw, uri)
            }
        }
    }

    private fun enqueueDownload(raw: String, uri: Uri) {
        try {
            val fileName = safeFileName(uri.lastPathSegment ?: "netha_media")
            val request = DownloadManager.Request(Uri.parse(raw))
                .setTitle(fileName)
                .setDescription("NETHA DOWNLOADER")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "NethaDownloader/$fileName")
            activeDownloadId = downloadManager.enqueue(request)
            status.text = "Download started: $fileName"
            progress.visibility = View.VISIBLE
            progress.isIndeterminate = false
            progress.progress = 0
            monitorDownload(activeDownloadId)
        } catch (e: Exception) {
            status.text = "Could not start download: " + (e.message ?: "unknown error")
            progress.visibility = View.GONE
        }
    }

    private fun monitorDownload(id: Long) {
        val query = DownloadManager.Query().setFilterById(id)
        val poll = object : Runnable {
            override fun run() {
                if (isFinishing || id != activeDownloadId) return
                val cursor = downloadManager.query(query)
                if (cursor != null && cursor.moveToFirst()) {
                    val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val downloadedCol = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val totalCol = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    val state = if (statusCol >= 0) cursor.getInt(statusCol) else -1
                    val downloaded = if (downloadedCol >= 0) cursor.getLong(downloadedCol) else 0L
                    val total = if (totalCol >= 0) cursor.getLong(totalCol) else -1L
                    if (total > 0) progress.progress = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                    when (state) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            progress.progress = 100
                            status.text = "Download complete. Find it in Downloads/NethaDownloader."
                            cursor.close()
                            return
                        }
                        DownloadManager.STATUS_FAILED -> {
                            status.text = "Download failed. The server may block downloads or require access."
                            progress.visibility = View.GONE
                            cursor.close()
                            return
                        }
                        else -> status.text = if (total > 0) "Downloading… " + progress.progress + "%" else "Downloading…"
                    }
                    cursor.close()
                    handler.postDelayed(this, 700)
                } else {
                    cursor?.close()
                    progress.visibility = View.GONE
                }
            }
        }
        handler.post(poll)
    }

    private fun openPlatform(link: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
        } catch (_: Exception) {
            Toast.makeText(this, "Could not open the platform link.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun safeFileName(input: String): String {
        val cleaned = input.substringAfterLast('/').substringBefore('?')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(90)
        return if (cleaned.isBlank()) "netha_media" else cleaned
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(linkInput.windowToken, 0)
        linkInput.clearFocus()
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        letterSpacing = 0.08f
        setTextColor(accent)
        setPadding(0, 0, 0, dp(10))
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        letterSpacing = 0.08f
        setTextColor(accent)
        setPadding(dp(2), dp(10), 0, dp(8))
    }

    private fun infoCard(heading: String, body: String): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(panel)
        }
        box.addView(TextView(this).apply {
            text = heading
            textSize = 13f
            setTextColor(Color.WHITE)
        }, matchWrap())
        box.addView(TextView(this).apply {
            text = body
            textSize = 12f
            setTextColor(muted)
            setPadding(0, dp(5), 0, 0)
        }, matchWrap())
        return box
    }

    private fun matchWrap() = LinearLayout.LayoutParams(-1, -2)
    private fun LinearMatch(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = top }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
