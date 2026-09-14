package com.amayra.maya.ui.scanner

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.amayra.maya.core.MayaLog
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Full-screen barcode scanner. Results are published on BarcodeBus and the
 * scan_barcode tool awaits them; honest handling when the camera is unavailable.
 */
object BarcodeBus {
    private val _result = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 2)
    val result: kotlinx.coroutines.flow.SharedFlow<String> = _result
    fun publish(value: String) { _result.tryEmit(value) }

    suspend fun await(timeoutMs: Long = 30_000): String? =
        withTimeoutOrNull(timeoutMs) { result.first { it.isNotBlank() } }
}

@Composable
fun BarcodeScannerScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasCam by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCam = granted
        if (!granted) MayaLog.w("SCAN", "camera denied")
    }

    LaunchedEffect(Unit) {
        hasCam = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!hasCam) permLauncher.launch(Manifest.permission.CAMERA)
    }

    LaunchedEffect(Unit) {
        BarcodeBus.result.collect { result = it }
    }

    Surface(color = com.amayra.maya.ui.theme.Bg, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("Close") }
                Spacer(Modifier.weight(1f))
                Text("Point at a barcode/QR", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(48.dp))
            }
            if (hasCam) {
                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        val providerFuture = ProcessCameraProvider.getInstance(ctx)
                        providerFuture.addListener({
                            try {
                                val provider = providerFuture.get()
                                val preview = Preview.Builder().build().also {
                                    it.setSurfaceProvider(previewView.surfaceProvider)
                                }
                                val analysis = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .build()
                                val executor = Executors.newSingleThreadExecutor()
                                val scanner = BarcodeScanning.getClient()
                                analysis.setAnalyzer(executor) { proxy ->
                                    val media = proxy.image
                                    if (media != null) {
                                        val rotation = proxy.imageInfo.rotationDegrees
                                        val input = InputImage.fromMediaImage(media, rotation)
                                        scanner.process(input)
                                            .addOnSuccessListener { codes ->
                                                codes.firstOrNull()?.rawValue?.let { value ->
                                                    BarcodeBus.publish(value)
                                                }
                                            }
                                            .addOnCompleteListener { proxy.close() }
                                    } else proxy.close()
                                }
                                provider.unbindAll()
                                provider.bindToLifecycle(
                                    lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                                )
                            } catch (t: Throwable) {
                                MayaLog.e("SCAN", "Camera bind failed", t)
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                        previewView
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
            } else {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Camera permission needed for scanning.")
                }
            }
            result?.let {
                Text(
                    "Result: $it",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}
