package com.maggd.smartstudy

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.MotionEvent
import kotlin.math.abs
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class Study3DView(context: Context, private val scene: Int) : GLSurfaceView(context) {
    private val renderer3d = StudyRenderer(scene)
    private var previousX = 0f
    private var previousY = 0f
    private var pinchDistance = 0f

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer3d)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { previousX = event.x; previousY = event.y }
            MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount >= 2) {
                pinchDistance = distance(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    val d = distance(event)
                    if (pinchDistance > 0f && d > 0f) renderer3d.zoomBy((pinchDistance - d) * 0.006f)
                    pinchDistance = d
                } else {
                    renderer3d.rotateBy((event.x - previousX) * 0.7f, (event.y - previousY) * 0.7f)
                }
                previousX = event.x; previousY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pinchDistance = 0f
        }
        return true
    }

    private fun distance(e: MotionEvent): Float {
        if (e.pointerCount < 2) return 0f
        val dx = e.getX(0) - e.getX(1)
        val dy = e.getY(0) - e.getY(1)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}

private data class CubeSpec(val x: Float, val y: Float, val z: Float, val scale: Float, val color: FloatArray)

private class StudyRenderer(private val scene: Int) : GLSurfaceView.Renderer {
    private var program = 0
    private var positionHandle = 0
    private var colorHandle = 0
    private var mvpHandle = 0
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val mv = FloatArray(16)
    private val mvp = FloatArray(16)
    @Volatile private var rotX = 18f
    @Volatile private var rotY = -25f
    @Volatile private var zoom = 1f
    private lateinit var cubeBuffer: FloatBuffer
    private val vertexCount = 36

    fun rotateBy(dx: Float, dy: Float) { rotY += dx; rotX = (rotX + dy).coerceIn(-80f, 80f) }
    fun zoomBy(delta: Float) { zoom = (zoom + delta).coerceIn(0.55f, 2.4f) }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.035f, 0.07f, 0.13f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        val vertices = floatArrayOf(
            -1f,-1f, 1f,  1f,-1f, 1f,  1f, 1f, 1f,  -1f,-1f,1f, 1f,1f,1f, -1f,1f,1f,
            -1f,-1f,-1f, -1f,1f,-1f, 1f,1f,-1f, -1f,-1f,-1f, 1f,1f,-1f, 1f,-1f,-1f,
            -1f,1f,-1f, -1f,1f,1f, 1f,1f,1f, -1f,1f,-1f, 1f,1f,1f, 1f,1f,-1f,
            -1f,-1f,-1f, 1f,-1f,-1f, 1f,-1f,1f, -1f,-1f,-1f, 1f,-1f,1f, -1f,-1f,1f,
            1f,-1f,-1f, 1f,1f,-1f, 1f,1f,1f, 1f,-1f,-1f, 1f,1f,1f, 1f,-1f,1f,
            -1f,-1f,-1f, -1f,-1f,1f, -1f,1f,1f, -1f,-1f,-1f, -1f,1f,1f, -1f,1f,-1f
        )
        cubeBuffer = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        cubeBuffer.put(vertices).position(0)
        val vertexShader = """
            uniform mat4 uMVP;
            attribute vec4 aPosition;
            void main() { gl_Position = uMVP * aPosition; }
        """.trimIndent()
        val fragmentShader = """
            precision mediump float;
            uniform vec4 uColor;
            void main() { gl_FragColor = uColor; }
        """.trimIndent()
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, compile(GLES20.GL_VERTEX_SHADER, vertexShader))
            GLES20.glAttachShader(it, compile(GLES20.GL_FRAGMENT_SHADER, fragmentShader))
            GLES20.glLinkProgram(it)
        }
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        colorHandle = GLES20.glGetUniformLocation(program, "uColor")
        mvpHandle = GLES20.glGetUniformLocation(program, "uMVP")
        Matrix.setLookAtM(view, 0, 0f, 0f, 7.2f, 0f, 0f, 0f, 0f, 1f, 0f)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val ratio = width.toFloat() / height.coerceAtLeast(1)
        Matrix.frustumM(projection, 0, -ratio, ratio, -1f, 1f, 2f, 20f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(program)
        val specs = sceneCubes(scene)
        specs.forEachIndexed { index, c ->
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, c.x, c.y, c.z)
            Matrix.rotateM(model, 0, rotX + index * 9f, 1f, 0f, 0f)
            Matrix.rotateM(model, 0, rotY + index * 13f, 0f, 1f, 0f)
            Matrix.scaleM(model, 0, c.scale * zoom, c.scale * zoom, c.scale * zoom)
            Matrix.multiplyMM(mv, 0, view, 0, model, 0)
            Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
            GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvp, 0)
            GLES20.glUniform4fv(colorHandle, 1, c.color, 0)
            cubeBuffer.position(0)
            GLES20.glEnableVertexAttribArray(positionHandle)
            GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, cubeBuffer)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)
            GLES20.glDisableVertexAttribArray(positionHandle)
        }
    }

    private fun sceneCubes(s: Int): List<CubeSpec> {
        val blue = floatArrayOf(0.15f,0.55f,0.95f,1f)
        val cyan = floatArrayOf(0.1f,0.8f,0.8f,1f)
        val orange = floatArrayOf(1f,0.48f,0.18f,1f)
        val purple = floatArrayOf(0.62f,0.34f,0.95f,1f)
        val green = floatArrayOf(0.18f,0.78f,0.48f,1f)
        val white = floatArrayOf(0.72f,0.83f,0.95f,1f)
        return when (s) {
            0 -> listOf(CubeSpec(0f,0f,0f,0.85f,blue), CubeSpec(-1.45f,0.65f,0f,0.38f,orange), CubeSpec(1.4f,-0.65f,0f,0.42f,cyan))
            1 -> listOf(CubeSpec(0f,0f,0f,0.55f,blue), CubeSpec(-1.35f,0.75f,0f,0.34f,green), CubeSpec(1.35f,0.75f,0f,0.34f,orange), CubeSpec(-1.35f,-0.75f,0f,0.34f,purple), CubeSpec(1.35f,-0.75f,0f,0.34f,cyan))
            2 -> listOf(CubeSpec(-1.35f,0f,0f,0.38f,cyan), CubeSpec(0f,0f,0f,0.48f,orange), CubeSpec(1.35f,0f,0f,0.38f,green))
            3 -> listOf(CubeSpec(0f,0.3f,0f,0.8f,purple), CubeSpec(-1.25f,-0.65f,0f,0.42f,blue), CubeSpec(1.25f,-0.65f,0f,0.42f,cyan))
            4 -> listOf(CubeSpec(0f,0f,0f,0.7f,blue), CubeSpec(-1.35f,0f,0f,0.28f,orange), CubeSpec(1.35f,0f,0f,0.28f,green))
            else -> listOf(CubeSpec(-0.9f,0f,0f,0.55f,blue), CubeSpec(0.9f,0f,0f,0.55f,purple), CubeSpec(0f,0.95f,0f,0.32f,cyan), CubeSpec(0f,-0.95f,0f,0.32f,orange), CubeSpec(0f,0f,-0.8f,0.28f,white))
        }
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }
}
