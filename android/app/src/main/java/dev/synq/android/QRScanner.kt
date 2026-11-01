package dev.synq.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Composable
fun QRScannerDialog(
    onDismiss: () -> Unit,
    onPayloadScanned: (QRPairingPayload) -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    var permissionRequested by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var manualInput by remember { mutableStateOf("") }
    var showManualInput by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        permissionRequested = true
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (hasCameraPermission) {
                    CameraPreview(
                        onQrDetected = { qrString ->
                            if (isProcessing) return@CameraPreview
                            isProcessing = true
                            val result = QRPairingPayload.parse(qrString)
                            result.onSuccess { payload ->
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onPayloadScanned(payload)
                            }.onFailure { err ->
                                errorMessage = err.message ?: "Invalid QR Code"
                                isProcessing = false
                            }
                        }
                    )

                    // Reticle & Scanner Overlay
                    ScannerOverlay(modifier = Modifier.fillMaxSize())
                } else {
                    // Camera Permission Denied view
                    CameraPermissionDeniedView(
                        onRequestPermission = {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        },
                        onOpenSettings = {
                            val intent = Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null)
                            )
                            context.startActivity(intent)
                        },
                        onShowManualInput = { showManualInput = true }
                    )
                }

                // Top Header Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 24.dp)
                        .align(Alignment.TopCenter),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(44.dp)
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White
                        )
                    }

                    Text(
                        text = "Scan QR Code",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp
                    )

                    Spacer(modifier = Modifier.size(44.dp))
                }

                // Bottom Content / Error banner / Manual Input
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 32.dp, start = 20.dp, end = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AnimatedVisibility(visible = errorMessage != null) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            ),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Text(
                                    text = errorMessage ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }

                    if (showManualInput) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    "Enter Pairing URI",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                OutlinedTextField(
                                    value = manualInput,
                                    onValueChange = { manualInput = it },
                                    placeholder = { Text("synq://pair?...") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedButton(onClick = { showManualInput = false }) {
                                        Text("Cancel")
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Button(
                                        onClick = {
                                            val result = QRPairingPayload.parse(manualInput)
                                            result.onSuccess { payload ->
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                onPayloadScanned(payload)
                                            }.onFailure { err ->
                                                errorMessage = err.message ?: "Invalid pairing payload"
                                            }
                                        },
                                        enabled = manualInput.isNotBlank()
                                    ) {
                                        Text("Pair")
                                    }
                                }
                            }
                        }
                    } else if (hasCameraPermission) {
                        OutlinedButton(
                            onClick = { showManualInput = true },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.background(
                                Color.Black.copy(alpha = 0.5f),
                                RoundedCornerShape(12.dp)
                            )
                        ) {
                            Text("Enter code manually", color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScannerOverlay(modifier: Modifier = Modifier) {
    val primaryColor = MaterialTheme.colorScheme.primary

    val infiniteTransition = rememberInfiniteTransition(label = "scanLaser")
    val laserY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "laser"
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // Reticle box
        Box(
            modifier = Modifier
                .size(260.dp)
                .border(2.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
                .clip(RoundedCornerShape(20.dp))
        ) {
            // Animated laser line
            Canvas(modifier = Modifier.fillMaxSize()) {
                val lineY = size.height * laserY
                drawLine(
                    color = primaryColor,
                    start = Offset(16.dp.toPx(), lineY),
                    end = Offset(size.width - 16.dp.toPx(), lineY),
                    strokeWidth = 3.dp.toPx()
                )
            }
        }

        Text(
            text = "Point camera at the QR code on your Mac",
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(top = 310.dp),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun CameraPermissionDeniedView(
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onShowManualInput: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(Color.DarkGray, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.PhotoCamera,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        Text(
            text = "Camera Access Needed",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "SYNQ uses your camera to scan the QR code displayed on your Mac for instant, secure pairing.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.LightGray,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onRequestPermission,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Grant Permission")
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Open Settings", color = Color.White)
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = onShowManualInput,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Enter code manually", color = Color.White)
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun CameraPreview(
    onQrDetected: (String) -> Unit
) {
    val context = LocalContext.current
    var cameraDevice by remember { mutableStateOf<CameraDevice?>(null) }
    var captureSession by remember { mutableStateOf<CameraCaptureSession?>(null) }
    var backgroundThread by remember { mutableStateOf<HandlerThread?>(null) }
    var backgroundHandler by remember { mutableStateOf<Handler?>(null) }
    var textureViewRef by remember { mutableStateOf<TextureView?>(null) }
    var previewSizeState by remember { mutableStateOf<Size?>(null) }
    var sensorOrientationState by remember { mutableStateOf(90) }

    val reader = remember {
        MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true
                )
            )
        }
    }

    DisposableEffect(Unit) {
        val thread = HandlerThread("CameraBackground").apply { start() }
        backgroundThread = thread
        backgroundHandler = Handler(thread.looper)

        onDispose {
            try {
                captureSession?.close()
                cameraDevice?.close()
                thread.quitSafely()
            } catch (_: Exception) {}
        }
    }

    // Coroutine loop that samples center-cropped frames from TextureView and decodes them with ZXing
    LaunchedEffect(textureViewRef) {
        val texture = textureViewRef ?: return@LaunchedEffect
        withContext(Dispatchers.Default) {
            while (isActive) {
                delay(150) // ~6-7 checks per second for crisp responsiveness
                if (!texture.isAvailable) continue

                try {
                    val viewW = texture.width
                    val viewH = texture.height
                    if (viewW <= 0 || viewH <= 0) continue

                    // Sample frame preserving aspect ratio
                    val targetW = 720
                    val targetH = (targetW * viewH) / viewW
                    val fullBmp = texture.getBitmap(targetW, targetH) ?: continue

                    // Focus decode strictly on the central reticle region (60% of min dimension)
                    val side = (minOf(fullBmp.width, fullBmp.height) * 0.65f).toInt()
                    val cropX = (fullBmp.width - side) / 2
                    val cropY = (fullBmp.height - side) / 2
                    val croppedBmp = Bitmap.createBitmap(fullBmp, cropX, cropY, side, side)

                    val width = croppedBmp.width
                    val height = croppedBmp.height
                    val pixels = IntArray(width * height)
                    croppedBmp.getPixels(pixels, 0, width, 0, 0, width, height)

                    val source = RGBLuminanceSource(width, height, pixels)
                    val binaryBitmap = BinaryBitmap(HybridBinarizer(source))

                    try {
                        val result = reader.decodeWithState(binaryBitmap)
                        reader.reset()
                        val text = result.text
                        if (!text.isNullOrBlank()) {
                            withContext(Dispatchers.Main) {
                                onQrDetected(text)
                            }
                        }
                    } catch (_: Exception) {
                        reader.reset()
                    } finally {
                        if (croppedBmp != fullBmp) {
                            croppedBmp.recycle()
                        }
                        fullBmp.recycle()
                    }
                } catch (_: Exception) {
                    // Frame unavailable or recycling
                }
            }
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            TextureView(ctx).apply {
                textureViewRef = this
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        surface: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {
                        val manager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                        val cameraId = manager.cameraIdList.firstOrNull { id ->
                            manager.getCameraCharacteristics(id)
                                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                        } ?: manager.cameraIdList.firstOrNull() ?: return

                        val characteristics = manager.getCameraCharacteristics(cameraId)
                        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                        sensorOrientationState = sensorOrientation

                        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        val choices = map?.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()

                        // Pick preview size preserving aspect ratio
                        val optimalSize = chooseOptimalSize(choices, width, height)
                        previewSizeState = optimalSize
                        surface.setDefaultBufferSize(optimalSize.width, optimalSize.height)

                        configureTransform(this@apply, width, height, optimalSize, sensorOrientation)

                        try {
                            manager.openCamera(
                                cameraId,
                                object : CameraDevice.StateCallback() {
                                    override fun onOpened(camera: CameraDevice) {
                                        cameraDevice = camera
                                        val surfaceView = Surface(surface)
                                        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                            addTarget(surfaceView)
                                        }

                                        camera.createCaptureSession(
                                            listOf(surfaceView),
                                            object : CameraCaptureSession.StateCallback() {
                                                override fun onConfigured(session: CameraCaptureSession) {
                                                    captureSession = session
                                                    builder.set(
                                                        android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                                                        android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                                                    )
                                                    builder.set(
                                                        android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE,
                                                        android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE_ON
                                                    )
                                                    try {
                                                        session.setRepeatingRequest(
                                                            builder.build(),
                                                            null,
                                                            backgroundHandler
                                                        )
                                                    } catch (_: Exception) {}
                                                }

                                                override fun onConfigureFailed(session: CameraCaptureSession) {}
                                            },
                                            backgroundHandler
                                        )
                                    }

                                    override fun onDisconnected(camera: CameraDevice) {
                                        camera.close()
                                        cameraDevice = null
                                    }

                                    override fun onError(camera: CameraDevice, error: Int) {
                                        camera.close()
                                        cameraDevice = null
                                    }
                                },
                                backgroundHandler
                            )
                        } catch (_: Exception) {
                            // Camera access failed
                        }
                    }

                    override fun onSurfaceTextureSizeChanged(
                        surface: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {
                        configureTransform(this@apply, width, height, previewSizeState, sensorOrientationState)
                    }

                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                        captureSession?.close()
                        cameraDevice?.close()
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                }
            }
        }
    )
}

private fun chooseOptimalSize(
    choices: Array<Size>,
    textureViewWidth: Int,
    textureViewHeight: Int
): Size {
    if (choices.isEmpty()) return Size(1920, 1080)
    val targetRatio = if (textureViewWidth > 0 && textureViewHeight > 0) {
        maxOf(textureViewWidth, textureViewHeight).toDouble() / minOf(textureViewWidth, textureViewHeight).toDouble()
    } else {
        16.0 / 9.0
    }

    // Filter reasonable preview sizes (between 720p and 1080p for performance and battery)
    val candidates = choices.filter { size ->
        val ratio = maxOf(size.width, size.height).toDouble() / minOf(size.width, size.height).toDouble()
        kotlin.math.abs(ratio - targetRatio) < 0.25 && minOf(size.width, size.height) in 720..1440
    }

    return candidates.minByOrNull {
        kotlin.math.abs(it.width * it.height - 1920 * 1080)
    } ?: choices.firstOrNull { minOf(it.width, it.height) >= 720 } ?: choices[0]
}

private fun configureTransform(
    textureView: TextureView,
    viewWidth: Int,
    viewHeight: Int,
    previewSize: Size?,
    sensorOrientation: Int
) {
    if (previewSize == null || viewWidth == 0 || viewHeight == 0) return
    val matrix = Matrix()
    val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
    val centerX = viewRect.centerX()
    val centerY = viewRect.centerY()

    val isRotated = sensorOrientation == 90 || sensorOrientation == 270
    val bufferWidth = if (isRotated) previewSize.height else previewSize.width
    val bufferHeight = if (isRotated) previewSize.width else previewSize.height

    // Center-crop uniform scaling: scale up so both dimensions cover the view without stretching
    val scale = maxOf(viewWidth.toFloat() / bufferWidth, viewHeight.toFloat() / bufferHeight)
    matrix.postScale(
        (bufferWidth * scale) / viewWidth,
        (bufferHeight * scale) / viewHeight,
        centerX,
        centerY
    )
    textureView.setTransform(matrix)
}

