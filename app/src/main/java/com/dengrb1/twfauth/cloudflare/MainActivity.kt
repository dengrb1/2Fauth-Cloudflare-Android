package com.dengrb1.twfauth.cloudflare

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.dengrb1.twfauth.cloudflare.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isAuthenticated = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWebView()
        setupBackNavigation()

        if (savedInstanceState != null) {
            isAuthenticated = savedInstanceState.getBoolean(KEY_AUTHENTICATED, false)
            if (isAuthenticated) {
                binding.webView.restoreState(savedInstanceState)
                setWebContentVisible(true)
            }
        }

        if (!isAuthenticated) {
            setWebContentVisible(false)
            authenticateUser()
        }
    }

    private fun authenticateUser() {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val biometricManager = BiometricManager.from(this)
        val canAuthenticate = biometricManager.canAuthenticate(authenticators)

        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
            showError(getString(R.string.auth_unavailable))
            return
        }

        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    isAuthenticated = true
                    setWebContentVisible(true)
                    if (binding.webView.url.isNullOrBlank()) {
                        binding.webView.loadUrl(BuildConfig.WORKER_URL)
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    showError(getString(R.string.auth_error, errString))
                    finish()
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.auth_title))
            .setSubtitle(getString(R.string.auth_subtitle))
            .setAllowedAuthenticators(authenticators)
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    private fun setWebContentVisible(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.INVISIBLE
        binding.webView.visibility = visibility
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun setupWebView() = with(binding.webView) {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        webChromeClient = WebChromeClient()
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                setLoadingState(true)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                setLoadingState(false)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    setLoadingState(false)
                    showError(getString(R.string.error_loading_page, error?.description ?: "Unknown"))
                }
            }
        }
    }

    private fun setLoadingState(isLoading: Boolean) {
        binding.progressIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE
        binding.errorText.visibility = View.GONE
    }

    private fun showError(message: String) {
        binding.errorText.visibility = View.VISIBLE
        binding.errorText.text = message
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_AUTHENTICATED, isAuthenticated)
        if (isAuthenticated) {
            binding.webView.saveState(outState)
        }
    }

    override fun onDestroy() {
        binding.webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val KEY_AUTHENTICATED = "key_authenticated"
    }
}
