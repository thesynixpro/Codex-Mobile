package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private lateinit var rootContainer: FrameLayout
    private var webView: WebView? = null
    private lateinit var bridge: AndroidBridge
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var isPickingDirectory = false

    private val directoryPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        isPickingDirectory = false
        if (uri != null) {
            val preliminaryName = DocumentTreeHelper.extractDisplayName(contentResolver, uri)
            val prelimNameQuote = JSONObject.quote(preliminaryName)
            val uriQuote = JSONObject.quote(uri.toString())

            // Immediately notify web UI that directory was picked so the popup is closed right away!
            evaluateJavascript(
                "window.onAndroidDirectoryStarted && window.onAndroidDirectoryStarted($prelimNameQuote, $uriQuote);"
            )

            try {
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (e: Exception) {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e2: Exception) {
                    e2.printStackTrace()
                }
            }

            // Persist URI in SharedPreferences for seamless reload
            try {
                val prefs = getSharedPreferences("codex_prefs", Context.MODE_PRIVATE)
                prefs.edit().putString("last_project_uri", uri.toString()).apply()
            } catch (e: Exception) {
                e.printStackTrace()
            }

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val treeJson = DocumentTreeHelper.buildDirectoryTree(this@MainActivity, uri)
                    val rootName = treeJson.optString("name", preliminaryName)
                    withContext(Dispatchers.Main) {
                        val treeStr = JSONObject.quote(treeJson.toString())
                        val nameStr = JSONObject.quote(rootName)
                        evaluateJavascript(
                            "window.onAndroidDirectorySelected && window.onAndroidDirectorySelected($treeStr, $nameStr, $uriQuote);"
                        )
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        val errorStr = JSONObject.quote(e.message ?: "Failed to read project directory")
                        evaluateJavascript(
                            "window.onAndroidDirectoryError && window.onAndroidDirectoryError($errorStr);"
                        )
                    }
                }
            }
        } else {
            evaluateJavascript(
                "window.onAndroidDirectoryCancelled && window.onAndroidDirectoryCancelled();"
            )
        }
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            filePathCallback?.onReceiveValue(uris.toTypedArray())
        } else {
            filePathCallback?.onReceiveValue(null)
        }
        filePathCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        super.onCreate(savedInstanceState)
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        ensureWebViewCacheDirs()
        enableEdgeToEdge()

        rootContainer = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#090D13"))
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }

        setContentView(rootContainer)
        window.decorView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)

        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { view, insets ->
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                if (imeInsets.bottom > 0) imeInsets.bottom else systemBars.bottom
            )
            insets
        }

        bridge = AndroidBridge(this, lifecycleScope)
        initWebView()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = webView
                if (wv != null) {
                    wv.evaluateJavascript("window.handleAndroidBack && window.handleAndroidBack();") { result ->
                        if (result != "\"handled\"" && result != "true") {
                            if (wv.canGoBack()) {
                                wv.goBack()
                            } else {
                                isEnabled = false
                                onBackPressedDispatcher.onBackPressed()
                            }
                        }
                    }
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun ensureWebViewCacheDirs() {
        try {
            val webViewDir = File(cacheDir, "WebView")
            val codeCacheDir = File(webViewDir, "Default/HTTP Cache/Code Cache")
            val jsDir = File(codeCacheDir, "js")
            val wasmDir = File(codeCacheDir, "wasm")

            // Remove any invalid subdirectories or stale fake indexes from previous attempts
            val jsIndex = File(jsDir, "index-dir")
            val wasmIndex = File(wasmDir, "index-dir")
            if (jsIndex.exists() || wasmIndex.exists()) {
                codeCacheDir.deleteRecursively()
            }

            // Ensure pure, clean empty leaf directories exist so opendir succeeds without triggering version upgrade
            if (!jsDir.exists()) {
                jsDir.mkdirs()
            }
            if (!wasmDir.exists()) {
                wasmDir.mkdirs()
            }

            // Clean any files inside them to ensure Chromium treats them as a clean new cache
            jsDir.listFiles()?.forEach { it.deleteRecursively() }
            wasmDir.listFiles()?.forEach { it.deleteRecursively() }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun initWebView() {
        ensureWebViewCacheDirs()

        val newWebView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#090D13"))
            overScrollMode = View.OVER_SCROLL_NEVER
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }
        webView = newWebView
        rootContainer.removeAllViews()
        rootContainer.addView(newWebView)

        configureWebView(newWebView)
        newWebView.loadUrl("file:///android_asset/web/index.html")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(wv: WebView) {
        val settings = wv.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        settings.textZoom = 100

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        wv.addJavascriptInterface(bridge, "AndroidBridge")

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                return !url.startsWith("file:///android_asset/")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // When page finishes loading, check if a valid persisted project URI is stored
                checkAndNotifyPersistedProject()
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                val didCrash = detail?.didCrash() ?: false
                android.util.Log.w("MainActivity", "WebView render process exited (crashed=$didCrash). Recovering...")
                try {
                    (view?.parent as? ViewGroup)?.removeView(view)
                    view?.destroy()
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Error cleaning up dead WebView: ${e.message}")
                }
                initWebView()
                return true
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                android.util.Log.w(
                    "MainActivity",
                    "WebView resource error: ${error?.description} for ${request?.url}"
                )
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                if (consoleMessage != null) {
                    android.util.Log.d(
                        "CodexWebConsole",
                        "${consoleMessage.sourceId()}:${consoleMessage.lineNumber()} [${consoleMessage.messageLevel()}] ${consoleMessage.message()}"
                    )
                }
                return super.onConsoleMessage(consoleMessage)
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                filePickerLauncher.launch(arrayOf("*/*"))
                return true
            }
        }
    }

    fun evaluateJavascript(script: String) {
        runOnUiThread {
            webView?.evaluateJavascript(script, null)
        }
    }

    fun launchDirectoryPicker() {
        if (isPickingDirectory) return
        isPickingDirectory = true
        try {
            directoryPickerLauncher.launch(null)
        } catch (e: Exception) {
            isPickingDirectory = false
            e.printStackTrace()
            val errorStr = JSONObject.quote(e.message ?: "Could not open folder picker")
            evaluateJavascript("window.onAndroidDirectoryError && window.onAndroidDirectoryError($errorStr);")
        }
    }

    fun getPersistedProjectUri(): String? {
        val prefs = getSharedPreferences("codex_prefs", Context.MODE_PRIVATE)
        val uriStr = prefs.getString("last_project_uri", null) ?: return null
        return try {
            val uri = Uri.parse(uriStr)
            val hasPerm = contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
            if (hasPerm) uriStr else null
        } catch (e: Exception) {
            null
        }
    }

    fun clearPersistedProjectUri() {
        val prefs = getSharedPreferences("codex_prefs", Context.MODE_PRIVATE)
        prefs.edit().remove("last_project_uri").apply()
    }

    private fun checkAndNotifyPersistedProject() {
        val uriStr = getPersistedProjectUri() ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val uri = Uri.parse(uriStr)
                val treeJson = DocumentTreeHelper.buildDirectoryTree(this@MainActivity, uri)
                val rootName = treeJson.optString("name", "Project")
                withContext(Dispatchers.Main) {
                    val treeStr = JSONObject.quote(treeJson.toString())
                    val nameStr = JSONObject.quote(rootName)
                    val qUriStr = JSONObject.quote(uriStr)
                    evaluateJavascript(
                        "window.onAndroidDirectoryAutoLoaded && window.onAndroidDirectoryAutoLoaded($treeStr, $nameStr, $qUriStr);"
                    )
                }
            } catch (e: Exception) {
                // Permission might have been revoked, clear it
                clearPersistedProjectUri()
            }
        }
    }
}
