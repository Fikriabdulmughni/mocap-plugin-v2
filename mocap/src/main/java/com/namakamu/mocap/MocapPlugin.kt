package com.namakamu.mocap

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import org.godotengine.godot.Godot
import org.godotengine.godot.plugin.GodotPlugin
import org.godotengine.godot.plugin.SignalInfo
import org.godotengine.godot.plugin.UsedByGodot
import java.util.concurrent.Executors

class MocapPlugin(godot: Godot) : GodotPlugin(godot) {

    companion object {
        private const val TAG = "MocapPlugin"
    }

    private var landmarker: PoseLandmarker? = null
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private val analyzerThread = Executors.newSingleThreadExecutor()

    override fun getPluginName(): String = "MocapPlugin"

    override fun getPluginSignals(): Set<SignalInfo> =
        setOf(SignalInfo("pose_frame", String::class.java))

    @UsedByGodot
    fun initPose() {
        val act = activity ?: return
        if (landmarker != null) return

        Thread {
            try {
                val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(
                        BaseOptions.builder()
                            .setModelAssetPath("pose_landmarker_lite.task")
                            .build()
                    )
                    .setRunningMode(RunningMode.VIDEO)
                    .setNumPoses(1)
                    .build()

                landmarker = PoseLandmarker.createFromOptions(act, options)
                Log.i(TAG, "PoseLandmarker siap")
            } catch (e: Exception) {
                Log.e(TAG, "Gagal membuat PoseLandmarker", e)
            }
        }.start()
    }

    @UsedByGodot
    fun startRecording() {
        val act = activity ?: return
        if (landmarker == null) initPose()

        val future = ProcessCameraProvider.getInstance(act)
        future.addListener({
            try {
                provider = future.get()

                analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysis!!.setAnalyzer(analyzerThread) { proxy ->
                    try {
                        val lm = landmarker ?: return@setAnalyzer
                        val ts = System.currentTimeMillis()

                        var bitmap = proxy.toBitmap()
                        val rot = proxy.imageInfo.rotationDegrees
                        if (rot != 0) {
                            val matrix = Matrix().apply { postRotate(rot.toFloat()) }
                            bitmap = Bitmap.createBitmap(
                                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                            )
                        }

                        val mpImage = BitmapImageBuilder(bitmap).build()
                        val result = lm.detectForVideo(mpImage, ts)
                        val pose = result.landmarks().firstOrNull()

                        if (pose != null) {
                            val sb = StringBuilder("{\"t\":$ts,\"p\":[")
                            pose.forEachIndexed { i, p ->
                                if (i > 0) sb.append(',')
                                sb.append("[${p.x()},${p.y()},${p.z()},${p.visibility().orElse(0f)}]")
                            }
                            sb.append("]}")
                            val json = sb.toString()
                            act.runOnUiThread { emitSignal("pose_frame", json) }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error memproses frame", e)
                    } finally {
                        proxy.close()
                    }
                }

                provider!!.unbindAll()
                provider!!.bindToLifecycle(
                    act as LifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    analysis!!
                )
                Log.i(TAG, "Kamera terbuka")
            } catch (e: Exception) {
                Log.e(TAG, "Gagal membuka kamera", e)
            }
        }, ContextCompat.getMainExecutor(act))
    }

    @UsedByGodot
    fun stopRecording() {
        val act = activity ?: return
        act.runOnUiThread {
            provider?.unbindAll()
            Log.i(TAG, "Kamera dihentikan")
        }
    }
}
