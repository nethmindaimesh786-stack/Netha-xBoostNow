package com.nethax.ultimate

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
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

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingCameraRequest: PermissionRequest? = null

    companion object {
        private const val FILE_CHOOSER_REQUEST_CODE = 1001
        private const val CAMERA_PERMISSION_REQUEST_CODE = 2001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true

        // Load local HTML through a secure app asset URL
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

        // Android Gallery Download Bridge
        webView.addJavascriptInterface(
            AndroidBridge(),
            "AndroidBridge"
        )

        // Camera Permission + Android File Picker
        webView.webChromeClient = object : WebChromeClient() {

            override fun onPermissionRequest(
                request: PermissionRequest
            ) {
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
                            arrayOf(
                                PermissionRequest.RESOURCE_VIDEO_CAPTURE
                            )
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

            // Existing Android File Picker
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

    // Handle Android Camera Permission Result
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
                    arrayOf(
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE
                    )
                )

            } else {
                request.deny()
            }
        }
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

        pendingCameraRequest?.deny()
        pendingCameraRequest = null

        filePathCallback?.onReceiveValue(null)
        filePathCallback = null

        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }

        super.onDestroy()
    }
}
