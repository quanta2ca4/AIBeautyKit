# 👀 Eye Enlarge Algorithm (Beauty Face)

Thuật toán **làm to mắt (Eye Enlarge)** dùng trong các ứng dụng beauty (Snow, B612, Ulike, TikTok…) dựa trên **Face Mesh landmark** từ **ML Kit**.

> ML Kit **không làm đẹp sẵn** – nó chỉ cung cấp landmark. Phần làm to mắt là **warp hình học cục bộ (local deformation)** do bạn tự implement.

---

## 🎯 Mục tiêu
- Mắt to **tự nhiên**
- Không méo mặt
- Điều chỉnh được bằng slider
- Chạy realtime với CameraX

---

## 1️⃣ Dữ liệu đầu vào (ML Kit)
Từ `FaceMesh`:

```kotlin
val leftEyePoints = face.getPoints(FaceMeshPoint.LEFT_EYE)
val rightEyePoints = face.getPoints(FaceMeshPoint.RIGHT_EYE)
```

Mỗi mắt gồm ~16–20 landmark (`PointF3D`).

---

## 2️⃣ Tư duy thuật toán
❌ Không scale toàn ảnh
❌ Không scale hình chữ nhật vùng mắt

✅ **Scale cục bộ theo tâm mắt + giảm dần ra ngoài (falloff)**

➡️ Kỹ thuật: **Radial Warp / Local Deformation**

---

## 3️⃣ Tính toán hình học

### 3.1. Tâm mắt (Eye Center)

```kotlin
fun computeCenter(points: List<PointF3D>): PointF {
    val x = points.sumOf { it.x.toDouble() } / points.size
    val y = points.sumOf { it.y.toDouble() } / points.size
    return PointF(x.toFloat(), y.toFloat())
}
```

---

### 3.2. Bán kính ảnh hưởng (Radius)

```kotlin
fun computeRadius(center: PointF, points: List<PointF3D>): Float {
    return points.maxOf {
        hypot(it.x - center.x, it.y - center.y)
    }
}
```

📌 Thực tế nên dùng:
```
radius = radius * 1.3 ~ 1.6
```

---

## 4️⃣ Công thức warp (cốt lõi)
Với mỗi pixel **P(x, y)** trong vùng mắt:

### 4.1. Khoảng cách tới tâm mắt
```
d = distance(P, center)
```

### 4.2. Chuẩn hoá khoảng cách
```
t = d / radius   (0 → 1)
```

### 4.3. Falloff (giảm dần ảnh hưởng)
```
weight = 1 - t²
```

### 4.4. Scale factor
```
scale = 1 + strength × weight
```

- `strength`: 0.0 → 1.0

---

## 5️⃣ Công thức ánh xạ pixel (inverse mapping)

```text
newX = center.x + (x - center.x) / scale
newY = center.y + (y - center.y) / scale
```

➡️ Pixel càng gần tâm → scale càng mạnh → mắt to tự nhiên.

---

## 6️⃣ Pseudo-code tổng quát

```text
for each eye:
    center = computeCenter(eyePoints)
    radius = computeRadius(center, eyePoints) * 1.4

    for each pixel in eye bounding box:
        d = distance(pixel, center)
        if d < radius:
            t = d / radius
            weight = 1 - t²
            scale = 1 + strength × weight

            srcPixel = inverseScale(pixel, center, scale)
            outputPixel = bitmap[srcPixel]
```

---

## 7️⃣ Kotlin core implementation

```kotlin
fun eyeEnlarge(
    bitmap: Bitmap,
    center: PointF,
    radius: Float,
    strength: Float // 0.0 ~ 1.0
): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    val output = bitmap.copy(Bitmap.Config.ARGB_8888, true)

    for (y in max(0, (center.y - radius).toInt()) until min(h, (center.y + radius).toInt())) {
        for (x in max(0, (center.x - radius).toInt()) until min(w, (center.x + radius).toInt())) {

            val dx = x - center.x
            val dy = y - center.y
            val dist = hypot(dx, dy)

            if (dist < radius) {
                val t = dist / radius
                val weight = 1 - t * t
                val scale = 1 + strength * weight

                val srcX = (center.x + dx / scale).toInt().coerceIn(0, w - 1)
                val srcY = (center.y + dy / scale).toInt().coerceIn(0, h - 1)

                output.setPixel(x, y, bitmap.getPixel(srcX, srcY))
            }
        }
    }
    return output
}
```

---

## 8️⃣ Áp dụng cho hai mắt

```kotlin
bitmap = eyeEnlarge(bitmap, leftEyeCenter, leftRadius, strength)
bitmap = eyeEnlarge(bitmap, rightEyeCenter, rightRadius, strength)
```

---

## 9️⃣ Giá trị strength khuyến nghị
| Strength | Hiệu ứng |
|--------|---------|
| 0.10 – 0.20 | Tự nhiên |
| 0.25 – 0.30 | Beauty rõ |
| > 0.35 | Dễ bị giả |

---

## 🔧 Nâng cấp nên có
- **Temporal smoothing** (giảm rung giữa frame)
- Giảm strength khi mắt nhắm
- Bilinear sampling (mượt hơn)
- Chạy trên `Dispatchers.Default`

---

## ✅ Kết luận
- ML Kit = cung cấp **Face Mesh landmark**
- Eye Enlarge = **toán học + warp cục bộ**
- Kotlin thuần làm được 100%
- Đây là nền tảng cho mọi beauty feature khác (gọt cằm, V-line…)

---

➡️ Feature tiếp theo nên làm: **Gọt cằm / V-line (Jaw Slimming)**

