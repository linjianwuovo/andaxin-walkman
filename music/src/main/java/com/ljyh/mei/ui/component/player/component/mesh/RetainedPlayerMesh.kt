package com.ljyh.mei.ui.component.player.component.mesh

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Mesh
import android.graphics.MeshSpecification
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.ui.unit.IntSize
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class MeshViewportTransform(size: IntSize) {
    private val width = size.width.toFloat()
    private val height = size.height.toFloat()
    private val aspect = width / height
    private val scaleX = if (aspect > 1f) 1f else 1f / aspect
    private val scaleY = if (aspect > 1f) aspect else 1f

    fun x(position: Float): Float = (position * scaleX + 1f) * width * .5f

    // OpenGL clip coordinates grow up; Canvas coordinates grow down.
    fun y(position: Float): Float = (1f - position * scaleY) * height * .5f
}

/** Geometry shared by HWUI display-list recordings at one pixel size. */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
internal class RetainedPlayerMesh private constructor(private val mesh: Mesh) {
    fun draw(canvas: Canvas, paint: Paint) {
        // DrawVertices uses this blender when a paint shader is supplied.
        canvas.drawMesh(mesh, BlendMode.MODULATE, paint)
    }

    companion object {
        private const val TAG = "RetainedPlayerMesh"
        private val specification by lazy {
            val float2 = MeshSpecification.TYPE_FLOAT2
            try {
                MeshSpecification.make(
                    arrayOf(
                        MeshSpecification.Attribute(float2, 0, "pos"),
                        MeshSpecification.Attribute(float2, 8, "tex"),
                    ),
                    16,
                    arrayOf(MeshSpecification.Varying(float2, "tex")),
                    """
                    Varyings main(const Attributes a) {
                        Varyings v;
                        v.position = a.pos;
                        v.tex = a.tex;
                        return v;
                    }
                    """.trimIndent(),
                    "float2 main(const Varyings v) { return v.tex; }",
                )
            } catch (error: IllegalArgumentException) {
                Log.w(TAG, "HWUI rejected the player mesh specification", error)
                null
            }
        }

        fun prepare(album: MeshAlbum, size: IntSize): RetainedPlayerMesh? {
            val spec = specification ?: return null
            val positions = album.positions
            val texture = album.textureCoordinates
            val indices = album.indices
            val viewport = MeshViewportTransform(size)
            val vertices = ByteBuffer.allocateDirect(positions.size * 8).order(ByteOrder.nativeOrder()).asFloatBuffer()
            val indexBuffer = ByteBuffer.allocateDirect(indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
            var left = Float.POSITIVE_INFINITY
            var top = Float.POSITIVE_INFINITY
            var right = Float.NEGATIVE_INFINITY
            var bottom = Float.NEGATIVE_INFINITY
            for (i in positions.indices step 2) {
                val x = viewport.x(positions[i])
                val y = viewport.y(positions[i + 1])
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x)
                bottom = maxOf(bottom, y)
                vertices.put(x).put(y).put(texture[i]).put(texture[i + 1])
            }
            if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) return null
            vertices.flip()
            indexBuffer.put(indices).flip()
            return try {
                RetainedPlayerMesh(
                    Mesh(spec, Mesh.TRIANGLES, vertices, positions.size / 2, indexBuffer, RectF(left, top, right, bottom)),
                )
            } catch (error: IllegalArgumentException) {
                Log.w(TAG, "HWUI rejected a player mesh", error)
                null
            }
        }
    }
}
