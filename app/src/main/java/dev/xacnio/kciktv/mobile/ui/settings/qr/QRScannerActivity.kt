package dev.xacnio.kciktv.mobile.ui.settings.qr

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.api.RetrofitClient
import dev.xacnio.kciktv.shared.data.api.TvLinkSetupRequest
import dev.xacnio.kciktv.shared.data.prefs.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QRScannerActivity : AppCompatActivity() {

    private lateinit var barcodeScannerView: DecoratedBarcodeView
    private lateinit var confirmationLayout: LinearLayout
    private lateinit var scanOverlay: View
    private lateinit var tvCodeDisplay: TextView
    private lateinit var btnConfirm: View
    private lateinit var btnCancel: View
    
    private lateinit var prefs: AppPreferences
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr_scanner)

        prefs = AppPreferences(this)
        
        // Bind Views
        barcodeScannerView = findViewById(R.id.barcode_scanner)
        confirmationLayout = findViewById(R.id.confirmation_layout)
        scanOverlay = findViewById(R.id.scan_overlay)
        tvCodeDisplay = findViewById(R.id.tv_code_display)
        btnConfirm = findViewById(R.id.btn_confirm)
        btnCancel = findViewById(R.id.btn_cancel)
        
        findViewById<android.view.View>(R.id.btnClose).setOnClickListener {
            finish()
        }
        
        // Setup Button Listeners
        btnCancel.setOnClickListener {
            resetScanner()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1)
        } else {
            startScanning()
        }
    }

    private fun startScanning() {
        barcodeScannerView.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult?) {
                result?.text?.let { text ->
                    if (text.isNotBlank()) {
                        barcodeScannerView.pause()
                        processQrCode(text)
                    }
                }
            }

            override fun possibleResultPoints(resultPoints: List<com.google.zxing.ResultPoint>?) {}
        })
    }

    private fun processQrCode(text: String) {
        try {
            // Handle case where text is just query params starting with ?
            val uriString = if (text.startsWith("?")) "http://dummy$text" else text
            val uri = uriString.toUri()
            
            val uuid = uri.getQueryParameter("uuid")
            val code = uri.getQueryParameter("code")

            if (uuid != null && code != null) {
                showConfirmationUI(uuid, code)
            } else {
                Toast.makeText(this, R.string.invalid_qr_code, Toast.LENGTH_SHORT).show()
                barcodeScannerView.resume()
            }
        } catch (e: Exception) {
            Toast.makeText(this, R.string.invalid_qr_code, Toast.LENGTH_SHORT).show()
            barcodeScannerView.resume()
        }
    }

    private fun showConfirmationUI(uuid: String, code: String) {
        // Stop camera preview
        barcodeScannerView.pause()
        barcodeScannerView.visibility = View.GONE
        scanOverlay.visibility = View.GONE
        
        // Show confirmation UI
        confirmationLayout.visibility = View.VISIBLE
        tvCodeDisplay.text = code
        
        // Setup confirmation action
        btnConfirm.setOnClickListener {
            sendRequest(uuid, code)
        }
    }
    
    private fun resetScanner() {
        // Reset UI
        confirmationLayout.visibility = View.GONE
        barcodeScannerView.visibility = View.VISIBLE
        scanOverlay.visibility = View.VISIBLE
        
        // Resume scanning
        barcodeScannerView.resume()
    }

    private fun sendRequest(uuid: String, code: String) {
        val token = prefs.authToken
        if (token == null) {
            Toast.makeText(this, R.string.login_required, Toast.LENGTH_SHORT).show()
            resetScanner()
            return
        }

        // Disable button to prevent double clicks
        btnConfirm.isEnabled = false
        
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.authService.linkTvDevice(
                        token = "Bearer $token",
                        request = TvLinkSetupRequest(uuid = uuid, key = code)
                    )
                }

                if (response.isSuccessful) {
                    Toast.makeText(this@QRScannerActivity, R.string.request_sent, Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    val errorMsg = response.errorBody()?.string() ?: response.code().toString()
                    Toast.makeText(this@QRScannerActivity, getString(R.string.request_failed, errorMsg), Toast.LENGTH_LONG).show()
                     // Re-enable on failure so they can try again or cancel
                    btnConfirm.isEnabled = true
                }
            } catch (e: Exception) {
                Toast.makeText(this@QRScannerActivity, getString(R.string.request_failed, e.message.toString()), Toast.LENGTH_LONG).show()
                btnConfirm.isEnabled = true
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (confirmationLayout.visibility != View.VISIBLE) {
            barcodeScannerView.resume()
        }
    }

    override fun onPause() {
        super.onPause()
        barcodeScannerView.pause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startScanning()
        } else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
