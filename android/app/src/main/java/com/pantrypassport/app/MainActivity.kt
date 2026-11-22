package com.pantrypassport.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import java.io.File

/**
 * Pantry Passport for Android.
 *
 * The web app is bundled at app/src/main/assets/www and served from
 * https://appassets.androidplatform.net/www/. Native help:
 *  - Photo picker and camera for ingredient photos and recipe scans.
 *  - Google Play subscription (Premium) through window.PantryNative.
 *  - Back closes an open recipe sheet before leaving the app.
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private var uploadCallback: ValueCallback<Array<Uri>>? = null
    private var cameraUri: Uri? = null
    private var backCallback: Any? = null
    private var billing: BillingManager? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WebView.setWebContentsDebuggingEnabled(
            (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        )

        val root = FrameLayout(this).apply { setBackgroundColor(BRAND) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        val loader = WebViewAssetLoader.Builder()
            .setDomain(HOST)
            .addPathHandler("/www/", WebViewAssetLoader.AssetsPathHandler(this).let { h ->
                WebViewAssetLoader.PathHandler { path -> h.handle("www/$path") }
            })
            .build()

        webView = WebView(this).apply {
            setBackgroundColor(PAGE)
            overScrollMode = View.OVER_SCROLL_NEVER
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportZoom(false)
            addJavascriptInterface(NativeBridge(), "PantryNative")

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val url = request.url
                    if (url.host == HOST) return false
                    openExternally(url)
                    return true
                }
            }
            webChromeClient = PantryChromeClient()
        }

        root.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(root)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        registerBackHandling()

        billing = BillingManager(this) { json ->
            runOnUiThread {
                if (::webView.isInitialized) {
                    webView.evaluateJavascript("window.__pantryBilling && window.__pantryBilling($json)", null)
                }
            }
        }.also { it.start() }

        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(START_URL)
        }
    }

    private fun openExternally(url: Uri) {
        if (url.scheme !in setOf("https", "http", "mailto")) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url))
        } catch (_: ActivityNotFoundException) {
        }
    }

    private inner class NativeBridge {
        @JavascriptInterface
        fun billingState(): String = billing?.stateJson() ?: "{}"

        @JavascriptInterface
        fun billingRefresh() {
            billing?.refresh()
        }

        @JavascriptInterface
        fun billingBuy() {
            billing?.buy()
        }

        @JavascriptInterface
        fun manageSubscription() {
            runOnUiThread {
                openExternally(
                    Uri.parse("https://play.google.com/store/account/subscriptions?sku=${BillingManager.PRODUCT_ID}&package=$packageName")
                )
            }
        }
    }

    // ---- Photos: camera or gallery -----------------------------------------------------

    private inner class PantryChromeClient : WebChromeClient() {
        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            params: FileChooserParams?
        ): Boolean {
            uploadCallback?.onReceiveValue(null)
            uploadCallback = filePathCallback
            cameraUri = null

            val wantsCamera = params?.isCaptureEnabled == true
            val multiple = params?.mode == FileChooserParams.MODE_OPEN_MULTIPLE

            val cameraIntent = makeCameraIntent()
            val pickIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                if (multiple) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }

            val intent = when {
                wantsCamera && cameraIntent != null -> cameraIntent
                cameraIntent != null -> Intent.createChooser(pickIntent, "Add a photo").apply {
                    putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cameraIntent))
                }
                else -> pickIntent
            }
            return try {
                @Suppress("DEPRECATION")
                startActivityForResult(intent, UPLOAD_CODE)
                true
            } catch (_: ActivityNotFoundException) {
                uploadCallback?.onReceiveValue(null)
                uploadCallback = null
                false
            }
        }
    }

    private fun makeCameraIntent(): Intent? {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (intent.resolveActivity(packageManager) == null) return null
        return try {
            val dir = File(cacheDir, "camera").apply { mkdirs() }
            dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000 }?.forEach { it.delete() }
            val file = File(dir, "photo-${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(this, "$packageName.photos", file)
            cameraUri = uri
            intent.putExtra(MediaStore.EXTRA_OUTPUT, uri)
            intent.clipData = ClipData.newRawUri("photo", uri)
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent
        } catch (_: Exception) {
            null
        }
    }

    @Deprecated("Activity result API is not available on plain Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != UPLOAD_CODE) return
        var uris: Array<Uri>? = null
        if (resultCode == RESULT_OK) {
            val clip = data?.clipData
            val picked = data?.data
            uris = when {
                clip != null && clip.itemCount > 0 && clip.getItemAt(0).uri != cameraUri ->
                    Array(clip.itemCount) { clip.getItemAt(it).uri }
                picked != null -> arrayOf(picked)
                cameraUri != null -> {
                    val f = File(File(cacheDir, "camera"), cameraUri!!.lastPathSegment ?: "")
                    if (f.exists() && f.length() > 0) arrayOf(cameraUri!!) else null
                }
                else -> null
            }
        }
        uploadCallback?.onReceiveValue(uris)
        uploadCallback = null
        cameraUri = null
    }

    // ---- Back button -------------------------------------------------------------------

    private fun registerBackHandling() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val callback = OnBackInvokedCallback { handleBack() }
            backCallback = callback
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                callback
            )
        }
    }

    @Deprecated("Used on Android 12 and older; newer versions use OnBackInvokedCallback")
    override fun onBackPressed() {
        handleBack()
    }

    private fun handleBack() {
        if (!::webView.isInitialized) {
            finish()
            return
        }
        webView.evaluateJavascript(BACK_JS) { result ->
            if (result == null || !result.contains("handled")) moveTaskToBack(true)
        }
    }

    // ---- Lifecycle ---------------------------------------------------------------------

    override fun onSaveInstanceState(outState: Bundle) {
        if (::webView.isInitialized) webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        billing?.refresh()
    }

    override fun onDestroy() {
        billing?.end()
        billing = null
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("PantryNative")
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val UPLOAD_CODE = 1001
        private const val HOST = "appassets.androidplatform.net"
        private const val START_URL = "https://$HOST/www/index.html"
        private val BRAND = Color.rgb(0x5B, 0x4B, 0xFF)
        private val PAGE = Color.rgb(0xF3, 0xF1, 0xFF)

        /** Closes the recipe sheet if open; otherwise the app goes to the background. */
        private const val BACK_JS = """
(function () {
  try {
    var o = document.getElementById('overlay');
    if (o && o.classList.contains('open')) { o.classList.remove('open'); return 'handled'; }
  } catch (e) {}
  return 'exit';
})();
"""
    }
}
