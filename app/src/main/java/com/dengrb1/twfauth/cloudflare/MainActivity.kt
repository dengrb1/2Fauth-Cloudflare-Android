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
import com.dengrb1.twfauth.cloudflare.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWebView()
        setupBackNavigation()

        if (savedInstanceState == null) {
            binding.webView.loadUrl(BuildConfig.WORKER_URL)
        } else {
            binding.webView.restoreState(savedInstanceState)
        }
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
        binding.webView.saveState(outState)
    }

    override fun onDestroy() {
        binding.webView.destroy()
        super.onDestroy()
    }
}
