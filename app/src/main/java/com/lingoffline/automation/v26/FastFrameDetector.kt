package com.lingoffline.automation.v26

import java.nio.ByteBuffer
import kotlin.math.*

data class HeroEstimate(
    val x: Float,
    val y: Float,
    val confidence: Float,
    val fromHealthBar: Boolean
)

data class FastDetection(
    val width: Int,
    val height: Int,
    val sampleStep: Int,
    val purpleSamples: Int,
    val purpleComponents: Int,
    val candidates: List<SwordPoint>,
    val hero: HeroEstimate
)

private data class FastComponent(
    val minX: Int,
    val minY: Int,
    val maxX: Int,
    val maxY: Int,
    val area: Int,
    val sumX: Long,
    val sumY: Long
)

object FastFrameDetector {

    private const val TARGET_WORK_WIDTH = 460

    fun analyze(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int
    ): FastDetection {
        if (width <= 0 || height <= 0 || pixelStride < 3) {
            return FastDetection(
                width, height, 1, 0, 0,
                emptyList(),
                fallbackHero(width, height)
            )
        }

        val step = max(
            3,
            ceil(width.toFloat() / TARGET_WORK_WIDTH.toFloat()).toInt()
        )

        val workW = max(1, width / step)
        val workH = max(1, height / step)

        val purple = BooleanArray(workW * workH)
        val green = BooleanArray(workW * workH)

        var purpleCount = 0

        val xStart = (workW * 0.055f).toInt()
        val xEnd = (workW * 0.945f).toInt()
        val yStart = (workH * 0.055f).toInt()
        val yEnd = (workH * 0.955f).toInt()

        for (wy in yStart until yEnd) {
            val sy = min(height - 1, wy * step)
            val row = sy * rowStride

            for (wx in xStart until xEnd) {
                val sx = min(width - 1, wx * step)
                val off = row + sx * pixelStride

                if (off < 0 || off + 2 >= buffer.limit()) continue

                val r = buffer.get(off).toInt() and 0xFF
                val g = buffer.get(off + 1).toInt() and 0xFF
                val b = buffer.get(off + 2).toInt() and 0xFF

                val idx = wy * workW + wx

                if (isPurple(r, g, b)) {
                    purple[idx] = true
                    purpleCount++
                }

                if (isHealthGreen(r, g, b)) {
                    green[idx] = true
                }
            }
        }

        val purpleComponents = connectedComponents(purple, workW, workH)

        val candidates = purpleComponents
            .mapNotNull {
                componentToSword(
                    it,
                    step,
                    width,
                    height
                )
            }
            .sortedByDescending { it.score }
            .take(14)

        val hero = detectHeroFromHealthBar(
            green,
            workW,
            workH,
            step,
            width,
            height
        )

        return FastDetection(
            width = width,
            height = height,
            sampleStep = step,
            purpleSamples = purpleCount,
            purpleComponents = purpleComponents.size,
            candidates = candidates,
            hero = hero
        )
    }

    private fun isPurple(r: Int, g: Int, b: Int): Boolean {
        val maxV = max(r, max(g, b))
        val minV = min(r, min(g, b))

        if (maxV < 90) return false
        if (maxV - minV < 35) return false
        if (b < g + 25) return false
        if (r < g + 18) return false
        if (b > r + 150) return false
        if (r > b + 150) return false

        return true
    }

    private fun isHealthGreen(r: Int, g: Int, b: Int): Boolean {
        if (g < 125) return false
        if (g < r + 35) return false
        if (g < b + 35) return false
        return true
    }

    private fun componentToSword(
        c: FastComponent,
        step: Int,
        srcW: Int,
        srcH: Int
    ): SwordPoint? {
        val w = c.maxX - c.minX + 1
        val h = c.maxY - c.minY + 1

        if (c.area < 3) return null
        if (h < 3) return null

        val ratio = h.toFloat() / max(1, w).toFloat()
        if (ratio < 0.70f) return null

        val centerX = c.sumX.toFloat() / c.area.toFloat()
        val centerY = c.sumY.toFloat() / c.area.toFloat()

        val x = (centerX * step).coerceIn(0f, srcW.toFloat())
        val y = (centerY * step).coerceIn(0f, srcH.toFloat())

        val score = c.area.toFloat() * (1f + min(ratio, 4f))

        return SwordPoint(x, y, score)
    }

    private fun detectHeroFromHealthBar(
        mask: BooleanArray,
        width: Int,
        height: Int,
        step: Int,
        srcW: Int,
        srcH: Int
    ): HeroEstimate {
        val comps = connectedComponents(mask, width, height)

        var best: FastComponent? = null
        var bestScore = -1f

        for (c in comps) {
            val cw = c.maxX - c.minX + 1
            val ch = c.maxY - c.minY + 1

            if (cw < 12) continue
            if (ch > 8) continue

            val ratio = cw.toFloat() / max(1, ch).toFloat()
            if (ratio < 3.5f) continue

            val cx = c.sumX.toFloat() / c.area.toFloat()
            val cy = c.sumY.toFloat() / c.area.toFloat()

            val nx = cx / width.toFloat()
            val ny = cy / height.toFloat()

            if (nx !in 0.25f..0.75f) continue
            if (ny !in 0.15f..0.72f) continue

            val score = cw.toFloat() * min(ratio, 16f)

            if (score > bestScore) {
                bestScore = score
                best = c
            }
        }

        val chosen = best ?: return fallbackHero(srcW, srcH)

        val barX = (
            chosen.sumX.toFloat() / chosen.area.toFloat()
        ) * step

        val barY = (
            chosen.sumY.toFloat() / chosen.area.toFloat()
        ) * step

        val heroY = barY + srcH * 0.105f

        return HeroEstimate(
            x = barX.coerceIn(0f, srcW.toFloat()),
            y = heroY.coerceIn(0f, srcH.toFloat()),
            confidence = min(1f, bestScore / 160f),
            fromHealthBar = true
        )
    }

    private fun fallbackHero(width: Int, height: Int): HeroEstimate {
        return HeroEstimate(
            x = width * 0.50f,
            y = height * 0.515f,
            confidence = 0.15f,
            fromHealthBar = false
        )
    }

    private fun connectedComponents(
        mask: BooleanArray,
        width: Int,
        height: Int
    ): List<FastComponent> {
        if (width <= 2 || height <= 2) return emptyList()

        val seen = BooleanArray(mask.size)
        val queue = IntArray(mask.size)
        val result = mutableListOf<FastComponent>()

        val dx = intArrayOf(-1, 1, 0, 0, -1, 1, -1, 1)
        val dy = intArrayOf(0, 0, -1, 1, -1, -1, 1, 1)

        for (y in 1 until height - 1) {
            val row = y * width

            for (x in 1 until width - 1) {
                val start = row + x

                if (!mask[start] || seen[start]) continue

                var head = 0
                var tail = 0

                queue[tail++] = start
                seen[start] = true

                var minX = x
                var maxX = x
                var minY = y
                var maxY = y
                var area = 0
                var sumX = 0L
                var sumY = 0L

                while (head < tail) {
                    val idx = queue[head++]
                    val px = idx % width
                    val py = idx / width

                    area++
                    sumX += px
                    sumY += py

                    minX = min(minX, px)
                    maxX = max(maxX, px)
                    minY = min(minY, py)
                    maxY = max(maxY, py)

                    for (i in 0 until 8) {
                        val nx = px + dx[i]
                        val ny = py + dy[i]

                        if (
                            nx <= 0 ||
                            ny <= 0 ||
                            nx >= width - 1 ||
                            ny >= height - 1
                        ) continue

                        val ni = ny * width + nx

                        if (mask[ni] && !seen[ni]) {
                            seen[ni] = true
                            queue[tail++] = ni
                        }
                    }
                }

                result += FastComponent(
                    minX,
                    minY,
                    maxX,
                    maxY,
                    area,
                    sumX,
                    sumY
                )
            }
        }

        return result
    }
}
