package com.lingoffline.automation.v26

import android.graphics.Bitmap
import kotlin.math.*

data class SwordPoint(
    val x: Float,
    val y: Float,
    val score: Float
)

data class DetectionDebug(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val workWidth: Int,
    val workHeight: Int,
    val purplePixels: Int,
    val componentCount: Int,
    val candidateCount: Int,
    val candidates: List<SwordPoint>,
    val selected: List<SwordPoint>,
    val formation: Float
)

private data class Component(
    val minX: Int,
    val minY: Int,
    val maxX: Int,
    val maxY: Int,
    val area: Int,
    val centerX: Float,
    val centerY: Float,
    val score: Float
)

object SwordDetector {

    private const val WORK_WIDTH = 384

    /*
     * V2.2:
     * - ROI dibuat lebih lebar untuk debugging.
     * - threshold ungu sedikit lebih toleran terhadap glow.
     * - semua kandidat dilaporkan.
     * - bila kandidat > 4, kombinasi 4 terbaik dipilih berdasarkan
     *   bentuk formasi + confidence komponen.
     */
    fun analyze(bitmap: Bitmap): DetectionDebug {
        val srcW = bitmap.width
        val srcH = bitmap.height

        if (srcW <= 0 || srcH <= 0) {
            return DetectionDebug(
                srcW, srcH, 0, 0,
                0, 0, 0,
                emptyList(), emptyList(), 0f
            )
        }

        val scale = min(1f, WORK_WIDTH.toFloat() / srcW.toFloat())
        val workW = max(1, (srcW * scale).roundToInt())
        val workH = max(1, (srcH * scale).roundToInt())

        val work = if (workW == srcW && workH == srcH) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, workW, workH, true)
        }

        val pixels = IntArray(workW * workH)
        work.getPixels(pixels, 0, workW, 0, 0, workW, workH)

        if (work !== bitmap) work.recycle()

        val mask = BooleanArray(workW * workH)

        val xStart = (workW * 0.08f).roundToInt()
        val xEnd = (workW * 0.92f).roundToInt()
        val yStart = (workH * 0.05f).roundToInt()
        val yEnd = (workH * 0.95f).roundToInt()

        var purplePixels = 0

        for (y in yStart until yEnd) {
            val row = y * workW

            for (x in xStart until xEnd) {
                val color = pixels[row + x]

                val r = ((color shr 16) and 0xFF) / 255f
                val g = ((color shr 8) and 0xFF) / 255f
                val b = (color and 0xFF) / 255f

                val hsv = rgbToHsv(r, g, b)

                if (
                    hsv[0] in 235f..355f &&
                    hsv[1] >= 0.22f &&
                    hsv[2] >= 0.38f
                ) {
                    mask[row + x] = true
                    purplePixels++
                }
            }
        }

        val allComponents = connectedComponents(mask, workW, workH)

        val filtered = allComponents
            .filter { c ->
                val width = c.maxX - c.minX + 1
                val height = c.maxY - c.minY + 1
                val ratio = height.toFloat() / max(width, 1).toFloat()

                c.area > 12 &&
                height > 10 &&
                ratio > 1.15f &&
                width < 42 &&
                height < (workH * 0.62f)
            }
            .sortedByDescending { it.score }

        val invScaleX = srcW.toFloat() / workW.toFloat()
        val invScaleY = srcH.toFloat() / workH.toFloat()

        val candidates = filtered
            .take(12)
            .map {
                SwordPoint(
                    x = it.centerX * invScaleX,
                    y = it.centerY * invScaleY,
                    score = it.score
                )
            }

        val selected = selectBestFour(candidates)
        val formation = formationScore(selected)

        return DetectionDebug(
            sourceWidth = srcW,
            sourceHeight = srcH,
            workWidth = workW,
            workHeight = workH,
            purplePixels = purplePixels,
            componentCount = allComponents.size,
            candidateCount = candidates.size,
            candidates = candidates,
            selected = selected,
            formation = formation
        )
    }

    fun detect(bitmap: Bitmap): List<SwordPoint> =
        analyze(bitmap).selected

    private fun selectBestFour(candidates: List<SwordPoint>): List<SwordPoint> {
        if (candidates.size < 4) return emptyList()
        if (candidates.size == 4) return candidates

        val pool = candidates.take(8)
        val maxScore = pool.maxOfOrNull { it.score }?.coerceAtLeast(1f) ?: 1f

        var best: List<SwordPoint> = emptyList()
        var bestScore = -1f

        for (a in 0 until pool.size - 3) {
            for (b in a + 1 until pool.size - 2) {
                for (c in b + 1 until pool.size - 1) {
                    for (d in c + 1 until pool.size) {
                        val combo = listOf(pool[a], pool[b], pool[c], pool[d])
                        val formation = formationScore(combo)

                        val confidence = combo
                            .map { (it.score / maxScore).coerceIn(0f, 1f) }
                            .average()
                            .toFloat()

                        val combined = formation * 0.82f + confidence * 0.18f

                        if (combined > bestScore) {
                            bestScore = combined
                            best = combo
                        }
                    }
                }
            }
        }

        return best
    }

    fun formationScore(points: List<SwordPoint>): Float {
        if (points.size != 4) return 0f

        val cx = points.map { it.x }.average().toFloat()
        val cy = points.map { it.y }.average().toFloat()

        val radii = points.map {
            hypot(it.x - cx, it.y - cy)
        }

        val avgRadius = radii.average().toFloat()
        if (avgRadius <= 1f) return 0f

        val variance = radii.map {
            (it - avgRadius).pow(2)
        }.average().toFloat()

        val radialScore =
            (1f - sqrt(variance) / avgRadius)
                .coerceIn(0f, 1f)

        val angles = points.map {
            atan2(it.y - cy, it.x - cx)
        }.sorted()

        val gaps = MutableList(4) { 0f }

        for (i in 0 until 4) {
            val current = angles[i]
            var next = angles[(i + 1) % 4]

            if (i == 3) {
                next += 2f * Math.PI.toFloat()
            }

            gaps[i] = next - current
        }

        val ideal = Math.PI.toFloat() / 2f

        val angleError = gaps.map {
            abs(it - ideal)
        }.average().toFloat()

        val angularScore =
            (1f - angleError / ideal)
                .coerceIn(0f, 1f)

        return radialScore * 0.55f + angularScore * 0.45f
    }

    fun optimalRoute(points: List<SwordPoint>): List<SwordPoint> {
        if (points.size != 4) return emptyList()

        val cx = points.map { it.x }.average().toFloat()
        val cy = points.map { it.y }.average().toFloat()

        return permutations(points).minByOrNull { order ->
            var px = cx
            var py = cy
            var total = 0f

            for (p in order) {
                total += hypot(p.x - px, p.y - py)
                px = p.x
                py = p.y
            }

            total
        } ?: emptyList()
    }

    fun areStable(
        previous: List<SwordPoint>,
        current: List<SwordPoint>,
        screenWidth: Int,
        screenHeight: Int
    ): Boolean {
        if (previous.size != 4 || current.size != 4) return false

        val a = sortByAngle(previous)
        val b = sortByAngle(current)

        val threshold = max(screenWidth, screenHeight) * 0.045f

        for (i in 0 until 4) {
            val movement = hypot(
                a[i].x - b[i].x,
                a[i].y - b[i].y
            )

            if (movement > threshold) return false
        }

        return true
    }

    private fun sortByAngle(points: List<SwordPoint>): List<SwordPoint> {
        val cx = points.map { it.x }.average().toFloat()
        val cy = points.map { it.y }.average().toFloat()

        return points.sortedBy {
            atan2(it.y - cy, it.x - cx)
        }
    }

    private fun connectedComponents(
        mask: BooleanArray,
        width: Int,
        height: Int
    ): List<Component> {
        val seen = BooleanArray(mask.size)
        val queue = IntArray(mask.size)
        val output = mutableListOf<Component>()

        val dx = intArrayOf(-1, 1, 0, 0, -1, 1, -1, 1)
        val dy = intArrayOf(0, 0, -1, 1, -1, -1, 1, 1)

        for (startY in 1 until height - 1) {
            for (startX in 1 until width - 1) {
                val start = startY * width + startX

                if (!mask[start] || seen[start]) continue

                var head = 0
                var tail = 0

                queue[tail++] = start
                seen[start] = true

                var minX = startX
                var maxX = startX
                var minY = startY
                var maxY = startY
                var area = 0
                var sumX = 0L
                var sumY = 0L

                while (head < tail) {
                    val index = queue[head++]
                    val x = index % width
                    val y = index / width

                    minX = min(minX, x)
                    maxX = max(maxX, x)
                    minY = min(minY, y)
                    maxY = max(maxY, y)

                    area++
                    sumX += x
                    sumY += y

                    for (i in 0 until 8) {
                        val nx = x + dx[i]
                        val ny = y + dy[i]

                        if (
                            nx <= 0 ||
                            ny <= 0 ||
                            nx >= width - 1 ||
                            ny >= height - 1
                        ) continue

                        val ni = ny * width + nx

                        if (!seen[ni] && mask[ni]) {
                            seen[ni] = true
                            queue[tail++] = ni
                        }
                    }
                }

                val componentWidth = maxX - minX + 1
                val componentHeight = maxY - minY + 1

                val ratio =
                    componentHeight.toFloat() /
                    max(componentWidth, 1).toFloat()

                val score =
                    area.toFloat() * min(ratio, 5f)

                output += Component(
                    minX = minX,
                    minY = minY,
                    maxX = maxX,
                    maxY = maxY,
                    area = area,
                    centerX = sumX.toFloat() / area.toFloat(),
                    centerY = sumY.toFloat() / area.toFloat(),
                    score = score
                )
            }
        }

        return output
    }

    private fun rgbToHsv(
        r: Float,
        g: Float,
        b: Float
    ): FloatArray {
        val maxValue = max(r, max(g, b))
        val minValue = min(r, min(g, b))
        val delta = maxValue - minValue

        var hue = 0f

        if (delta != 0f) {
            hue = when (maxValue) {
                r -> 60f * (((g - b) / delta) % 6f)
                g -> 60f * (((b - r) / delta) + 2f)
                else -> 60f * (((r - g) / delta) + 4f)
            }

            if (hue < 0f) hue += 360f
        }

        val saturation =
            if (maxValue == 0f) 0f else delta / maxValue

        return floatArrayOf(hue, saturation, maxValue)
    }

    private fun <T> permutations(items: List<T>): List<List<T>> {
        if (items.size <= 1) return listOf(items)

        val output = mutableListOf<List<T>>()

        for (i in items.indices) {
            val head = items[i]
            val rest = items.filterIndexed { index, _ -> index != i }

            for (tail in permutations(rest)) {
                output += listOf(head) + tail
            }
        }

        return output
    }
}
