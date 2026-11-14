package com.nethax.ultimate

import android.Manifest
import android.app.Activity
import android.app.ActivityOptions
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.provider.Settings
import android.system.OsConstants
import android.text.Html
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingCameraRequest: PermissionRequest? = null

    private var textRecognizer: TextRecognizer? = null
    private var languageIdentifier: LanguageIdentifier? = null

    private val translationExecutor = Executors.newSingleThreadExecutor()
    private val translationStateLock = Any()

    @Volatile
    private var lastProcessedText = ""

    @Volatile
    private var lastDetectedLanguage = ""

    @Volatile
    private var lastTranslation = ""

    @Volatile
    private var activeTranslationSessionId = 0

    @Volatile
    private var isActivityDestroyed = false

    companion object {
        private const val FILE_CHOOSER_REQUEST_CODE = 1001
        private const val CAMERA_PERMISSION_REQUEST_CODE = 2001

        private const val CACHE_PERMISSION_REQUEST_CODE = 3001
        private const val CACHE_CLEAR_REQUEST_CODE = 3002

        private const val MAX_FRAME_BASE64_LENGTH = 5_000_000
        private const val MAX_TRANSLATION_BYTES = 450
    }

    private fun isActiveSession(sessionId: Int): Boolean {
        return !isActivityDestroyed &&
            activeTranslationSessionId == sessionId
    }

    private fun resetTranslationState(sessionId: Int) {
        synchronized(translationStateLock) {
            activeTranslationSessionId = sessionId
            lastProcessedText = ""
            lastDetectedLanguage = ""
            lastTranslation = ""
        }
    }

    private fun getTextRecognizer(): TextRecognizer {
        return textRecognizer ?: TextRecognition
            .getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            .also {
                textRecognizer = it
            }
    }

    private fun getLanguageIdentifier(): LanguageIdentifier {
        return languageIdentifier ?: LanguageIdentification
            .getClient()
            .also {
                languageIdentifier = it
            }
    }

    private fun recycleBitmap(bitmap: Bitmap?) {
        try {
            if (bitmap != null && !bitmap.isRecycled) {
                bitmap.recycle()
            }
        } catch (_: Exception) {
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true

        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler(
                "/assets/",
                WebViewAssetLoader.AssetsPathHandler(this)
            )
            .build()

        webView.webViewClient = object : WebViewClient() {

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                return request?.url?.let {
                    assetLoader.shouldInterceptRequest(it)
                }
            }

            @Suppress("DEPRECATION")
            override fun shouldInterceptRequest(
                view: WebView?,
                url: String?
            ): WebResourceResponse? {
                return url?.let {
                    assetLoader.shouldInterceptRequest(Uri.parse(it))
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return true

                return !(
                    uri.scheme == "https" &&
                    uri.host == "appassets.androidplatform.net"
                )
            }
        }

        webView.addJavascriptInterface(
            AndroidBridge(),
            "AndroidBridge"
        )

        webView.webChromeClient = object : WebChromeClient() {

            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {

                    val trustedOrigin =
                        request.origin.scheme == "https" &&
                        request.origin.host == "appassets.androidplatform.net"

                    val wantsCamera = request.resources.contains(
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE
                    )

                    if (!trustedOrigin || !wantsCamera) {
                        request.deny()
                        return@runOnUiThread
                    }

                    if (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                        checkSelfPermission(Manifest.permission.CAMERA) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {

                        if (pendingCameraRequest != null) {
                            request.deny()
                            return@runOnUiThread
                        }

                        pendingCameraRequest = request

                        requestPermissions(
                            arrayOf(Manifest.permission.CAMERA),
                            CAMERA_PERMISSION_REQUEST_CODE
                        )

                    } else {
                        request.grant(
                            arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                        )
                    }
                }
            }

            override fun onPermissionRequestCanceled(
                request: PermissionRequest
            ) {
                runOnUiThread {
                    if (pendingCameraRequest === request) {
                        pendingCameraRequest = null
                    }
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {

                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback

                return try {

                    val intent = params?.createIntent()
                        ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "image/*"
                        }

                    startActivityForResult(
                        intent,
                        FILE_CHOOSER_REQUEST_CODE
                    )

                    true

                } catch (e: Exception) {

                    filePathCallback?.onReceiveValue(null)
                    filePathCallback = null
                    false
                }
            }
        }

        setContentView(webView)

        webView.loadUrl(
            "https://appassets.androidplatform.net/assets/splash.html"
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {

            val request = pendingCameraRequest
            pendingCameraRequest = null

            if (request == null) return

            if (
                grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                request.grant(
                    arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                )
            } else {
                request.deny()
            }
        }
    }

    inner class AndroidBridge {

        @JavascriptInterface
        fun requestCacheClear() {

            runOnUiThread {

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    sendCacheClearStatus(
                        "This cache cleaning feature requires Android 11 or newer.",
                        false
                    )
                    return@runOnUiThread
                }

                if (!Environment.isExternalStorageManager()) {

                    try {

                        val permissionIntent = Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName")
                        )

                        startActivityForResult(
                            permissionIntent,
                            CACHE_PERMISSION_REQUEST_CODE
                        )

                        sendCacheClearStatus(
                            "Please enable All files access for NETHA X ULTIMATE, then return and tap Clear Cache again.",
                            false
                        )

                    } catch (e: Exception) {

                        sendCacheClearStatus(
                            "Unable to open storage permission settings.",
                            false
                        )
                    }

                    return@runOnUiThread
                }

                launchSystemCacheClear()
            }
        }

        @JavascriptInterface
        fun resetTranslation(sessionId: Int) {
            resetTranslationState(sessionId)
        }

        @JavascriptInterface
        fun saveImage(dataUrl: String) {

            try {

                val base64Data = dataUrl.substringAfter(",", "")

                if (base64Data.isBlank()) {
                    throw Exception("Invalid image data")
                }

                if (base64Data.length > MAX_FRAME_BASE64_LENGTH * 4) {
                    throw Exception("Image is too large")
                }

                val imageBytes = Base64.decode(
                    base64Data,
                    Base64.DEFAULT
                )

                if (imageBytes.size > 20 * 1024 * 1024) {
                    throw Exception("Image is too large")
                }

                val values = ContentValues().apply {

                    put(
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        "NETHA-${System.currentTimeMillis()}.png"
                    )

                    put(
                        MediaStore.MediaColumns.MIME_TYPE,
                        "image/png"
                    )

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

                        put(
                            MediaStore.MediaColumns.RELATIVE_PATH,
                            "Pictures/NETHA X"
                        )

                        put(
                            MediaStore.MediaColumns.IS_PENDING,
                            1
                        )
                    }
                }

                val imageUri = contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values
                ) ?: throw Exception("Gallery save failed")

                try {

                    contentResolver.openOutputStream(imageUri)?.use {
                        it.write(imageBytes)
                    } ?: throw Exception("Cannot open image file")

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

                        val completedValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.IS_PENDING, 0)
                        }

                        contentResolver.update(
                            imageUri,
                            completedValues,
                            null,
                            null
                        )
                    }

                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "Image saved to Gallery!",
                            Toast.LENGTH_LONG
                        ).show()
                    }

                } catch (e: Exception) {

                    contentResolver.delete(imageUri, null, null)
                    throw e
                }

            } catch (e: Exception) {

                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "Download failed: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        @JavascriptInterface
        fun processTranslationFrame(
            dataUrl: String,
            sessionId: Int
        ) {

            if (!isActiveSession(sessionId)) return

            var bitmap: Bitmap? = null

            try {

                val base64Data = dataUrl.substringAfter(",", "")

                if (
                    base64Data.isBlank() ||
                    base64Data.length > MAX_FRAME_BASE64_LENGTH
                ) {
                    sendTCResult(
                        "",
                        "",
                        "",
                        "Invalid camera frame",
                        sessionId
                    )
                    return
                }

                val imageBytes = Base64.decode(
                    base64Data,
                    Base64.DEFAULT
                )

                if (!isActiveSession(sessionId)) return

                bitmap = BitmapFactory.decodeByteArray(
                    imageBytes,
                    0,
                    imageBytes.size
                ) ?: throw Exception("Cannot read camera frame")

                val frameBitmap = bitmap
                val inputImage = InputImage.fromBitmap(frameBitmap, 0)

                getTextRecognizer()
                    .process(inputImage)
                    .addOnSuccessListener { visionText ->

                        recycleBitmap(frameBitmap)
                        bitmap = null

                        if (!isActiveSession(sessionId)) return@addOnSuccessListener

                        val detectedText = visionText.text.trim()

                        if (detectedText.isBlank()) {
                            sendTCResult(
                                "",
                                "",
                                "",
                                "Looking for text...",
                                sessionId
                            )
                            return@addOnSuccessListener
                        }

                        val isUnchanged = synchronized(translationStateLock) {

                            if (
                                activeTranslationSessionId != sessionId ||
                                isActivityDestroyed
                            ) {
                                return@synchronized false
                            }

                            detectedText == lastProcessedText
                        }

                        if (!isActiveSession(sessionId)) {
                            return@addOnSuccessListener
                        }

                        if (isUnchanged) {
                            sendTCResult(
                                detectedText,
                                "unchanged",
                                "",
                                "Text detected — no change",
                                sessionId
                            )
                            return@addOnSuccessListener
                        }

                        synchronized(translationStateLock) {

                            if (
                                activeTranslationSessionId != sessionId ||
                                isActivityDestroyed
                            ) {
                                return@synchronized
                            }

                            lastProcessedText = detectedText
                            lastDetectedLanguage = ""
                            lastTranslation = ""
                        }

                        if (!isActiveSession(sessionId)) {
                            return@addOnSuccessListener
                        }

                        getLanguageIdentifier()
                            .identifyLanguage(detectedText)
                            .addOnSuccessListener { languageCode ->

                                if (!isActiveSession(sessionId)) {
                                    return@addOnSuccessListener
                                }

                                if (
                                    languageCode == "und" ||
                                    languageCode.isBlank()
                                ) {
                                    sendTCResult(
                                        detectedText,
                                        "Unknown",
                                        "",
                                        "Language could not be detected",
                                        sessionId
                                    )
                                    return@addOnSuccessListener
                                }

                                synchronized(translationStateLock) {

                                    if (
                                        activeTranslationSessionId != sessionId ||
                                        isActivityDestroyed
                                    ) {
                                        return@synchronized
                                    }

                                    lastDetectedLanguage = languageCode
                                }

                                if (!isActiveSession(sessionId)) {
                                    return@addOnSuccessListener
                                }

                                translateToSinhala(
                                    detectedText,
                                    languageCode,
                                    sessionId
                                )
                            }
                            .addOnFailureListener {

                                if (!isActiveSession(sessionId)) {
                                    return@addOnFailureListener
                                }

                                sendTCResult(
                                    detectedText,
                                    "Unknown",
                                    "",
                                    "Language detection failed",
                                    sessionId
                                )
                            }
                    }
                    .addOnFailureListener { error ->

                        recycleBitmap(frameBitmap)
                        bitmap = null

                        if (!isActiveSession(sessionId)) {
                            return@addOnFailureListener
                        }

                        sendTCResult(
                            "",
                            "",
                            "",
                            "OCR error: ${error.message ?: "Try again"}",
                            sessionId
                        )
                    }

            } catch (e: Exception) {

                recycleBitmap(bitmap)

                if (!isActiveSession(sessionId)) return

                sendTCResult(
                    "",
                    "",
                    "",
                    "Frame error: ${e.message ?: "Try again"}",
                    sessionId
                )
            }
        }
    }

    // Launch Android's official system cache-clear confirmation.
    private fun launchSystemCacheClear() {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            sendCacheClearStatus(
                "Android 11 or newer is required.",
                false
            )
            return
        }

        try {

            val clearIntent = Intent(
                StorageManager.ACTION_CLEAR_APP_CACHE
            )

            if (Build.VERSION.SDK_INT >= 34) {

                val options = ActivityOptions.makeBasic()
                    .setShareIdentityEnabled(true)
                    .toBundle()

                startActivityForResult(
                    clearIntent,
                    CACHE_CLEAR_REQUEST_CODE,
                    options
                )

            } else {

                startActivityForResult(
                    clearIntent,
                    CACHE_CLEAR_REQUEST_CODE
                )
            }

            sendCacheClearStatus(
                "Android system cache cleanup confirmation opened.",
                false
            )

        } catch (e: Exception) {

            sendCacheClearStatus(
                "Could not start system cache cleanup: ${e.message ?: "Unknown error"}",
                false
            )
        }
    }

    private fun sendCacheClearStatus(
        status: String,
        success: Boolean
    ) {

        if (isActivityDestroyed || !::webView.isInitialized) return

        val result = JSONObject().apply {
            put("status", status)
            put("success", success)
        }

        runOnUiThread {

            if (isActivityDestroyed || !::webView.isInitialized) {
                return@runOnUiThread
            }

            webView.evaluateJavascript(
                "window.onCacheClearStatus && " +
                "window.onCacheClearStatus($result);",
                null
            )
        }
    }

    private fun translateToSinhala(
        originalText: String,
        detectedLanguage: String,
        sessionId: Int
    ) {

        if (!isActiveSession(sessionId)) return

        val sourceLanguage = detectedLanguage
            .substringBefore("-")
            .lowercase()

        if (sourceLanguage == "si") {

            synchronized(translationStateLock) {

                if (
                    activeTranslationSessionId != sessionId ||
                    isActivityDestroyed
                ) {
                    return
                }

                lastTranslation = originalText
            }

            sendTCResult(
                originalText,
                detectedLanguage,
                originalText,
                "Text is already in Sinhala",
                sessionId
            )
            return
        }

        try {

            translationExecutor.execute {

                if (!isActiveSession(sessionId)) return@execute

                try {

                    val textBytes = originalText.toByteArray(Charsets.UTF_8)

                    if (textBytes.size > MAX_TRANSLATION_BYTES) {
                        throw Exception(
                            "Text is too long. Please focus on a shorter text."
                        )
                    }

                    val encodedText = URLEncoder.encode(
                        originalText,
                        "UTF-8"
                    )

                    val languagePair = "$sourceLanguage|si"

                    val encodedPair = URLEncoder.encode(
                        languagePair,
                        "UTF-8"
                    )

                    val url = URL(
                        "https://api.mymemory.translated.net/get" +
                        "?q=$encodedText&langpair=$encodedPair"
                    )

                    val connection = url.openConnection()
                        as HttpURLConnection

                    connection.requestMethod = "GET"
                    connection.connectTimeout = 10000
                    connection.readTimeout = 10000

                    connection.setRequestProperty(
                        "User-Agent",
                        "NETHA-X-ULTIMATE"
                    )

                    try {

                        if (!isActiveSession(sessionId)) {
                            return@execute
                        }

                        val responseCode = connection.responseCode

                        if (!isActiveSession(sessionId)) {
                            return@execute
                        }

                        val responseStream =
                            if (responseCode in 200..299) {
                                connection.inputStream
                            } else {
                                connection.errorStream
                            }

                        val response = responseStream
                            ?.bufferedReader()
                            ?.use { it.readText() }
                            ?: throw Exception("Empty translation response")

                        if (!isActiveSession(sessionId)) {
                            return@execute
                        }

                        if (responseCode !in 200..299) {
                            throw Exception(
                                "Translation service error: $responseCode"
                            )
                        }

                        val json = JSONObject(response)

                        val responseStatus = json.optInt(
                            "responseStatus",
                            0
                        )

                        if (responseStatus != 200) {
                            throw Exception(
                                json.optString(
                                    "responseDetails",
                                    "Translation unavailable"
                                )
                            )
                        }

                        val translated = json
                            .optJSONObject("responseData")
                            ?.optString("translatedText", "")
                            ?.takeIf { it.isNotBlank() }
                            ?: throw Exception("No translated text returned")

                        val cleanTranslation =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                Html.fromHtml(
                                    translated,
                                    Html.FROM_HTML_MODE_LEGACY
                                ).toString()
                            } else {
                                @Suppress("DEPRECATION")
                                Html.fromHtml(translated).toString()
                            }

                        if (!isActiveSession(sessionId)) {
                            return@execute
                        }

                        synchronized(translationStateLock) {

                            if (
                                activeTranslationSessionId != sessionId ||
                                isActivityDestroyed
                            ) {
                                return@synchronized
                            }

                            lastTranslation = cleanTranslation
                        }

                        sendTCResult(
                            originalText,
                            detectedLanguage,
                            cleanTranslation,
                            "Translation complete",
                            sessionId
                        )

                    } finally {
                        connection.disconnect()
                    }

                } catch (e: Exception) {

                    if (!isActiveSession(sessionId)) {
                        return@execute
                    }

                    sendTCResult(
                        originalText,
                        detectedLanguage,
                        "",
                        "Translation failed: ${e.message ?: "Check internet connection"}",
                        sessionId
                    )
                }
            }

        } catch (e: Exception) {

            if (!isActiveSession(sessionId)) return

            sendTCResult(
                originalText,
                detectedLanguage,
                "",
                "Translation failed: ${e.message ?: "Please try again"}",
                sessionId
            )
        }
    }

    private fun sendTCResult(
        text: String,
        language: String,
        translation: String,
        status: String,
        sessionId: Int
    ) {

        if (!isActiveSession(sessionId) || !::webView.isInitialized) {
            return
        }

        val result = JSONObject().apply {
            put("text", text)
            put("language", language)
            put("translation", translation)
            put("status", status)
            put("sessionId", sessionId)
        }

        runOnUiThread {

            if (!isActiveSession(sessionId)) {
                return@runOnUiThread
            }

            if (!::webView.isInitialized) {
                return@runOnUiThread
            }

            webView.evaluateJavascript(
                "window.onTCNativeResult && " +
                "window.onTCNativeResult($result);",
                null
            )
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {

            FILE_CHOOSER_REQUEST_CODE -> {

                val result = if (resultCode == Activity.RESULT_OK) {

                    WebChromeClient.FileChooserParams.parseResult(
                        resultCode,
                        data
                    )

                } else {
                    null
                }

                filePathCallback?.onReceiveValue(result)
                filePathCallback = null
            }

            CACHE_PERMISSION_REQUEST_CODE -> {

                if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    Environment.isExternalStorageManager()
                ) {

                    sendCacheClearStatus(
                        "Storage access granted. Return to Mobile Optimizer and tap Clear Cache again.",
                        true
                    )

                } else {

                    sendCacheClearStatus(
                        "All files access was not enabled. Cache cleanup was not started.",
                        false
                    )
                }
            }

            CACHE_CLEAR_REQUEST_CODE -> {

                when (resultCode) {

                    Activity.RESULT_OK -> {
                        sendCacheClearStatus(
                            "Android reports that the app cache cleanup completed.",
                            true
                        )
                    }

                    OsConstants.EIO -> {
                        sendCacheClearStatus(
                            "Android reported an error while clearing cache.",
                            false
                        )
                    }

                    else -> {
                        sendCacheClearStatus(
                            "Android did not confirm cache cleanup. The system may have cancelled the request or the operation may not have started.",
                            false
                        )
                    }
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {

        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {

        isActivityDestroyed = true

        synchronized(translationStateLock) {
            activeTranslationSessionId++
            lastProcessedText = ""
            lastDetectedLanguage = ""
            lastTranslation = ""
        }

        try {
            pendingCameraRequest?.deny()
        } catch (_: Exception) {
        }

        pendingCameraRequest = null

        filePathCallback?.onReceiveValue(null)
        filePathCallback = null

        translationExecutor.shutdownNow()

        textRecognizer?.close()
        textRecognizer = null

        languageIdentifier?.close()
        languageIdentifier = null

        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.removeJavascriptInterface("AndroidBridge")
            webView.destroy()
        }

        super.onDestroy()
    }
}
