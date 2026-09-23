package com.flowtools.pairing

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage

@Composable
fun QrScannerView(onCode: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = it
    }
    LaunchedEffect(Unit) {
        if (!hasPermission) launcher.launch(Manifest.permission.CAMERA)
    }
    if (!hasPermission) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("A câmara é necessária para ler o QR do PC.")
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.height(48.dp)) {
                Text("Permitir câmara")
            }
        }
        return
    }
    val scanner = remember { BarcodeScanning.getClient() }
    var providerRef by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var lastAt by remember { mutableLongStateOf(0L) }
    var cameraError by remember { mutableStateOf(false) }
    if (cameraError) {
        Text(
            "Câmara indisponível neste dispositivo. Insira o código do PC abaixo.",
            modifier = modifier,
        )
        return
    }
    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            ProcessCameraProvider.getInstance(ctx).addListener({
                try {
                    val provider = ProcessCameraProvider.getInstance(ctx).get()
                    providerRef = provider
                    val preview = Preview.Builder().build()
                    preview.setSurfaceProvider(previewView.surfaceProvider)
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { ia ->
                            ia.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { proxy ->
                                val media = proxy.image
                                if (media == null) {
                                    proxy.close()
                                } else {
                                    val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                                    scanner.process(image)
                                        .addOnSuccessListener { barcodes ->
                                            val now = SystemClock.elapsedRealtime()
                                            val raw = barcodes.firstOrNull()?.rawValue
                                            if (raw != null && now - lastAt > 1500) {
                                                lastAt = now
                                                onCode(raw)
                                            }
                                        }
                                        .addOnCompleteListener { proxy.close() }
                                }
                            }
                        }
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                } catch (_: Exception) {
                    cameraError = true
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
        onRelease = { providerRef?.unbindAll() },
        modifier = modifier.fillMaxWidth().aspectRatio(1f),
    )
}

@Composable
fun PairingFlow(
    vm: PairingViewModel,
    onDone: () -> Unit,
    onClaim: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val approved by vm.approved.collectAsState()
    val deviceName by vm.deviceName.collectAsState()
    var manualId by remember { mutableStateOf("") }
    var manualCode by remember { mutableStateOf("") }
    var manualHost by remember { mutableStateOf("http://192.168.1.5:8787") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Emparelhar PC", style = MaterialTheme.typography.headlineSmall)
        when (val s = state) {
            is PairUiState.Idle -> {
                QrScannerView(onCode = vm::onScanned)
                Text("Sem câmara? Insira o código do PC:", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(manualId, { manualId = it }, label = { Text("ID do emparelhamento") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(manualCode, { manualCode = it }, label = { Text("Código de 6 dígitos") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(manualHost, { manualHost = it }, label = { Text("Endereço do PC") }, modifier = Modifier.fillMaxWidth())
                Button(
                    onClick = { vm.onManual(manualId, manualCode, manualHost) },
                    modifier = Modifier.height(48.dp),
                    enabled = manualCode.length == 6,
                ) { Text("Continuar") }
            }
            is PairUiState.Scanned -> {
                Text("Código ${s.invite.code} · ${s.invite.host}", style = MaterialTheme.typography.bodyMedium)
                Text("Permissões (autorize só as que quiser):", style = MaterialTheme.typography.titleSmall)
                ALL_CAPABILITY_LABELS.forEach { label ->
                    Row {
                        Checkbox(
                            checked = approved.contains(label),
                            onCheckedChange = { vm.toggle(label) },
                        )
                        Text(label, modifier = Modifier.padding(top = 12.dp))
                    }
                }
                OutlinedTextField(deviceName, vm::setDeviceName, label = { Text("Nome deste telemóvel") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = onClaim, modifier = Modifier.height(48.dp)) { Text("Autorizar selecionadas") }
                TextButton(onClick = vm::retry) { Text("Ler outro QR") }
            }
            is PairUiState.Claiming -> {
                CircularProgressIndicator()
                Text("A ligar ao PC…")
            }
            is PairUiState.Error -> {
                Text(s.message, color = MaterialTheme.colorScheme.error)
                Button(onClick = vm::retry, modifier = Modifier.height(48.dp)) { Text("Tentar novamente") }
            }
            is PairUiState.Done -> {
                Text("PC emparelhado.")
                LaunchedEffect(Unit) { onDone() }
            }
        }
    }
}
