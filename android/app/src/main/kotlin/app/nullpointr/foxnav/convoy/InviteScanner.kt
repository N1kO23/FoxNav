package app.nullpointr.foxnav.convoy

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.nullpointr.foxnav.shared.convoy.Invite
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors

/**
 * Scans a convoy invite's QR code with the camera. Camera apps may not open `foxnav://` links,
 * so joining from a QR code happens in here. Only FoxNav invites count; other codes are ignored.
 */
@Composable
fun InviteScannerDialog(onInvite: (Invite) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var permitted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permitted = granted
        denied = !granted
    }
    LaunchedEffect(Unit) { if (!permitted) request.launch(Manifest.permission.CAMERA) }
    var found by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (permitted) {
                CameraPreview(
                    onText = { text ->
                        val invite = Invite.parse(text)
                        if (invite != null && !found) {
                            found = true
                            onInvite(invite)
                        }
                    },
                )
            }
            Box(Modifier.align(Alignment.Center).size(260.dp).border(3.dp, MaterialTheme.colorScheme.secondary, RoundedCornerShape(24.dp)))
            Column(
                Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Scan the invite's QR code", style = MaterialTheme.typography.titleLarge, color = Color.White)
                if (denied) {
                    Text("FoxNav needs the camera to scan QR codes. You can also open or paste the invite link.", color = Color.White, textAlign = TextAlign.Center)
                }
            }
            Button(onClick = onDismiss, modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(32.dp)) { Text("Cancel") }
        }
    }
}

/** The back camera, with every frame checked for a QR code. */
@Composable
private fun CameraPreview(onText: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnText by rememberUpdatedState(onText)
    val analysisThread = remember { Executors.newSingleThreadExecutor() }
    val providerFuture = remember { ProcessCameraProvider.getInstance(context) }

    DisposableEffect(lifecycleOwner) {
        onDispose {
            // Off as soon as the scanner closes.
            if (providerFuture.isDone) runCatching { providerFuture.get().unbindAll() }
            analysisThread.shutdown()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { viewContext ->
            // A TextureView: a SurfaceView preview stays black inside a dialog's window.
            val view = PreviewView(viewContext).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
            providerFuture.addListener(
                {
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                    val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                    analysis.setAnalyzer(analysisThread) { image ->
                        val text = image.use(::decodeQr)
                        if (text != null) ContextCompat.getMainExecutor(viewContext).execute { latestOnText(text) }
                    }
                    provider.unbindAll()
                    runCatching { provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis) }
                },
                ContextCompat.getMainExecutor(viewContext),
            )
            view
        },
    )
}

private fun decodeQr(image: ImageProxy): String? {
    // The first plane of a YUV frame is the brightness, all a QR reader needs.
    val plane = image.planes[0]
    val buffer = plane.buffer
    val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
    return decodeQr(bytes, plane.rowStride, image.width, image.height)
}

/** The text of a QR code in a greyscale image with [rowStride] bytes per row, or null if there's none. */
internal fun decodeQr(luminance: ByteArray, rowStride: Int, width: Int, height: Int): String? {
    val source = PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false)
    return try {
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.TRY_HARDER to true)).text
    } catch (e: ReaderException) {
        null
    }
}
