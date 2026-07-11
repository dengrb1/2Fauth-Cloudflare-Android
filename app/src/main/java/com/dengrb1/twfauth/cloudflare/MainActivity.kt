package com.dengrb1.twfauth.cloudflare

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.dengrb1.twfauth.cloudflare.ui.PlatformActions
import com.dengrb1.twfauth.cloudflare.ui.TwoFactorApp
import com.dengrb1.twfauth.cloudflare.security.TurnstileOriginPolicy
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.io.IOException
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var container: AppContainer
    private lateinit var scanner: ActivityResultLauncher<ScanOptions>
    private lateinit var fileReader: ActivityResultLauncher<Array<String>>
    private lateinit var fileWriter: ActivityResultLauncher<String>
    private var scanContinuation: CancellableContinuation<String?>? = null
    private var readContinuation: CancellableContinuation<String?>? = null
    private var writeRequest: WriteRequest? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applySaved(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerPlatformLaunchers()
        container = AppContainer(applicationContext)
        setContent {
            TwoFactorApp(
                gateway = container.uiGateway,
                platformActions = PlatformActions(
                    requestDeviceUnlock = ::requestDeviceUnlock,
                    requestTurnstileToken = ::requestTurnstileToken,
                    scanOtpAuth = ::scanOtpAuth,
                    readTextFile = ::readTextFile,
                    writeTextFile = ::writeTextFile,
                    copyToClipboard = ::copyToClipboard,
                ),
            )
        }
    }

    private fun registerPlatformLaunchers() {
        scanner = registerForActivityResult(ScanContract()) { result ->
            scanContinuation.takeActive()?.resume(result.contents?.trim()?.takeIf(String::isNotBlank))
            scanContinuation = null
        }
        fileReader = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val continuation = readContinuation.takeActive()
            readContinuation = null
            if (uri == null) continuation?.resume(null) else lifecycleScope.launch {
                val content = withContext(Dispatchers.IO) {
                    runCatching { contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
                }
                continuation?.takeIf { it.isActive }?.resume(content)
            }
        }
        fileWriter = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val request = writeRequest
            writeRequest = null
            if (request == null) return@registerForActivityResult
            lifecycleScope.launch {
                val written = uri != null && withContext(Dispatchers.IO) {
                    runCatching {
                        contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(request.content) }
                            ?: throw IOException("Unable to open document")
                    }.isSuccess
                }
                request.continuation.takeIf { it.isActive }?.resume(written)
            }
        }
    }

    private suspend fun requestDeviceUnlock(): Boolean = withContext(Dispatchers.Main.immediate) {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this@MainActivity).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            return@withContext false
        }
        suspendCancellableCoroutine { continuation ->
            val prompt = BiometricPrompt(
                this@MainActivity,
                ContextCompat.getMainExecutor(this@MainActivity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        continuation.takeIf { it.isActive }?.resume(true)
                    }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        continuation.takeIf { it.isActive }?.resume(false)
                    }
                },
            )
            continuation.invokeOnCancellation { prompt.cancelAuthentication() }
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle(getString(R.string.auth_title))
                    .setSubtitle(getString(R.string.auth_subtitle))
                    .setAllowedAuthenticators(authenticators)
                    .build(),
            )
        }
    }

    private suspend fun scanOtpAuth(): String? = withContext(Dispatchers.Main.immediate) {
        scanContinuation.takeActive()?.resume(null)
        suspendCancellableCoroutine { continuation ->
            scanContinuation = continuation
            continuation.invokeOnCancellation { if (scanContinuation === continuation) scanContinuation = null }
            scanner.launch(
                ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt(getString(R.string.scan_prompt)).setBeepEnabled(false).setOrientationLocked(false),
            )
        }
    }

    private suspend fun readTextFile(mimeTypes: Array<String>): String? = withContext(Dispatchers.Main.immediate) {
        readContinuation.takeActive()?.resume(null)
        suspendCancellableCoroutine { continuation ->
            readContinuation = continuation
            continuation.invokeOnCancellation { if (readContinuation === continuation) readContinuation = null }
            fileReader.launch(mimeTypes)
        }
    }

    private suspend fun writeTextFile(suggestedName: String, mimeType: String, content: String): Boolean =
        withContext(Dispatchers.Main.immediate) {
            writeRequest?.continuation.takeActive()?.resume(false)
            suspendCancellableCoroutine { continuation ->
                writeRequest = WriteRequest(content, continuation)
                continuation.invokeOnCancellation { if (writeRequest?.continuation === continuation) writeRequest = null }
                fileWriter.launch(suggestedName)
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun requestTurnstileToken(siteKey: String): String? = withContext(Dispatchers.Main.immediate) {
        if (siteKey.isBlank()) return@withContext null
        suspendCancellableCoroutine { continuation ->
            val originPolicy = TurnstileOriginPolicy(BuildConfig.WORKER_URL)
            val webView = WebView(this@MainActivity).apply {
                CookieManager.getInstance().setAcceptCookie(false)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
            }
            var dialog: androidx.appcompat.app.AlertDialog? = null
            fun finish(token: String?) {
                if (continuation.isActive) continuation.resume(token?.takeIf(String::isNotBlank))
                dialog?.dismiss()
            }
            webView.addJavascriptInterface(
                object {
                    @JavascriptInterface fun onToken(token: String) = runOnUiThread { finish(token) }
                    @JavascriptInterface fun onError() = runOnUiThread { finish(null) }
                },
                "AndroidChallenge",
            )
            webView.webViewClient = object : WebViewClient() {
                private fun allowed(uri: Uri): Boolean = originPolicy.allows(uri.toString())
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    return !allowed(request.url)
                }
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    return if (allowed(request.url)) super.shouldInterceptRequest(view, request) else
                        WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                    handler.cancel(); finish(null)
                }
            }
            val html = """
                <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
                <script src="https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit" async defer></script>
                <style>body{margin:0;min-height:240px;display:grid;place-items:center;background:transparent}</style></head>
                <body><div id="challenge"></div><script>
                const siteKey=${JSONObject.quote(siteKey)};
                const timer=setInterval(()=>{if(window.turnstile){clearInterval(timer);turnstile.render('#challenge',{
                  sitekey:siteKey,callback:t=>AndroidChallenge.onToken(t),
                  'expired-callback':()=>turnstile.reset(),'error-callback':()=>AndroidChallenge.onError()
                });}},100);
                </script></body></html>
            """.trimIndent()
            dialog = MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.security_challenge)
                .setView(webView)
                .setNegativeButton(android.R.string.cancel) { _, _ -> finish(null) }
                .setOnCancelListener { finish(null) }
                .create()
            dialog?.setOnDismissListener { webView.removeJavascriptInterface("AndroidChallenge"); webView.destroy() }
            continuation.invokeOnCancellation { dialog?.dismiss() }
            dialog?.show()
            webView.loadDataWithBaseURL(BuildConfig.WORKER_URL.trimEnd('/') + "/", html, "text/html", "UTF-8", null)
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
    }

    override fun onDestroy() {
        scanContinuation.takeActive()?.cancel()
        readContinuation.takeActive()?.cancel()
        writeRequest?.continuation.takeActive()?.cancel()
        super.onDestroy()
    }

    private data class WriteRequest(val content: String, val continuation: CancellableContinuation<Boolean>)
}

private fun <T> CancellableContinuation<T>?.takeActive(): CancellableContinuation<T>? = this?.takeIf { it.isActive }
