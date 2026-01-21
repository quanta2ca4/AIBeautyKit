package apero.quanta.opencvapp.feature.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import com.google.mlkit.vision.common.PointF3D
import com.google.mlkit.vision.facemesh.FaceMesh
import com.google.mlkit.vision.facemesh.FaceMeshPoint
import kotlin.math.sqrt

class ImageWarpView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var bitmap: Bitmap? = null
    
    // Mesh configuration
    private val meshWidth = 40
    private val meshHeight = 40
    private val count = (meshWidth + 1) * (meshHeight + 1)
    private val verts = FloatArray(count * 2)
    private val originalVerts = FloatArray(count * 2)

    private var faceMesh: FaceMesh? = null
    
    // Face Mesh Indices for Eyes (Standard 468 landmarks)
    private val LEFT_EYE_INDICES = listOf(33, 246, 161, 160, 159, 158, 157, 173, 133, 155, 154, 153, 145, 144, 163, 7)
    private val RIGHT_EYE_INDICES = listOf(362, 398, 384, 385, 386, 387, 388, 466, 263, 249, 390, 373, 374, 380, 381, 382)

    // Effect levels (0.0 to 1.0)
    var eyeEnlargementLevel: Float = 0f
        set(value) {
            field = value
            applyWarp()
        }
    
    var chinSlimmingLevel: Float = 0f
        set(value) {
            field = value
            applyWarp()
        }

    fun setImage(bmp: Bitmap) {
        this.bitmap = bmp
        initializeMesh(bmp.width.toFloat(), bmp.height.toFloat())
        invalidate()
    }

    fun setFaceMesh(mesh: FaceMesh) {
        this.faceMesh = mesh
    }

    private fun initializeMesh(width: Float, height: Float) {
        var index = 0
        for (y in 0..meshHeight) {
            val fy = height * y / meshHeight
            for (x in 0..meshWidth) {
                val fx = width * x / meshWidth
                verts[index * 2] = fx
                verts[index * 2 + 1] = fy
                originalVerts[index * 2] = fx
                originalVerts[index * 2 + 1] = fy
                index += 1
            }
        }
    }

    private fun applyWarp() {
        if (bitmap == null || faceMesh == null) return

        // Reset to original
        System.arraycopy(originalVerts, 0, verts, 0, originalVerts.size)

        val meshPoints = faceMesh!!.allPoints
        if (meshPoints.isEmpty()) return

        // 1. Calculate Centers and Radii
        val leftEyePoints = getPoints(meshPoints, LEFT_EYE_INDICES)
        val rightEyePoints = getPoints(meshPoints, RIGHT_EYE_INDICES)

        val leftEyeCenter = computeCenter(leftEyePoints)
        val rightEyeCenter = computeCenter(rightEyePoints)

        val leftRadius = if (leftEyeCenter != null) computeRadius(leftEyeCenter, leftEyePoints) * 1.5f else 0f
        val rightRadius = if (rightEyeCenter != null) computeRadius(rightEyeCenter, rightEyePoints) * 1.5f else 0f

        // 2. Apply Warp to Mesh Vertices
        for (i in 0 until count) {
            val ox = originalVerts[i * 2]
            val oy = originalVerts[i * 2 + 1]

            var totalDx = 0f
            var totalDy = 0f

            // Apply Left Eye Enlarge
            if (leftEyeCenter != null && eyeEnlargementLevel > 0) {
                val offset = calculateEnlargeOffset(ox, oy, leftEyeCenter.x, leftEyeCenter.y, leftRadius, eyeEnlargementLevel)
                totalDx += offset.first
                totalDy += offset.second
            }

            // Apply Right Eye Enlarge
            if (rightEyeCenter != null && eyeEnlargementLevel > 0) {
                val offset = calculateEnlargeOffset(ox, oy, rightEyeCenter.x, rightEyeCenter.y, rightRadius, eyeEnlargementLevel)
                totalDx += offset.first
                totalDy += offset.second
            }
            
            // Apply Chin Slimming
            if (chinSlimmingLevel > 0) {
                val chinPoint = getPoint(meshPoints, 152)
                if (chinPoint != null) {
                    val cx = chinPoint.position.x
                    val cy = chinPoint.position.y
                    val r = 300f
                    val s = 0.2f * chinSlimmingLevel
                    val chinOffset = calculateSlimOffset(ox, oy, cx, cy, r, s)
                    totalDx += chinOffset.first
                    totalDy += chinOffset.second
                }
            }

            verts[i * 2] = ox + totalDx
            verts[i * 2 + 1] = oy + totalDy
        }

        invalidate()
    }

    private fun calculateEnlargeOffset(x: Float, y: Float, cx: Float, cy: Float, radius: Float, strength: Float): Pair<Float, Float> {
        val dx = x - cx
        val dy = y - cy
        val dist = sqrt(dx * dx + dy * dy)

        if (dist < radius) {
            val t = dist / radius
            val weight = 1 - t * t
            val scale = 1 + strength * weight
            
            val scaleMinus1 = scale - 1
            return Pair(dx * scaleMinus1, dy * scaleMinus1)
        }
        return Pair(0f, 0f)
    }
    
    private fun calculateSlimOffset(x: Float, y: Float, cx: Float, cy: Float, radius: Float, strength: Float): Pair<Float, Float> {
        if (y < cy - radius) return Pair(0f, 0f)
        
        val dx = x - cx
        val dy = y - cy
        val dist = sqrt(dx * dx + dy * dy)
        
        if (dist < radius) {
             val influence = (1 - dist / radius)
             val factor = 1 - strength * influence 
             return Pair(dx * (factor - 1), 0f)
        }
        return Pair(0f, 0f)
    }

    private fun computeCenter(points: List<FaceMeshPoint>): PointF3D? {
        if (points.isEmpty()) return null
        val sumX = points.sumOf { it.position.x.toDouble() }
        val sumY = points.sumOf { it.position.y.toDouble() }
        return PointF3D.from((sumX / points.size).toFloat(), (sumY / points.size).toFloat(), 0f)
    }

    private fun computeRadius(center: PointF3D, points: List<FaceMeshPoint>): Float {
        if (points.isEmpty()) return 0f
        return points.maxOf { 
            val dx = it.position.x - center.x
            val dy = it.position.y - center.y
            sqrt(dx * dx + dy * dy)
        }
    }

    private fun getPoints(allPoints: List<FaceMeshPoint>, indices: List<Int>): List<FaceMeshPoint> {
        return indices.mapNotNull { index -> allPoints.find { it.index == index } }
    }

    private fun getPoint(points: List<FaceMeshPoint>, index: Int): FaceMeshPoint? {
        return points.find { it.index == index }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bitmap?.let {
            canvas.drawBitmapMesh(it, meshWidth, meshHeight, verts, 0, null, 0, null)
        }
    }
}