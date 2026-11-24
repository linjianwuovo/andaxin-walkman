package com.ljyh.mei.ui.component.player.component.mesh

import kotlin.math.cos
import kotlin.math.sin

private class ControlPoint(
    var x: Float,
    var y: Float,
    var ur: Float = 0f,
    var vr: Float = 0f,
    var up: Float = 1f,
    var vp: Float = 1f
) {
    val uTangent: FloatArray
        get() = floatArrayOf(
            up * cos(ur),
            up * sin(ur)
        )

    val vTangent: FloatArray
        get() = floatArrayOf(
            -vp * sin(vr),
            vp * cos(vr)
        )
}

private val H = floatArrayOf(
    2f, -2f, 1f, 1f,
    -3f, 3f, -2f, -1f,
    0f, 0f, 1f, 0f,
    1f, 0f, 0f, 0f
)

private val H_T = FloatArray(16).also {
    for (i in 0 until 4) {
        for (j in 0 until 4) {
            it[i * 4 + j] = H[j * 4 + i]
        }
    }
}

private fun mat4Mul(out: FloatArray, a: FloatArray, b: FloatArray) {
    for (i in 0 until 4) {
        for (j in 0 until 4) {
            var sum = 0f
            for (k in 0 until 4) {
                sum += a[i + k * 4] * b[k + j * 4]
            }
            out[i + j * 4] = sum
        }
    }
}

private fun mat4Transpose(out: FloatArray, m: FloatArray) {
    for (i in 0 until 4) {
        for (j in 0 until 4) {
            out[i * 4 + j] = m[j * 4 + i]
        }
    }
}

internal class BHPMesh(preset: ControlPointPreset, subdivision: Int) {
    private val controlPointWidth = preset.width
    private val controlPointHeight = preset.height
    private val subdivisionLevel = subdivision.coerceIn(4, 50)
    private val gridWidth = (controlPointWidth - 1) * subdivisionLevel
    private val gridHeight = (controlPointHeight - 1) * subdivisionLevel
    private val controlPoints = Array(controlPointHeight) { row ->
        Array(controlPointWidth) { col ->
            val baseX = if (controlPointWidth == 1) 0f else col.toFloat() / (controlPointWidth - 1) * 2f - 1f
            val baseY = if (controlPointHeight == 1) 0f else row.toFloat() / (controlPointHeight - 1) * 2f - 1f
            ControlPoint(baseX, baseY)
        }
    }

    val positions: FloatArray
    val textureCoordinates: FloatArray
    val indices: ShortArray

    init {
        val vertexCount = gridWidth * gridHeight
        require(vertexCount > 0) { "Grid size must be positive" }
        require(vertexCount <= 65536) { "Mesh exceeds Canvas index range" }
        positions = FloatArray(vertexCount * 2)
        textureCoordinates = FloatArray(vertexCount * 2)
        indices = ShortArray((gridWidth - 1) * (gridHeight - 1) * 6)
        configureFromPreset(preset)
        updateMesh()
    }

    private fun getControlPoint(i: Int, j: Int): ControlPoint =
        controlPoints[j.coerceIn(0, controlPointHeight - 1)][i.coerceIn(0, controlPointWidth - 1)]

    private fun configureFromPreset(preset: ControlPointPreset) {
        val uPower = 2f / (preset.width - 1)
        val vPower = 2f / (preset.height - 1)
        for (cpConf in preset.conf) {
            val cp = getControlPoint(cpConf.cx, cpConf.cy)
            cp.x = cpConf.x
            cp.y = cpConf.y
            cp.ur = cpConf.ur
            cp.vr = cpConf.vr
            cp.up = uPower * cpConf.up
            cp.vp = vPower * cpConf.vp
        }
    }

    private fun fillMeshCoefficients(
        p00: ControlPoint, p01: ControlPoint,
        p10: ControlPoint, p11: ControlPoint,
        axIdx: Int,
        out: FloatArray
    ) {
        out[0] = if (axIdx == 0) p00.x else p00.y
        out[1] = if (axIdx == 0) p01.x else p01.y
        out[4] = if (axIdx == 0) p10.x else p10.y
        out[5] = if (axIdx == 0) p11.x else p11.y
        val v00 = p00.vTangent; val v10 = p10.vTangent
        val v01 = p01.vTangent; val v11 = p11.vTangent
        val u00 = p00.uTangent; val u10 = p10.uTangent
        val u01 = p01.uTangent; val u11 = p11.uTangent
        out[2] = v00[axIdx]; out[3] = v01[axIdx]
        out[6] = v10[axIdx]; out[7] = v11[axIdx]
        out[8] = u00[axIdx]; out[9] = u01[axIdx]
        out[12] = u10[axIdx]; out[13] = u11[axIdx]
        out[10] = 0f; out[11] = 0f; out[14] = 0f; out[15] = 0f
    }

    private fun precomputeMatrix(m: FloatArray, out: FloatArray) {
        val mT = FloatArray(16)
        mat4Transpose(mT, m)
        val temp = FloatArray(16)
        mat4Mul(temp, mT, H)
        mat4Mul(out, H_T, temp)
    }

    private fun updateMesh() {
        val n = subdivisionLevel
        val cpW = controlPointWidth
        val cpH = controlPointHeight
        val subDivM1 = (n - 1).coerceAtLeast(1)
        val invTH = 1f / (subDivM1 * (cpW - 1))
        val invTW = 1f / (subDivM1 * (cpH - 1))

        val tempM = FloatArray(16)
        val accX = FloatArray(16)
        val accY = FloatArray(16)
        val uPow = FloatArray(4)
        val vPow = FloatArray(4)
        val ux = FloatArray(4)
        val uy = FloatArray(4)

        for (patchY in 0 until (cpH - 1)) {
            for (patchX in 0 until (cpW - 1)) {
                val p00 = getControlPoint(patchX, patchY)
                val p10 = getControlPoint(patchX + 1, patchY)
                val p01 = getControlPoint(patchX, patchY + 1)
                val p11 = getControlPoint(patchX + 1, patchY + 1)

                fillMeshCoefficients(p00, p01, p10, p11, 0, tempM)
                precomputeMatrix(tempM, accX)
                fillMeshCoefficients(p00, p01, p10, p11, 1, tempM)
                precomputeMatrix(tempM, accY)

                val sX = patchX.toFloat() / (cpW - 1)
                val sY = patchY.toFloat() / (cpH - 1)

                for (u in 0 until n) {
                    val uNorm = if (n > 1) u.toFloat() / subDivM1 else 0f
                    cubicPowers(uNorm, uPow)

                    transformVec4Mat4(ux, uPow, accX)
                    transformVec4Mat4(uy, uPow, accY)

                    for (v in 0 until n) {
                        val vNorm = if (n > 1) v.toFloat() / subDivM1 else 0f
                        cubicPowers(vNorm, vPow)

                        val px = dot4(vPow, ux)
                        val py = dot4(vPow, uy)

                        val uvU = sX + v * invTH
                        val uvV = 1f - sY - u * invTW

                        val row = patchY * n + u
                        val col = patchX * n + v
                        val idx = (row * gridWidth + col) * 2

                        positions[idx] = px
                        positions[idx + 1] = py
                        textureCoordinates[idx] = uvU.coerceIn(0f, 1f)
                        textureCoordinates[idx + 1] = uvV.coerceIn(0f, 1f)
                    }
                }
            }
        }

        var ii = 0
        for (row in 0 until (gridHeight - 1)) {
            for (col in 0 until (gridWidth - 1)) {
                val tl = row * gridWidth + col
                val tr = tl + 1
                val bl = (row + 1) * gridWidth + col
                val br = bl + 1
                indices[ii++] = tl.toShort()
                indices[ii++] = tr.toShort()
                indices[ii++] = bl.toShort()
                indices[ii++] = tr.toShort()
                indices[ii++] = br.toShort()
                indices[ii++] = bl.toShort()
            }
        }
    }

    private fun cubicPowers(value: Float, out: FloatArray) {
        val square = value * value
        out[0] = square * value
        out[1] = square
        out[2] = value
        out[3] = 1f
    }

    private fun transformVec4Mat4(out: FloatArray, a: FloatArray, m: FloatArray) {
        for (i in 0 until 4) {
            out[i] = a[0] * m[i] + a[1] * m[4 + i] + a[2] * m[8 + i] + a[3] * m[12 + i]
        }
    }

    private fun dot4(a: FloatArray, b: FloatArray): Float {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]
    }
}
