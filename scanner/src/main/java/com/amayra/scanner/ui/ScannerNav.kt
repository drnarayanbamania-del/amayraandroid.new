package com.amayra.scanner.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.amayra.scanner.R
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.amayra.scanner.PageResult
import com.amayra.scanner.Routes
import com.amayra.scanner.ScanJob
import com.amayra.scanner.ScannerViewModel
import com.amayra.scanner.data.TRANSLATE_LANGUAGES
import com.amayra.scanner.data.Exporter
import com.amayra.scanner.ocr.ImageEnhancer
import com.amayra.scanner.translate.TranslationEngine
import com.amayra.scanner.ui.theme.BrandBlue
import com.amayra.scanner.ui.theme.HeaderDarkBottom
import com.amayra.scanner.ui.theme.HeaderDarkTop
import com.amayra.scanner.ui.theme.NeonBlue
import com.amayra.scanner.ui.theme.NeonGreen
import com.amayra.scanner.ui.theme.NeonPurple
import com.amayra.scanner.ui.theme.NeonRed
import androidx.navigation.NavHostController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cross-screen scratchpad for an in-flight scan. Lives as a singleton because
 * a scan flows Home -> (Camera|Gallery|Edit) -> Result -> Translate.
 */
object PendingScan {
    var uris by mutableStateOf<List<Uri>>(emptyList())
    var type by mutableStateOf("scan") // scan | handwriting | gallery | pdf
    var sourceBitmap by mutableStateOf<Bitmap?>(null)
    var draftText by mutableStateOf("")
    var docName by mutableStateOf("")
    var firstThumb by mutableStateOf<String?>(null)

    fun reset() {
        uris = emptyList(); type = "scan"; sourceBitmap = null
        draftText = ""; docName = ""; firstThumb = null
    }
}

@Composable
private fun TopBar(title: String, onBack: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun HeaderStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.07f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
            .padding(vertical = 10.dp)
    ) {
        Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(label, color = Color(0xFF9DB2FF), fontSize = 10.sp, letterSpacing = 0.8.sp)
    }
}

@Composable
private fun ProActionTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.55f))))
            ) {
                Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        }
    }
}

private fun docDate(ts: Long): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = ts }
    val today = java.util.Calendar.getInstance()
    return when {
        cal.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR) &&
            cal.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR) -> "Today"
        else -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(ts))
    }
}

// ---------------------------------------------------------------------
// HOME
// ---------------------------------------------------------------------

@Composable
fun HomeScreen(vm: ScannerViewModel, nav: NavHostController) = HomeScreenContent(vm, nav)

@Composable
private fun HomeScreenContent(vm: ScannerViewModel, nav: NavHostController) {
    val context = LocalContext.current
    val docs by vm.docs.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    fun launchEdit(label: String, type: String) {
        PendingScan.reset()
        PendingScan.type = type
        PendingScan.docName = "Scan " + SimpleDateFormat("MMM d", Locale.getDefault()).format(Date())
        nav.navigate(Routes.EDIT)
    }

    val galleryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(20)
    ) { uris ->
        if (uris.isNotEmpty()) {
            PendingScan.reset()
            PendingScan.uris = uris
            PendingScan.type = "gallery"
            PendingScan.docName = "Gallery " + SimpleDateFormat("MMM d", Locale.getDefault()).format(Date())
            PendingScan.sourceBitmap = ImageEnhancer.loadScaled(context, uris.first())
            nav.navigate(Routes.EDIT)
        }
    }

    val pdfPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val list = uris ?: emptyList()
        if (list.isNotEmpty()) {
            PendingScan.reset()
            PendingScan.type = "pdf"
            PendingScan.docName = "PDF " + SimpleDateFormat("MMM d", Locale.getDefault()).format(Date())
            vm.processPdf(list)
            nav.navigate(Routes.RESULT)
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            // Brand header — dark neon card with logo, PRO chip and live stats
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(26.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(HeaderDarkTop, HeaderDarkBottom, Color(0xFF142051))
                        )
                    )
            ) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(R.drawable.logo_bamania),
                            contentDescription = "Bamania's Tech logo",
                            modifier = Modifier.size(54.dp).clip(RoundedCornerShape(14.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "AI TEXT SCANNER",
                                color = Color.White, fontSize = 20.sp,
                                fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp
                            )
                            Text(
                                "BAMANIA'S TECH · LEARN · BUILD · GROW",
                                color = Color(0xFF9DB2FF), fontSize = 10.sp, letterSpacing = 1.5.sp
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Brush.horizontalGradient(listOf(NeonBlue, NeonPurple)))
                                .padding(horizontal = 12.dp, vertical = 5.dp)
                        ) {
                            Text(
                                "PRO", color = Color.White, fontSize = 11.sp,
                                fontWeight = FontWeight.Bold, letterSpacing = 1.sp
                            )
                        }
                    }

                    Spacer(Modifier.height(18.dp))

                    Text(
                        "Convert images into text",
                        color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Scan, extract and translate text instantly — fully offline",
                        color = Color(0xFFAAB6D8), fontSize = 13.sp
                    )

                    Spacer(Modifier.height(16.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HeaderStat(docs.size.toString(), "documents", Modifier.weight(1f))
                        HeaderStat(docs.sumOf { it.wordCount }.toString(), "words", Modifier.weight(1f))
                        HeaderStat("100%", "on-device", Modifier.weight(1f))
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Action tiles — dark neon 2x2 grid
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ProActionTile(Icons.Filled.PhotoCamera, "Camera", NeonBlue, Modifier.weight(1f)) {
                    nav.navigate(Routes.CAMERA)
                }
                ProActionTile(Icons.Filled.PhotoLibrary, "Gallery", NeonPurple, Modifier.weight(1f)) {
                    galleryPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ProActionTile(Icons.Filled.PictureAsPdf, "PDF", NeonRed, Modifier.weight(1f)) {
                    pdfPicker.launch(arrayOf("application/pdf"))
                }
                ProActionTile(Icons.Filled.Edit, "Handwriting", Color(0xFF00E5C7), Modifier.weight(1f)) {
                    PendingScan.reset()
                    PendingScan.type = "handwriting"
                    PendingScan.docName = "Notes " + SimpleDateFormat("MMM d", Locale.getDefault()).format(Date())
                    nav.navigate(Routes.CAMERA)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ProActionTile(Icons.Filled.QrCodeScanner, "QR & Barcode", NeonGreen, Modifier.weight(1f)) {
                    nav.navigate(Routes.CODE)
                }
                ProActionTile(Icons.Filled.History, "History", Color(0xFFFFB454), Modifier.weight(1f)) {
                    nav.navigate(Routes.HISTORY)
                }
            }

            Spacer(Modifier.height(24.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Recent Documents", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { nav.navigate(Routes.HISTORY) }) { Text("See all") }
            }

            val visible = docs.take(3)
            if (visible.isEmpty()) {
                Card(shape = RoundedCornerShape(16.dp)) {
                    Text(
                        "No documents yet.\nScan your first page!",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(24.dp)
                    )
                }
            } else {
                visible.forEach { doc ->
                    DocCard(doc) {
                        PendingScan.reset()
                        PendingScan.draftText = doc.text
                        PendingScan.docName = doc.name
                        PendingScan.firstThumb = doc.thumbnailPath
                        PendingScan.type = doc.type
                        nav.navigate(Routes.RESULT)
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ActionTile(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
        ) {
            Icon(icon, contentDescription = label, tint = BrandBlue, modifier = Modifier.size(30.dp))
            Spacer(Modifier.height(8.dp))
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun DocCard(doc: com.amayra.scanner.data.ScannerStore.DocEntity, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(14.dp)) {
            val bmp = doc.thumbnailPath?.let { path ->
                produceState<Bitmap?>(initialValue = null, path) {
                    value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path) }
                }.value
            }
            if (bmp != null) {
                Image(
                    bmp.asImageBitmap(), contentDescription = null,
                    modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Icon(Icons.Filled.Description, contentDescription = null, tint = BrandBlue)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(doc.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(
                    "${doc.wordCount} words · ${docDate(doc.createdAt)} · ${doc.type.replaceFirstChar { it.uppercase() }}",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------------
// CAMERA
// ---------------------------------------------------------------------

@Composable
fun CameraScreen(vm: ScannerViewModel, nav: NavHostController) = CameraScreenContent(vm, nav)

@Composable
private fun CameraScreenContent(vm: ScannerViewModel, nav: NavHostController) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) { if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA) }

    if (!hasPermission) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().padding(32.dp)
        ) {
            Icon(Icons.Filled.NoPhotography, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Text(
                "Camera permission required for scanning",
                fontSize = 16.sp, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) { Text("Grant camera access") }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { nav.popBackStack() }) { Text("Go back") }
        }
        return
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
    }
    var flashOn by rememberSaveable { mutableStateOf(false) }
    var cameraRef by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    val handwriting = PendingScan.type == "handwriting"

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val listener = Runnable {
            runCatching {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                provider.unbindAll()
                cameraRef = provider.bindToLifecycle(
                    lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
                )
            }
        }
        providerFuture.addListener(listener, ContextCompat.getMainExecutor(context))
        onDispose {
            runCatching { providerFuture.get().unbindAll() }
        }
    }

    LaunchedEffect(flashOn) {
        cameraRef?.cameraControl?.enableTorch(flashOn)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // Framing guide
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 32.dp)
                .fillMaxWidth()
                .aspectRatio(0.75f)
                .border(2.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(16.dp))
        )

        Text(
            if (handwriting) "Handwriting mode — fill the frame with the page"
            else "Position the document inside the frame",
            color = Color.White, fontSize = 14.sp,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp)
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 28.dp)
        ) {
            // Flash toggle
            IconButton(onClick = { flashOn = !flashOn }) {
                Icon(
                    if (flashOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                    contentDescription = "Flash", tint = Color.White
                )
            }

            // Capture
            Button(
                onClick = {
                    val file = File(context.cacheDir, "capture_${System.currentTimeMillis()}.jpg")
                    val opts = ImageCapture.OutputFileOptions.Builder(file).build()
                    imageCapture.takePicture(
                        opts, ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(res: ImageCapture.OutputFileResults) {
                                PendingScan.uris = listOf(Uri.fromFile(file))
                                if (PendingScan.docName.isBlank()) {
                                    PendingScan.docName = "Scan " + SimpleDateFormat("MMM d", Locale.getDefault()).format(Date())
                                }
                                PendingScan.sourceBitmap = BitmapFactory.decodeFile(file.absolutePath)
                                nav.navigate(Routes.EDIT)
                            }

                            override fun onError(exc: ImageCaptureException) {
                                Toast.makeText(context, "Capture failed. Try again.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 34.dp, vertical = 18.dp)
            ) {
                Icon(Icons.Filled.Camera, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Capture")
            }

            // Done (camera) acts as back; keep symmetric layout
            IconButton(onClick = { nav.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
        }
    }
}

// ---------------------------------------------------------------------
// EDIT (crop-free quick edit + enhance + extract)
// ---------------------------------------------------------------------

@Composable
fun EditScreen(vm: ScannerViewModel, nav: NavHostController) = EditScreenContent(vm, nav)

@Composable
private fun EditScreenContent(vm: ScannerViewModel, nav: NavHostController) {
    var brightness by rememberSaveable { mutableStateOf(1f) }
    var contrast by rememberSaveable { mutableStateOf(1.4f) }
    var sharpen by rememberSaveable { mutableStateOf(true) }
    var removeShadow by rememberSaveable { mutableStateOf(true) }
    var blackAndWhite by rememberSaveable { mutableStateOf(false) }
    var rotation by rememberSaveable { mutableStateOf(0f) }

    val preview = remember(PendingScan.sourceBitmap, rotation) {
        PendingScan.sourceBitmap?.let { ImageEnhancer.rotate(it, rotation) }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        TopBar(if (PendingScan.type == "handwriting") "Handwriting Scan" else "Edit Scan") { nav.popBackStack() }

        Card(
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            val bmp = preview
            if (bmp != null) {
                Image(
                    bmp.asImageBitmap(), contentDescription = "Scan preview",
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    contentScale = ContentScale.Fit
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No image selected", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = { sharpen = !sharpen },
                label = { Text(if (sharpen) "Sharpen ✓" else "Sharpen") },
                leadingIcon = { Icon(Icons.Filled.AutoFixHigh, contentDescription = null, Modifier.size(18.dp)) }
            )
            AssistChip(
                onClick = { removeShadow = !removeShadow },
                label = { Text(if (removeShadow) "Shadow ✓" else "Shadow") },
                leadingIcon = { Icon(Icons.Filled.WbSunny, contentDescription = null, Modifier.size(18.dp)) }
            )
            AssistChip(
                onClick = { blackAndWhite = !blackAndWhite },
                label = { Text(if (blackAndWhite) "B&W ✓" else "B&W") },
                leadingIcon = { Icon(Icons.Filled.Contrast, contentDescription = null, Modifier.size(18.dp)) }
            )
            AssistChip(
                onClick = { rotation = (rotation + 90f) % 360f },
                label = { Text("Rotate") },
                leadingIcon = { Icon(Icons.Filled.RotateRight, contentDescription = null, Modifier.size(18.dp)) }
            )
        }

        Spacer(Modifier.height(10.dp))

        Text("Brightness", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = brightness, onValueChange = { brightness = it }, valueRange = 0.7f..1.8f, steps = 10)
        Text("Contrast", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = contrast, onValueChange = { contrast = it }, valueRange = 0.8f..2.2f, steps = 13)

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = {
                val uris = PendingScan.uris
                if (uris.isNotEmpty()) {
                    vm.processImages(
                        uris = uris,
                        type = PendingScan.type,
                        label = if (PendingScan.type == "handwriting") "Converting handwriting into text…"
                        else "Extracting text…",
                        options = ImageEnhancer.Options(
                            rotationDegrees = rotation.toInt(),
                            grayscale = true,
                            contrast = contrast,
                            sharpen = sharpen,
                            removeShadow = removeShadow,
                            blackAndWhite = blackAndWhite,
                            brightness = brightness
                        )
                    )
                    nav.navigate(Routes.RESULT)
                }
            },
            enabled = PendingScan.uris.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(Icons.Filled.DocumentScanner, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Extract Text", fontSize = 16.sp)
        }
        Spacer(Modifier.height(12.dp))
    }
}

// ---------------------------------------------------------------------
// RESULT
// ---------------------------------------------------------------------

@Composable
fun ResultScreen(vm: ScannerViewModel, nav: NavHostController) = ResultScreenContent(vm, nav)

@Composable
private fun ResultScreenContent(vm: ScannerViewModel, nav: NavHostController) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val jobState by vm.job.collectAsState()
    var saved by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf(PendingScan.docName.ifBlank { "Scan" }) }

    DisposableEffect(Unit) {
        onDispose { if (jobState is ScanJob.Done || jobState is ScanJob.Error) vm.resetJob() }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        TopBar("Extracted Text") { nav.popBackStack() }

        when (val j = jobState) {
            is ScanJob.Idle -> {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Nothing scanned yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            is ScanJob.Running -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(j.label, fontSize = 16.sp)
                    if (j.total > 1) {
                        Spacer(Modifier.height(8.dp))
                        Text("Page ${j.done + 1} of ${j.total}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LinearProgressIndicator(
                            progress = { (j.done + 1f) / j.total },
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                        )
                    }
                }
            }
            is ScanJob.Error -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(24.dp)
                ) {
                    Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(j.friendly, textAlign = TextAlign.Center, fontSize = 15.sp)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { nav.popBackStack() }) { Text("Try another image") }
                }
            }
            is ScanJob.Done -> {
                // Seed the editable draft once per finished job.
                LaunchedEffect(j.pages) {
                    if (PendingScan.draftText.isBlank()) {
                        PendingScan.draftText = j.pages.joinToString("\n\n") { it.text }
                        PendingScan.firstThumb = j.pages.firstOrNull()?.thumbnailPath ?: PendingScan.firstThumb
                    }
                }
                val text = PendingScan.draftText

                OutlinedTextField(
                    value = PendingScan.draftText,
                    onValueChange = { PendingScan.draftText = it },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    label = { Text("Recognized text (editable)") },
                    supportingText = {
                        Text(
                            "${ScannerWordCounterUi.count(text)} words · ${text.length} characters"
                        )
                    }
                )

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Document name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(PendingScan.draftText))
                            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp)); Text("Copy")
                    }
                    OutlinedButton(
                        onClick = {
                            vm.saveDocument(name, PendingScan.draftText, PendingScan.type, PendingScan.firstThumb)
                            saved = true
                            Toast.makeText(context, "Saved to history", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Save, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp)); Text(if (saved) "Saved" else "Save")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    // Read Aloud — toggle; speaks/stops the extracted text
                    val isSpeaking = remember(PendingScan.draftText) { mutableStateOf(false) }
                    val tts = remember {
                        android.speech.tts.TextToSpeech(context) { }
                    }
                    DisposableEffect(Unit) {
                        onDispose {
                            runCatching { tts.stop(); tts.shutdown() }
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            if (isSpeaking.value) {
                                tts.stop(); isSpeaking.value = false
                            } else {
                                tts.language = java.util.Locale.getDefault()
                                tts.speak(PendingScan.draftText, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "scan")
                                isSpeaking.value = true
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.VolumeUp, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp)); Text(if (isSpeaking.value) "Stop" else "Listen")
                    }
                    Button(
                        onClick = {
                            if (!Exporter.shareTextToWhatsApp(context, PendingScan.draftText)) {
                                Toast.makeText(context, "WhatsApp not installed", Toast.LENGTH_SHORT).show()
                                Exporter.shareText(context, PendingScan.draftText)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF25D366), contentColor = Color.White
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Chat, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp)); Text("WhatsApp")
                    }
                    Button(
                        onClick = { nav.navigate(Routes.TRANSLATE) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Translate, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp)); Text("Translate")
                    }
                    Button(
                        onClick = {
                            vm.exportPdf(
                                name, PendingScan.draftText,
                                onDone = { file ->
                                    if (!Exporter.sharePdfToWhatsApp(context, file)) {
                                        Exporter.sharePdf(context, file)
                                    }
                                },
                                onError = { msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.PictureAsPdf, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp)); Text("PDF")
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

object ScannerWordCounterUi {
    fun count(text: String): Int = text.trim().split(Regex("\\s+")).count { it.isNotBlank() }
}

// ---------------------------------------------------------------------
// TRANSLATE
// ---------------------------------------------------------------------

@Composable
fun TranslateScreen(vm: ScannerViewModel, nav: NavHostController) = TranslateScreenContent(vm, nav)

@Composable
private fun TranslateScreenContent(vm: ScannerViewModel, nav: NavHostController) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val engine = remember { TranslationEngine(context) }
    val prefs by vm.prefs.collectAsState()

    var sourceCode by rememberSaveable { mutableStateOf("en") }
    var targetCode by rememberSaveable { mutableStateOf(prefs.targetLanguage) }
    var outcome by remember { mutableStateOf<TranslationEngine.Outcome?>(null) }

    val sourceText = PendingScan.draftText

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        TopBar("Translate") { nav.popBackStack() }

        if (sourceText.isBlank()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Scan something first, then come back to translate.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            return@Column
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            LanguagePicker("From", sourceCode, Modifier.weight(1f)) { sourceCode = it }
            IconButton(
                onClick = {
                    val old = sourceCode
                    sourceCode = targetCode
                    targetCode = old
                }
            ) {
                Icon(Icons.Filled.SwapHoriz, contentDescription = "Swap languages", tint = BrandBlue)
            }
            LanguagePicker("To", targetCode, Modifier.weight(1f)) {
                targetCode = it
                vm.setTargetLanguage(it)
            }
        }

        Spacer(Modifier.height(10.dp))

        // One-tap pairs — Hindi to English / French / German
        Text("Quick translate", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickPair("हिंदी → English", "hi", "en") { s, t -> sourceCode = s; targetCode = t; vm.setTargetLanguage(t) }
            QuickPair("हिंदी → Français", "hi", "fr") { s, t -> sourceCode = s; targetCode = t; vm.setTargetLanguage(t) }
            QuickPair("हिंदी → Deutsch", "hi", "de") { s, t -> sourceCode = s; targetCode = t; vm.setTargetLanguage(t) }
        }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = {
                scope.launch {
                    outcome = TranslationEngine.Outcome.Downloading("Translating… (first time downloads a language model)")
                    outcome = engine.translate(sourceText, sourceCode, targetCode)
                }
            },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(Icons.Filled.Translate, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Translate")
        }

        Spacer(Modifier.height(14.dp))

        Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Original", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(sourceText, fontSize = 15.sp, maxLines = 6)
            }
        }

        Spacer(Modifier.height(12.dp))

        when (val o = outcome) {
            null -> {}
            is TranslationEngine.Outcome.Downloading -> {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp)); Text(o.message, fontSize = 13.sp)
                }
            }
            is TranslationEngine.Outcome.Failure -> {
                Text(o.friendly, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
            }
            is TranslationEngine.Outcome.Success -> {
                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Translated (${engine.labelFor(targetCode)})", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(o.translated, fontSize = 16.sp)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(o.translated))
                            Toast.makeText(context, "Translation copied", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp)); Text("Copy")
                    }
                    OutlinedButton(
                        onClick = { Exporter.shareText(context, o.translated) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp)); Text("Share")
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun QuickPair(label: String, from: String, to: String, onPick: (String, String) -> Unit) {
    FilterChip(
        selected = false,
        onClick = { onPick(from, to) },
        label = { Text(label, fontSize = 11.sp, maxLines = 1) }
    )
}

@Composable
private fun LanguagePicker(label: String, selected: String, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = TRANSLATE_LANGUAGES.firstOrNull { it.first == selected }
    Column(modifier) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(current?.second ?: selected, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            TRANSLATE_LANGUAGES.forEach { (code, name) ->
                DropdownMenuItem(
                    text = { Text(name + if (code == selected) " ✓" else "") },
                    onClick = { onSelect(code); open = false }
                )
            }
        }
    }
}

// ---------------------------------------------------------------------
// HISTORY
// ---------------------------------------------------------------------

@Composable
fun HistoryScreen(vm: ScannerViewModel, nav: NavHostController) = HistoryScreenContent(vm, nav)

@Composable
private fun HistoryScreenContent(vm: ScannerViewModel, nav: NavHostController) {
    val context = LocalContext.current
    val docs by vm.docs.collectAsState()
    var renameTarget by remember { mutableStateOf<com.amayra.scanner.data.ScannerStore.DocEntity?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }

    val filtered = if (query.isBlank()) docs else docs.filter {
        it.name.contains(query, ignoreCase = true) || it.text.contains(query, ignoreCase = true)
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            IconButton(onClick = { nav.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("History", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (docs.isNotEmpty()) {
                TextButton(onClick = { confirmClear = true }) { Text("Clear") }
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("Search documents and text…") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search")
                    }
                }
            },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(10.dp))

        if (filtered.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    if (query.isBlank()) "Nothing here yet." else "No matches for \"$query\"",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(filtered, key = { it.id }) { doc ->
                    var menuOpen by remember { mutableStateOf(false) }
                    Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(14.dp)) {
                            Column(
                                Modifier.weight(1f).clickable {
                                    PendingScan.reset()
                                    PendingScan.draftText = doc.text
                                    PendingScan.docName = doc.name
                                    PendingScan.firstThumb = doc.thumbnailPath
                                    PendingScan.type = doc.type
                                    nav.navigate(Routes.RESULT)
                                }
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (doc.pinned) {
                                        Icon(
                                            Icons.Filled.PushPin, contentDescription = "Pinned",
                                            modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(Modifier.width(4.dp))
                                    }
                                    Text(doc.name, fontWeight = FontWeight.SemiBold)
                                }
                                Text(
                                    "${doc.wordCount} words · ${docDate(doc.createdAt)} · ${doc.type.replaceFirstChar { c -> c.uppercase() }}",
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = "Actions")
                                }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Open") },
                                        onClick = {
                                            menuOpen = false
                                            PendingScan.reset()
                                            PendingScan.draftText = doc.text
                                            PendingScan.docName = doc.name
                                            PendingScan.firstThumb = doc.thumbnailPath
                                            PendingScan.type = doc.type
                                            nav.navigate(Routes.RESULT)
                                        }
                                    )
                                    DropdownMenuItem(text = { Text("Rename") }, onClick = { renameTarget = doc; menuOpen = false })
                                    DropdownMenuItem(
                                        text = { Text(if (doc.pinned) "Unpin" else "Pin to top") },
                                        leadingIcon = { Icon(Icons.Filled.PushPin, contentDescription = null, Modifier.size(18.dp)) },
                                        onClick = { vm.setPinned(doc.id, !doc.pinned); menuOpen = false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Share to WhatsApp") },
                                        leadingIcon = { Icon(Icons.Filled.Chat, contentDescription = null, Modifier.size(18.dp)) },
                                        onClick = {
                                            if (!Exporter.shareTextToWhatsApp(context, doc.text)) {
                                                Toast.makeText(context, "WhatsApp not installed", Toast.LENGTH_SHORT).show()
                                            }
                                            menuOpen = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Share text") },
                                        onClick = { Exporter.shareText(context, doc.text); menuOpen = false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Export PDF") },
                                        onClick = {
                                            vm.exportPdf(
                                                doc.name, doc.text,
                                                onDone = { f -> Exporter.sharePdf(context, f) },
                                                onError = { m -> Toast.makeText(context, m, Toast.LENGTH_SHORT).show() }
                                            )
                                            menuOpen = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Delete") },
                                        onClick = { vm.deleteDocument(doc.id); menuOpen = false }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    renameTarget?.let { target ->
        var newName by remember(target.id) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename document") },
            text = {
                OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank()) vm.renameDocument(target.id, newName.trim())
                    renameTarget = null
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } }
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?") },
            text = { Text("All saved documents will be removed from this device. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { vm.clearHistory(); confirmClear = false }) { Text("Clear all") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }
}

// ---------------------------------------------------------------------
// SETTINGS
// ---------------------------------------------------------------------

@Composable
fun SettingsScreen(vm: ScannerViewModel, nav: NavHostController) = SettingsScreenContent(vm, nav)

@Composable
private fun SettingsScreenContent(vm: ScannerViewModel, nav: NavHostController) {
    val context = LocalContext.current
    val prefs by vm.prefs.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    var showPrivacy by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            IconButton(onClick = { nav.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Settings", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }

        SectionCard("Appearance") {
            Text("Theme", fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (mode, label) ->
                    FilterChip(
                        selected = prefs.themeMode == mode,
                        onClick = { vm.setThemeMode(mode) },
                        label = { Text(label) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        SectionCard("Scanning & Recognition") {
            Text("Default text script", fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            val scripts = listOf("auto" to "Auto (recommended)", "latin" to "Latin / English", "devanagari" to "Devanagari / Hindi", "chinese" to "Chinese", "japanese" to "Japanese", "korean" to "Korean")
            scripts.forEach { (code, label) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { vm.setOcrScript(code) }.padding(vertical = 6.dp)
                ) {
                    RadioButton(selected = prefs.ocrScript == code, onClick = { vm.setOcrScript(code) })
                    Text(label, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            SettingToggle(
                title = "Auto-enhance images",
                subtitle = "Grayscale, sharpen and shadow removal before OCR",
                checked = prefs.autoEnhance,
                onChange = { vm.setAutoEnhance(it) }
            )
            SettingToggle(
                title = "Read text aloud automatically",
                subtitle = "Speak extracted text after every scan",
                checked = prefs.autoSpeak,
                onChange = { vm.setAutoSpeak(it) }
            )
        }

        Spacer(Modifier.height(12.dp))

        SectionCard("Translation") {
            Text("Default target language", fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            TRANSLATE_LANGUAGES.forEach { (code, label) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { vm.setTargetLanguage(code) }.padding(vertical = 6.dp)
                ) {
                    RadioButton(selected = prefs.targetLanguage == code, onClick = { vm.setTargetLanguage(code) })
                    Text(label, fontSize = 14.sp)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        SectionCard("Data & Privacy") {
            TextButton(onClick = { showPrivacy = true }) {
                Icon(Icons.Filled.PrivacyTip, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Privacy policy")
            }
            TextButton(onClick = { confirmClear = true }) {
                Icon(Icons.Filled.Delete, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Clear all history")
            }
        }

        Spacer(Modifier.height(12.dp))

        SectionCard("About") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.logo_bamania),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("AI TEXT SCANNER APP", fontWeight = FontWeight.SemiBold)
                    Text("by Bamania's Tech", fontSize = 12.sp, color = NeonPurple)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("Version 1.1 · On-device OCR by Google ML Kit", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = {
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(
                        android.content.Intent.EXTRA_TEXT,
                        "AI TEXT SCANNER APP — scan, extract and translate text offline. By Bamania's Tech"
                    )
                }
                context.startActivity(android.content.Intent.createChooser(send, "Share app"))
            }) {
                Icon(Icons.Filled.Share, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Share this app")
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showPrivacy) {
        AlertDialog(
            onDismissRequest = { showPrivacy = false },
            title = { Text("Privacy policy") },
            text = {
                Text(
                    "AI TEXT SCANNER APP works entirely on your device.\n\n" +
                        "• Text recognition and translation run locally (Google ML Kit on-device models).\n" +
                        "• Your scans, extracted text and exports never leave your phone.\n" +
                        "• No account, sign-up or login is required.\n" +
                        "• No analytics, ads or trackers.\n" +
                        "• Translation language models (~30 MB each) download once over the internet and then work offline.\n" +
                        "• Clearing history permanently deletes all saved documents from this device.",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { showPrivacy = false }) { Text("Got it") }
            }
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all history?") },
            text = { Text("All saved documents will be removed from this device.") },
            confirmButton = {
                TextButton(onClick = { vm.clearHistory(); confirmClear = false }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SettingToggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp)
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = BrandBlue)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}
