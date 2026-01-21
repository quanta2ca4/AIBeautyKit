package apero.quanta.opencvapp.feature.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun ImageEditorScreen() {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var eyeEnlargement by remember { mutableFloatStateOf(0f) }
    var chinSlimming by remember { mutableFloatStateOf(0f) }
    
    // Helper for FaceMesh
    val faceMeshHelper = remember { FaceMeshHelper(context) }
    
    DisposableEffect(Unit) {
        onDispose {
            faceMeshHelper.close()
        }
    }

    // Reference to the custom view to update properties
    var warpView: ImageWarpView? by remember { mutableStateOf(null) }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val bmp = loadBitmapFromUri(context, uri)
            // Resize if too big to avoid OOM
            val scaledBmp = scaleBitmapDown(bmp, 1024)
            bitmap = scaledBmp
            
            // Run Detection
            faceMeshHelper.processImage(scaledBmp, 
                onSuccess = { meshes ->
                    if (meshes.isNotEmpty()) {
                        warpView?.setFaceMesh(meshes[0])
                        Toast.makeText(context, "Face Detected!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "No face detected.", Toast.LENGTH_SHORT).show()
                    }
                },
                onFailure = {
                    Toast.makeText(context, "Detection Failed: ${it.message}", Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Button(
            onClick = { 
                pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text("Select Image")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (bitmap != null) {
                AndroidView(
                    factory = { ctx ->
                        ImageWarpView(ctx).apply {
                            warpView = this
                            setImage(bitmap!!)
                        }
                    },
                    update = { view ->
                        // Update view properties when compose state changes
                        view.eyeEnlargementLevel = eyeEnlargement
                        view.chinSlimmingLevel = chinSlimming
                        
                        // If bitmap changed (e.g. new selection), update it.
                        // Ideally checking if it's the same object reference.
                        // Here we just ensure we set it if needed.
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text("No Image Selected", modifier = Modifier.align(Alignment.Center))
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))

        Text("Eye Enlargement: ${(eyeEnlargement * 100).toInt()}%")
        Slider(
            value = eyeEnlargement,
            onValueChange = { eyeEnlargement = it },
            valueRange = 0f..1f
        )

        Text("Slim Chin: ${(chinSlimming * 100).toInt()}%")
        Slider(
            value = chinSlimming,
            onValueChange = { chinSlimming = it },
            valueRange = 0f..1f
        )
    }
}

fun loadBitmapFromUri(context: android.content.Context, uri: Uri): Bitmap {
    return if (Build.VERSION.SDK_INT < 28) {
        MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
    } else {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
            decoder.isMutableRequired = true // Ensure mutable for software rendering if needed
        }
    }
}

fun scaleBitmapDown(bitmap: Bitmap, maxDimension: Int): Bitmap {
    val originalWidth = bitmap.width
    val originalHeight = bitmap.height
    var resizedWidth = originalWidth
    var resizedHeight = originalHeight

    if (originalHeight > maxDimension || originalWidth > maxDimension) {
        if (originalWidth > originalHeight) {
            resizedWidth = maxDimension
            resizedHeight = (resizedWidth * originalHeight / originalWidth)
        } else {
            resizedHeight = maxDimension
            resizedWidth = (resizedHeight * originalWidth / originalHeight)
        }
    }
    return Bitmap.createScaledBitmap(bitmap, resizedWidth, resizedHeight, true)
}
