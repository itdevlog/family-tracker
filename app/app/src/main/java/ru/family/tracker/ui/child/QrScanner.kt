package ru.family.tracker.ui.child

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import ru.family.tracker.R
import ru.family.tracker.data.pairing.parseQrPayload
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-screen QR scanner built on CameraX and ML Kit.
 *
 * Only QR barcodes whose text is a valid [parseQrPayload] payload are delivered, so a
 * random QR code on a poster does not abort pairing. The first accepted code stops
 * analysis and calls [onResult] exactly once; [onCancel] returns to manual entry, both
 * from the cancel button and from the system back gesture.
 *
 * If the CAMERA permission is denied or the camera cannot start, the screen explains the
 * problem and offers manual entry — pairing never depends on the scanner.
 */
@Composable
fun QrScanner(
    onResult: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val currentOnResult by rememberUpdatedState(onResult)

    var hasPermission by remember { mutableStateOf(hasCameraPermission(context)) }
    var permissionRequested by rememberSaveable { mutableStateOf(false) }
    var cameraFailed by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        permissionRequested = true
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    BackHandler(onBack = onCancel)

    Box(modifier = Modifier.fillMaxSize()) {
        if (hasPermission && !cameraFailed) {
            CameraPreview(
                onQrPayload = currentOnResult,
                onCameraFailed = { cameraFailed = true },
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.child_scan_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.child_scan_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            if (!hasPermission || cameraFailed) {
                CameraUnavailable(
                    cameraFailed = cameraFailed,
                    cameraDenied = !hasPermission && permissionRequested,
                    onAllowCamera = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    onManual = onCancel,
                )
            } else {
                Button(
                    onClick = onCancel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                ) {
                    Text(stringResource(R.string.child_scan_cancel))
                }
            }
        }
    }
}

@Composable
private fun CameraUnavailable(
    cameraFailed: Boolean,
    cameraDenied: Boolean,
    onAllowCamera: () -> Unit,
    onManual: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(16.dp))
            .padding(20.dp),
    ) {
        when {
            cameraFailed -> Text(
                text = stringResource(R.string.child_scan_camera_error),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
            )

            cameraDenied -> {
                Text(
                    text = stringResource(R.string.child_scan_camera_denied),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onAllowCamera) {
                    Text(stringResource(R.string.child_scan_allow_camera))
                }
            }

            else -> CircularProgressIndicator(color = Color.White)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onManual) {
            Text(stringResource(R.string.child_scan_manual), color = Color.White)
        }
    }
}

@Composable
private fun CameraPreview(
    onQrPayload: (String) -> Unit,
    onCameraFailed: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val currentOnQrPayload by rememberUpdatedState(onQrPayload)
    val currentOnCameraFailed by rememberUpdatedState(onCameraFailed)

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )
        // Analysis runs on every camera frame; the flag stops it after the first hit and
        // also prevents overlapping ML Kit tasks on slow devices.
        val delivered = AtomicBoolean(false)
        var provider: ProcessCameraProvider? = null
        var analysis: ImageAnalysis? = null

        val bindListener = Runnable {
            try {
                val cameraProvider = providerFuture.get()
                provider = cameraProvider

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { imageAnalysis ->
                        analysis = imageAnalysis
                        imageAnalysis.setAnalyzer(analysisExecutor) { image ->
                            analyzeFrame(image, scanner, delivered) { raw ->
                                imageAnalysis.clearAnalyzer()
                                currentOnQrPayload(raw)
                            }
                        }
                    }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis,
                )
            } catch (_: Exception) {
                currentOnCameraFailed()
            }
        }
        providerFuture.addListener(bindListener, mainExecutor)

        onDispose {
            provider?.unbindAll()
            analysis?.clearAnalyzer()
            analysisExecutor.shutdown()
            scanner.close()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * Feeds one frame to ML Kit and closes it when the task completes.
 *
 * Only the frame that wins [delivered] is processed; if it held no valid pairing QR code
 * the flag is cleared so scanning continues with the next frame.
 */
private fun analyzeFrame(
    imageProxy: ImageProxy,
    scanner: BarcodeScanner,
    delivered: AtomicBoolean,
    onQrPayload: (String) -> Unit,
) {
    val mediaImage = imageProxy.image
    if (mediaImage == null || !delivered.compareAndSet(false, true)) {
        imageProxy.close()
        return
    }

    val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
    scanner.process(input)
        .addOnSuccessListener { barcodes ->
            val raw = barcodes
                .asSequence()
                .filter { it.format == Barcode.FORMAT_QR_CODE }
                .mapNotNull { it.rawValue }
                .firstOrNull { parseQrPayload(it) != null }

            if (raw != null) {
                onQrPayload(raw)
            } else {
                delivered.set(false)
            }
        }
        .addOnFailureListener {
            delivered.set(false)
        }
        .addOnCompleteListener {
            imageProxy.close()
        }
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
