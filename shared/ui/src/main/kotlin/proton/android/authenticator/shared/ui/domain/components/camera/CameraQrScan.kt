/*
 * Copyright (c) 2025 Proton AG
 * This file is part of Proton AG and Proton Authenticator.
 *
 * Proton Authenticator is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Proton Authenticator is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Proton Authenticator.  If not, see <https://www.gnu.org/licenses/>.
 */

package proton.android.authenticator.shared.ui.domain.components.camera

import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import proton.android.authenticator.shared.ui.domain.analyzers.QrCodeAnalyzer

@Composable
fun CameraQrScan(
    onQrCodeScanned: (String, ByteArray) -> Unit,
    onCameraError: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var cutoutRect by remember {
        mutableStateOf(Rect.Zero)
    }

    var cameraProvider by remember {
        mutableStateOf<ProcessCameraProvider?>(null)
    }

    val cameraSelector = remember {
        CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()
    }

    LaunchedEffect(Unit) {
        runCatching {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                runCatching {
                    cameraProvider = cameraProviderFuture.get()
                }.onFailure { _: Throwable ->
                    onCameraError()
                }
            }, ContextCompat.getMainExecutor(context))
        }.onFailure { _: Throwable ->
            onCameraError()
        }
    }

    val preview = remember {
        Preview.Builder()
            .build()
    }

    val imageAnalysis = remember {
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
    }

    var previewViewSize by remember {
        mutableStateOf(Size.Zero)
    }

    var canScanCode by remember {
        mutableStateOf(true)
    }

    DisposableEffect(key1 = lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> true
                Lifecycle.Event.ON_PAUSE -> false
                Lifecycle.Event.ON_CREATE,
                Lifecycle.Event.ON_START,
                Lifecycle.Event.ON_STOP,
                Lifecycle.Event.ON_DESTROY,
                Lifecycle.Event.ON_ANY -> null
            }?.also { isScanAllowed -> canScanCode = isScanAllowed }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)

            cameraProvider?.unbindAll()
        }
    }

    LaunchedEffect(cameraProvider) {
        val provider = cameraProvider ?: return@LaunchedEffect

        runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis
            )
        }.onFailure { _: Throwable ->
            onCameraError()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { factoryContext ->
            PreviewView(factoryContext).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE

                post {
                    previewViewSize = Size(
                        width = width.toFloat(),
                        height = height.toFloat()
                    )
                }

                preview.surfaceProvider = surfaceProvider
            }
        }
    )

    CameraQrScanMask(cutoutRect = cutoutRect)

    LaunchedEffect(key1 = previewViewSize) {
        if (previewViewSize == Size.Zero) return@LaunchedEffect

        val cutoutSize = previewViewSize.minDimension * 0.7f
        val left = previewViewSize.width.minus(cutoutSize).div(2)
        val top = previewViewSize.height.minus(cutoutSize).div(3)
        cutoutRect = Rect(
            left = left,
            top = top,
            right = left.plus(cutoutSize),
            bottom = top.plus(cutoutSize)
        )

        imageAnalysis.setAnalyzer(
            ContextCompat.getMainExecutor(context),
            QrCodeAnalyzer(
                onQrCodeScanned = { qrCodeValue, qrCodeBytes ->
                    if (canScanCode) {
                        canScanCode = false

                        onQrCodeScanned(qrCodeValue, qrCodeBytes)
                    }
                }
            )
        )
    }
}
