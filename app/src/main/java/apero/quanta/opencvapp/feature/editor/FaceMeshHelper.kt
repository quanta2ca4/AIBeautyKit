package apero.quanta.opencvapp.feature.editor

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.facemesh.FaceMesh
import com.google.mlkit.vision.facemesh.FaceMeshDetection
import com.google.mlkit.vision.facemesh.FaceMeshDetector
import com.google.mlkit.vision.facemesh.FaceMeshDetectorOptions

class FaceMeshHelper(context: Context) {

    private val detector: FaceMeshDetector

    init {
        val options = FaceMeshDetectorOptions.Builder()
            .setUseCase(FaceMeshDetectorOptions.FACE_MESH)
            .build()
        detector = FaceMeshDetection.getClient(options)
    }

    fun processImage(bitmap: Bitmap, onSuccess: (List<FaceMesh>) -> Unit, onFailure: (Exception) -> Unit) {
        val image = InputImage.fromBitmap(bitmap, 0)
        detector.process(image)
            .addOnSuccessListener { result ->
                onSuccess(result)
            }
            .addOnFailureListener { e ->
                onFailure(e)
            }
    }

    fun close() {
        detector.close()
    }
}
