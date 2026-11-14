package com.nethax.ultimate

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.ValueCallback
import android.webkit.JavascriptInterface
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    companion object {
        private const val FILE_CHOOSER_REQUEST_CODE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true

        webView.webViewClient = WebViewClient()

        // Android Gallery Download Bridge
        webView.addJavascriptInterface(
            AndroidBridge(),
            "AndroidBridge"
        )

        // Android File Picker
        webView.webChromeClient = object : WebChromeClient() {

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

        webView.loadUrl("file:///android_asset/splash.html")
    }

    // Save PNG directly to Android Gallery
    inner class AndroidBridge {

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
                            put(
                                MediaStore.MediaColumns.IS_PENDING,
                                0
                            )
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
    }

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

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
