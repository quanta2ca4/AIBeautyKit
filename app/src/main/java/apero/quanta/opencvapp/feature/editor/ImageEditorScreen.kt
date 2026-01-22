package apero.quanta.opencvapp.feature.editor

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Environment
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
import java.io.OutputStream

@Composable
fun ImageEditorScreen() {
// ... (code unchanged until the end of the file)

fun saveBitmapToGallery(context: android.content.Context, bitmap: Bitmap) {
    val filename = "Warped_${System.currentTimeMillis()}.jpg"
    var fos: OutputStream? = null
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/OpenCVApp")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
    }

    val imageUri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

    imageUri?.let { uri ->
        try {
            fos = context.contentResolver.openOutputStream(uri)
            fos?.use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                context.contentResolver.update(uri, contentValues, null, null)
            }
            Toast.makeText(context, "Saved to Gallery!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Save Failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
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

    fun detectFace(bmp: Bitmap) {
        faceMeshHelper.processImage(bmp, 
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

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val bmp = loadBitmapFromUri(context, uri)
            // Resize if too big to avoid OOM
            val scaledBmp = scaleBitmapDown(bmp, 1024)
            
            // Reset effects
            eyeEnlargement = 0f
            chinSlimming = 0f
            
            bitmap = scaledBmp
            detectFace(scaledBmp)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(onClick = { 
                pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) {
                Text(if (bitmap == null) "Select Image" else "Change")
            }
            
            if (bitmap != null) {
                Button(onClick = {
                    val warpedBmp = warpView?.getWarpedBitmap()
                    if (warpedBmp != null) {
                        bitmap = warpedBmp
                        eyeEnlargement = 0f
                        chinSlimming = 0f
                        detectFace(warpedBmp)
                        Toast.makeText(context, "Applied!", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text("Apply")
                }
                
                Button(onClick = {
                    val finalBmp = warpView?.getWarpedBitmap()
                    if (finalBmp != null) {
                        saveBitmapToGallery(context, finalBmp)
                    }
                }) {
                    Text("Save")
                }
            }
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
                        // Only update levels, don't re-setImage here unless the bitmap object itself changed
                        view.eyeEnlargementLevel = eyeEnlargement
                        view.chinSlimmingLevel = chinSlimming
                        
                        // We use a tag or a simple check to see if we need to reload the bitmap
                        if (view.tag != bitmap) {
                            view.setImage(bitmap!!)
                            view.tag = bitmap
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text("No Image Selected", modifier = Modifier.align(Alignment.Center))
            }
        }
// ... (phần còn lại của code)
        
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
