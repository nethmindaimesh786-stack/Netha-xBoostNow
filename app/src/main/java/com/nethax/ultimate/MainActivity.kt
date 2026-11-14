package com.nethax.ultimate

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
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

    // Initialize ML Kit only when required.
    private var textRecognizer: TextRecognizer? = null
    private var languageIdentifier: LanguageIdentifier? = null

    private val translationExecutor = Executors.newSingleThreadExecutor()

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
        private const val MAX_FRAME_BASE64_LENGTH = 5_000_000
        private const val MAX_TRANSLATION_BYTES = 450
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

                // Keep the JavaScript bridge inside the local app origin.
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

    // Camera Permission Result
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

// Reset previous translation when camera starts again
@JavascriptInterface
fun resetTranslation() {
    lastProcessedText = ""
    lastDetectedLanguage = ""
    lastTranslation = ""
}       
        // Save PNG to Gallery
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

        // Receive camera frame from JavaScript for OCR
        @JavascriptInterface
        fun processTranslationFrame(dataUrl: String) {

            if (isActivityDestroyed) return

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
                        "Invalid camera frame"
                    )
                    return
                }

                val imageBytes = Base64.decode(
                    base64Data,
                    Base64.DEFAULT
                )

                bitmap = BitmapFactory.decodeByteArray(
                    imageBytes,
                    0,
                    imageBytes.size
                ) ?: throw Exception("Cannot read camera frame")

                val inputImage = InputImage.fromBitmap(bitmap, 0)

                getTextRecognizer()
                    .process(inputImage)
                    .addOnSuccessListener { visionText ->

                        val detectedText = visionText.text.trim()

                        // OCR has finished using this bitmap.
                        bitmap?.let {
                            if (!it.isRecycled) it.recycle()
                        }
                        bitmap = null

                        if (detectedText.isBlank()) {
                            sendTCResult(
                                "",
                                "",
                                "",
                                "Looking for text..."
                            )
                            return@addOnSuccessListener
                        }

                        // Important: always respond to repeated frames.
                        // Otherwise JavaScript may remain locked in processing state.
                        if (detectedText == lastProcessedText) {
                            sendTCResult(
                                detectedText,
                                "unchanged",
                                "",
                                "Text detected — no change"
                            )
                            return@addOnSuccessListener
                        }

                        lastProcessedText = detectedText
                        lastDetectedLanguage = ""
                        lastTranslation = ""

                        getLanguageIdentifier()
                            .identifyLanguage(detectedText)
                            .addOnSuccessListener { languageCode ->

                                if (
                                    languageCode == "und" ||
                                    languageCode.isBlank()
                                ) {
                                    sendTCResult(
                                        detectedText,
                                        "Unknown",
                                        "",
                                        "Language could not be detected"
                                    )
                                    return@addOnSuccessListener
                                }

                                lastDetectedLanguage = languageCode

                                translateToSinhala(
                                    detectedText,
                                    languageCode
                                )
                            }
                            .addOnFailureListener {

                                sendTCResult(
                                    detectedText,
                                    "Unknown",
                                    "",
                                    "Language detection failed"
                                )
                            }
                    }
                    .addOnFailureListener { error ->

                        bitmap?.let {
                            if (!it.isRecycled) it.recycle()
                        }
                        bitmap = null

                        sendTCResult(
                            "",
                            "",
                            "",
                            "OCR error: ${error.message ?: "Try again"}"
                        )
                    }

            } catch (e: Exception) {

                bitmap?.let {
                    if (!it.isRecycled) it.recycle()
                }

                sendTCResult(
                    "",
                    "",
                    "",
                    "Frame error: ${e.message ?: "Try again"}"
                )
            }
        }
    }

    // Translate detected text into Sinhala using MyMemory
    private fun translateToSinhala(
        originalText: String,
        detectedLanguage: String
    ) {

        val sourceLanguage = detectedLanguage
            .substringBefore("-")
            .lowercase()

        if (sourceLanguage == "si") {

            lastTranslation = originalText

            sendTCResult(
                originalText,
                detectedLanguage,
                originalText,
                "Text is already in Sinhala"
            )
            return
        }

        try {

            translationExecutor.execute {

                try {

                    // Do not silently translate only part of the text.
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

                        val responseCode = connection.responseCode

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

                        lastTranslation = cleanTranslation

                        sendTCResult(
                            originalText,
                            detectedLanguage,
                            cleanTranslation,
                            "Translation complete"
                        )

                    } finally {
                        connection.disconnect()
                    }

                } catch (e: Exception) {

                    sendTCResult(
                        originalText,
                        detectedLanguage,
                        "",
                        "Translation failed: ${e.message ?: "Check internet connection"}"
                    )
                }
            }

        } catch (e: Exception) {

            sendTCResult(
                originalText,
                detectedLanguage,
                "",
                "Translation failed: ${e.message ?: "Please try again"}"
            )
        }
    }

    // Send OCR and translation results to the webpage
    private fun sendTCResult(
        text: String,
        language: String,
        translation: String,
        status: String
    ) {

        if (isActivityDestroyed || !::webView.isInitialized) return

        val result = JSONObject().apply {
            put("text", text)
            put("language", language)
            put("translation", translation)
            put("status", status)
        }

        runOnUiThread {

            if (isActivityDestroyed) return@runOnUiThread

            webView.evaluateJavascript(
                "window.onTCNativeResult && " +
                "window.onTCNativeResult(${result});",
                null
            )
        }
    }

    // Existing File Picker Result
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {

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
    }

    // Back Button
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

        try {
            pendingCameraRequest?.deny()
        } catch (_: Exception) {
        }

        pendingCameraRequest = null

        filePathCallback?.onReceiveValue(null)
        filePathCallback = null

        translationExecutor.shutdownNow()

        // Close only the ML Kit clients that were actually initialized.
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
