package net.wastu.binderclip

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import io.github.g00fy2.quickie.QRResult
import io.github.g00fy2.quickie.ScanCustomCode
import io.github.g00fy2.quickie.config.BarcodeFormat
import io.github.g00fy2.quickie.config.ScannerConfig

class QrScannerActivity : AppCompatActivity() {
    private val scanner = registerForActivityResult(ScanCustomCode()) { result ->
        if (result is QRResult.QRSuccess) setResult(RESULT_OK, Intent().putExtra("uri", result.content.rawValue)) else setResult(RESULT_CANCELED)
        finish()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) scanner.launch(ScannerConfig.build { setBarcodeFormats(listOf(BarcodeFormat.FORMAT_QR_CODE)); setShowTorchToggle(true); setShowCloseButton(true); setKeepScreenOn(true) })
    }
}
