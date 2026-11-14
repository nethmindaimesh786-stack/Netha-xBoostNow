package com.nethax.ultimate

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.nl.languageid.LanguageIdentification
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

    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val languageIdentifier by lazy {
        LanguageIdentification.getClient()
    }

    private val translationExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var lastProcessedText = ""

    @Volatile
    private var isActivityDestroyed = false

    companion object {
        private const val FILE_CHOOSER_REQUEST_CODE = 1001
        private const val CAMERA_PERMISSION_REQUEST_CODE = 2001
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

        // Save PNG to Gallery
        @JavascriptInterface
        fun saveImage(dataUrl: String) {

            try {

                val base64Data = dataUrl.substringAfter(",", "")

                if (base64Data.isBlank()) {
                    throw Exception("Invalid image data")
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

            try {

                val base64Data = dataUrl.substringAfter(",", "")

                if (base64Data.isBlank() || base64Data.length > 5_000_000) {
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

                val bitmap = BitmapFactory.decodeByteArray(
                    imageBytes,
                    0,
                    imageBytes.size
                ) ?: throw Exception("Cannot read camera frame")

                val inputImage = InputImage.fromBitmap(bitmap, 0)

                textRecognizer.process(inputImage)
                    .addOnSuccessListener { visionText ->

                        val detectedText = visionText.text.trim()

                        if (detectedText.isBlank()) {
                            sendTCResult(
                                "",
                                "",
                                "",
                                "Looking for text..."
                            )
                            bitmap.recycle()
                            return@addOnSuccessListener
                        }

                        if (detectedText == lastProcessedText) {
                            bitmap.recycle()
                            return@addOnSuccessListener
                        }

                        lastProcessedText = detectedText

                        languageIdentifier.identifyLanguage(detectedText)
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
                                    bitmap.recycle()
                                    return@addOnSuccessListener
                                }

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

                        bitmap.recycle()
                    }
                    .addOnFailureListener { error ->

                        bitmap.recycle()

                        sendTCResult(
                            "",
                            "",
                            "",
                            "OCR error: ${error.message ?: "Try again"}"
                        )
                    }

            } catch (e: Exception) {

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
            sendTCResult(
                originalText,
                detectedLanguage,
                originalText,
                "Text is already in Sinhala"
            )
            return
        }

        translationExecutor.execute {

            try {

                val textToTranslate = limitUtf8Bytes(
                    originalText,
                    450
                )

                val encodedText = URLEncoder.encode(
                    textToTranslate,
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
                        throw Exception("Translation service error: $responseCode")
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
    }

    // Keep MyMemory request below its text-size limit
    private fun limitUtf8Bytes(
        text: String,
        maxBytes: Int
    ): String {

        val result = StringBuilder()
        var currentBytes = 0

        for (character in text) {

            val characterString = character.toString()
            val characterBytes = characterString
                .toByteArray(Charsets.UTF_8)
                .size

            if (currentBytes + characterBytes > maxBytes) {
                break
            }

            result.append(character)
            currentBytes += characterBytes
        }

        return result.toString()
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

        pendingCameraRequest?.deny()
        pendingCameraRequest = null

        filePathCallback?.onReceiveValue(null)
        filePathCallback = null

        translationExecutor.shutdownNow()

        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.removeJavascriptInterface("AndroidBridge")
            webView.destroy()
        }

        if (::webView.isInitialized) {
            textRecognizer.close()
            languageIdentifier.close()
        }

        super.onDestroy()
    }
}
