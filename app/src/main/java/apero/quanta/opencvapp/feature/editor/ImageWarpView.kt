package apero.quanta.opencvapp.feature.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.google.mlkit.vision.common.PointF3D
import com.google.mlkit.vision.facemesh.FaceMesh
import com.google.mlkit.vision.facemesh.FaceMeshPoint
import kotlin.math.pow
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
    
    // For manual warping (Liquify)
    private val manualVertsOffset = FloatArray(count * 2)
    private var lastX = 0f
    private var lastY = 0f
    private val brushRadius = 150f
    private val brushStrength = 0.5f

    // Cached view transform for touch mapping
    private var viewScale = 1f
    private var viewDx = 0f
    private var viewDy = 0f

    private var faceMesh: FaceMesh? = null
    // Cached warp parameters
    private var cachedLeftEyeCenter: PointF3D? = null
    private var cachedRightEyeCenter: PointF3D? = null
    private var cachedLeftRadius: Float = 0f
    private var cachedRightRadius: Float = 0f
    private var cachedChinPoint: PointF3D? = null

    private val meshPaint = Paint().apply {
        color = Color.CYAN
        style = Paint.Style.STROKE
        strokeWidth = 1f
        alpha = 100
    }

    private val pointPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }
    
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
        // Reset manual offsets for new image
        for (i in manualVertsOffset.indices) manualVertsOffset[i] = 0f
        invalidate()
    }

    fun setFaceMesh(mesh: FaceMesh) {
        this.faceMesh = mesh
        updateWarpParameters()
        applyWarp()
    }

    fun getWarpedBitmap(): Bitmap? {
        val src = bitmap ?: return null
        val result = Bitmap.createBitmap(src.width, src.height, src.config ?: Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawBitmapMesh(src, meshWidth, meshHeight, verts, 0, null, 0, null)
        return result
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null) return false

        // Map touch coordinates to bitmap coordinates
        val bx = (event.x - viewDx) / viewScale
        val by = (event.y - viewDy) / viewScale

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastX = bx
                lastY = by
            }
            MotionEvent.ACTION_MOVE -> {
                handleManualWarp(bx, by)
                lastX = bx
                lastY = by
                applyWarp()
            }
        }
        return true
    }

    private fun handleManualWarp(currentX: Float, currentY: Float) {
        val dx = currentX - lastX
        val dy = currentY - lastY
        
        for (i in 0 until count) {
            val ox = originalVerts[i * 2]
            val oy = originalVerts[i * 2 + 1]
            
            val dist = sqrt((currentX - ox).pow(2) + (currentY - oy).pow(2))
            if (dist < brushRadius) {
                val weight = (1 - dist / brushRadius).pow(2)
                manualVertsOffset[i * 2] += dx * weight * brushStrength
                manualVertsOffset[i * 2 + 1] += dy * weight * brushStrength
            }
        }
    }

    private fun updateWarpParameters() {
        val mesh = faceMesh ?: return
        val meshPoints = mesh.allPoints
        if (meshPoints.isEmpty()) return

        val leftEyePoints = getPoints(meshPoints, LEFT_EYE_INDICES)
        val rightEyePoints = getPoints(meshPoints, RIGHT_EYE_INDICES)

        cachedLeftEyeCenter = computeCenter(leftEyePoints)
        cachedRightEyeCenter = computeCenter(rightEyePoints)

        cachedLeftRadius = if (cachedLeftEyeCenter != null) computeRadius(cachedLeftEyeCenter!!, leftEyePoints) * 1.5f else 0f
        cachedRightRadius = if (cachedRightEyeCenter != null) computeRadius(cachedRightEyeCenter!!, rightEyePoints) * 1.5f else 0f
        
        cachedChinPoint = getPoint(meshPoints, 152)?.position
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
        if (bitmap == null) return

        // 1. Combine Original + Manual Offsets
        for (i in 0 until count) {
            val baseStartX = originalVerts[i * 2] + manualVertsOffset[i * 2]
            val baseStartY = originalVerts[i * 2 + 1] + manualVertsOffset[i * 2 + 1]

            // 2. Apply Automatic Effects (Face Mesh) on top
            val warped = getWarpedPoint(baseStartX, baseStartY)
            verts[i * 2] = warped.first
            verts[i * 2 + 1] = warped.second
        }

        invalidate()
    }

    private fun getWarpedPoint(ox: Float, oy: Float): Pair<Float, Float> {
        var totalDx = 0f
        var totalDy = 0f

        // Apply Left Eye Enlarge
        if (cachedLeftEyeCenter != null && eyeEnlargementLevel > 0) {
            val offset = calculateEnlargeOffset(ox, oy, cachedLeftEyeCenter!!.x, cachedLeftEyeCenter!!.y, cachedLeftRadius, eyeEnlargementLevel)
            totalDx += offset.first
            totalDy += offset.second
        }

        // Apply Right Eye Enlarge
        if (cachedRightEyeCenter != null && eyeEnlargementLevel > 0) {
            val offset = calculateEnlargeOffset(ox, oy, cachedRightEyeCenter!!.x, cachedRightEyeCenter!!.y, cachedRightRadius, eyeEnlargementLevel)
            totalDx += offset.first
            totalDy += offset.second
        }

        // Apply Chin Slimming
        if (chinSlimmingLevel > 0 && cachedChinPoint != null) {
            val cx = cachedChinPoint!!.x
            val cy = cachedChinPoint!!.y
            val r = 300f
            val s = 0.2f * chinSlimmingLevel
            val chinOffset = calculateSlimOffset(ox, oy, cx, cy, r, s)
            totalDx += chinOffset.first
            totalDy += chinOffset.second
        }

        return Pair(ox + totalDx, oy + totalDy)
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
        val bmp = bitmap ?: return

        // Calculate scale and translation to fit center
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        val bmpWidth = bmp.width.toFloat()
        val bmpHeight = bmp.height.toFloat()

        if (bmpWidth == 0f || bmpHeight == 0f) return

        viewScale = kotlin.math.min(viewWidth / bmpWidth, viewHeight / bmpHeight)
        viewDx = (viewWidth - bmpWidth * viewScale) / 2f
        viewDy = (viewHeight - bmpHeight * viewScale) / 2f

        canvas.save()
        canvas.translate(viewDx, viewDy)
        canvas.scale(viewScale, viewScale)

        canvas.drawBitmapMesh(bmp, meshWidth, meshHeight, verts, 0, null, 0, null)

        faceMesh?.let { mesh ->
            // Draw triangles
            for (triangle in mesh.allTriangles) {
                val p1 = triangle.allPoints[0].position
                val p2 = triangle.allPoints[1].position
                val p3 = triangle.allPoints[2].position

                val w1 = getWarpedPoint(p1.x, p1.y)
                val w2 = getWarpedPoint(p2.x, p2.y)
                val w3 = getWarpedPoint(p3.x, p3.y)

                canvas.drawLine(w1.first, w1.second, w2.first, w2.second, meshPaint)
                canvas.drawLine(w2.first, w2.second, w3.first, w3.second, meshPaint)
                canvas.drawLine(w3.first, w3.second, w1.first, w1.second, meshPaint)
            }

            // Draw points
            for (point in mesh.allPoints) {
                val pos = point.position
                val warped = getWarpedPoint(pos.x, pos.y)
                canvas.drawPoint(warped.first, warped.second, pointPaint)
            }
        }
        
        canvas.restore()
    }
}