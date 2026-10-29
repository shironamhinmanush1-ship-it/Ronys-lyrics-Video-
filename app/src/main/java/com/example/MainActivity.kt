package com.example

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.example.ui.theme.MyApplicationTheme
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

class MainActivity : ComponentActivity() {

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Register Activity Result Launcher for WebView file picker (<input type="file">)
        fileChooserLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (filePathCallback != null) {
                val data = result.data
                val results: Array<Uri>? = if (result.resultCode == Activity.RESULT_OK && data != null) {
                    val clipData = data.clipData
                    if (clipData != null) {
                        Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
                    } else {
                        data.data?.let { arrayOf(it) }
                    }
                } else {
                    null
                }
                filePathCallback?.onReceiveValue(results)
                filePathCallback = null
            }
        }

        setContent {
            MyApplicationTheme {
                var webViewRef by remember { mutableStateOf<WebView?>(null) }

                BackHandler(enabled = webViewRef?.canGoBack() == true) {
                    webViewRef?.goBack()
                }

                LyricCraftScreen(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0A0B10))
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .imePadding(),
                    onWebViewCreated = { webView ->
                        webViewRef = webView
                    },
                    onOpenFileChooser = { callback, params ->
                        filePathCallback?.onReceiveValue(null)
                        filePathCallback = callback

                        val intent = params.createIntent().apply {
                            type = "*/*"
                            if (params.acceptTypes.isNotEmpty() && params.acceptTypes[0].isNotEmpty()) {
                                putExtra(Intent.EXTRA_MIME_TYPES, params.acceptTypes)
                            }
                        }

                        try {
                            fileChooserLauncher.launch(
                                Intent.createChooser(intent, "ফাইল নির্বাচন করুন")
                            )
                        } catch (e: Exception) {
                            filePathCallback = null
                        }
                    }
                )
            }
        }
    }

    /**
     * JavaScript Bridge exposing native Android functionalities to the HTML5 app
     */
    inner class WebAppInterface(private val context: Context) {

        @JavascriptInterface
        fun getGeminiApiKey(): String {
            return try {
                val field = BuildConfig::class.java.getField("GEMINI_API_KEY")
                val key = field.get(null) as? String ?: ""
                if (key.startsWith("MY_") || key.isEmpty()) "" else key
            } catch (e: Exception) {
                ""
            }
        }

        @JavascriptInterface
        fun showToast(message: String) {
            runOnUiThread {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun saveVideo(base64Data: String, filename: String, mimeType: String) {
            runOnUiThread {
                try {
                    val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                    var savedUri: Uri? = null

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val contentValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        }
                        val resolver = context.contentResolver
                        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                        if (uri != null) {
                            resolver.openOutputStream(uri)?.use { os: OutputStream ->
                                os.write(bytes)
                                os.flush()
                            }
                            savedUri = uri
                        }
                    } else {
                        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        downloadsDir.mkdirs()
                        val file = File(downloadsDir, filename)
                        FileOutputStream(file).use { fos ->
                            fos.write(bytes)
                            fos.flush()
                        }
                        savedUri = Uri.fromFile(file)
                    }

                    Toast.makeText(
                        context,
                        "ভিডিও ডাউনলোড ফোল্ডারে সেভ হয়েছে: $filename",
                        Toast.LENGTH_LONG
                    ).show()

                    // Also provide a quick share/view option
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = mimeType
                        val cacheFile = File(context.cacheDir, filename)
                        FileOutputStream(cacheFile).use { it.write(bytes) }
                        val contentUri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            cacheFile
                        )
                        putExtra(Intent.EXTRA_STREAM, contentUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "ভিডিও শেয়ার বা সেভ করুন"))

                } catch (e: Exception) {
                    Toast.makeText(context, "সেভ করতে সমস্যা হয়েছে: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    fun LyricCraftScreen(
        modifier: Modifier = Modifier,
        onWebViewCreated: (WebView) -> Unit,
        onOpenFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Unit
    ) {
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundColor(0xFF0A0B10.toInt())

                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        allowFileAccess = true
                        allowContentAccess = true
                        mediaPlaybackRequiresUserGesture = false
                        loadWithOverviewMode = true
                        useWideViewPort = true
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        cacheMode = WebSettings.LOAD_DEFAULT
                    }

                    addJavascriptInterface(WebAppInterface(ctx), "AndroidBridge")

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                            return false // Handle all local links inside WebView
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onShowFileChooser(
                            webView: WebView?,
                            filePathCallback: ValueCallback<Array<Uri>>?,
                            fileChooserParams: FileChooserParams?
                        ): Boolean {
                            if (filePathCallback != null && fileChooserParams != null) {
                                onOpenFileChooser(filePathCallback, fileChooserParams)
                                return true
                            }
                            return false
                        }

                        override fun onPermissionRequest(request: PermissionRequest?) {
                            // Grant audio capture permissions for Web Audio recording
                            request?.grant(request.resources)
                        }
                    }

                    loadUrl("file:///android_asset/index.html")
                    onWebViewCreated(this)
                }
            }
        )
    }
}
