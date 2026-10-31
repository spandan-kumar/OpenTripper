package io.opentripper

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import io.opentripper.core.GuidedRun
import java.io.File

/** Camera permission and capture stay inside the app; no gallery/storage permission is needed. */
class PodCameraActivity : ComponentActivity() {
    private lateinit var preview: PreviewView
    private lateinit var review: ImageView
    private lateinit var message: TextView
    private lateinit var capture: Button
    private lateinit var accept: Button
    private lateinit var retake: Button
    private lateinit var allow: Button
    private var camera: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var capturing = false
    private var reviewing = false
    private var accepted = false
    private var requesting = false
    private var capturedAt: String? = null
    private val live by lazy { intent.getBooleanExtra("live", false) }
    private val check by lazy { intent.getBooleanExtra("check", false) }
    private val runId by lazy { intent.getStringExtra("run") ?: "" }
    private val stepId by lazy { intent.getStringExtra("step") ?: "" }
    private val photoName by lazy { "photo-$stepId.jpg" }
    private val photoFile: File by lazy {
        if (check) File(cacheDir, "camera-check.jpg") else if (live) FieldReportStore(this).photoFile(runId, photoName) else GuidedReportStore(this).photoFile(runId, photoName)
    }
    private val redraw: () -> Unit = { updateAvailability() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!check && !validStep()) { finish(); return }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 16, 24, 16) }
        setContentView(content)
        content.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(24 + insets.systemWindowInsetLeft, insets.systemWindowInsetTop + 16,
                24 + insets.systemWindowInsetRight, insets.systemWindowInsetBottom + 16)
            insets
        }
        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label; setOnClickListener { action() }; content.addView(this)
        }
        button("Back to test") { finish() }
        message = TextView(this).apply { textSize = 18f; content.addView(this) }
        val frame = FrameLayout(this).apply { content.addView(this, LinearLayout.LayoutParams(-1, 0, 1f)) }
        preview = PreviewView(this).apply { frame.addView(this, FrameLayout.LayoutParams(-1, -1)) }
        review = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; frame.addView(this, FrameLayout.LayoutParams(-1, -1)) }
        allow = button("Allow camera") { requestCamera() }
        button("Camera permission settings") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
        }
        capture = button("Take photo") { takePhoto() }
        retake = button("Retake") { reviewing = false; photoFile.delete(); updateAvailability() }
        accept = button(if (check) "Finish camera check" else if (live) "Save live Maps photo" else "Use photo & next test") {
            if (!reviewing || !photoFile.isFile || (!check && !validStep())) return@button
            accepted = true
            if (!check) setResult(RESULT_OK, Intent().putExtra("run", runId).putExtra("step", stepId).putExtra("photo", photoName).putExtra("capturedAt", capturedAt))
            finish()
        }
        capturedAt = savedInstanceState?.getString("capturedAt")
        reviewing = savedInstanceState?.getBoolean("reviewing") == true && photoFile.isFile
        if (reviewing) showReview()
        updateAvailability()
        if (savedInstanceState == null && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) requestCamera()
    }
    override fun onStart() {
        super.onStart()
        if (!::capture.isInitialized) return
        AppState.listeners.add(redraw)
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        updateAvailability()
    }
    override fun onStop() { AppState.listeners.remove(redraw); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("reviewing", reviewing); outState.putString("capturedAt", capturedAt); super.onSaveInstanceState(outState) }
    override fun onDestroy() {
        camera?.unbindAll()
        if (::capture.isInitialized && !isChangingConfigurations && (!accepted || check)) photoFile.delete()
        super.onDestroy()
    }
    private fun validStep(): Boolean {
        if (live) return AppState.fieldSessionId == runId && AppState.fieldPhase == "LIVE_MAPS" && AppState.fieldLivePhotoName == photoName
        val run = AppState.guidedRun ?: return false
        return AppState.guidedRunId == runId && run.phase == GuidedRun.Phase.PHOTO_REQUIRED && run.current?.id == stepId
    }
    private fun updateAvailability() {
        if (!::capture.isInitialized) return
        val permitted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val valid = check || validStep()
        message.text = when {
            !valid -> "This test stopped or the photo wait expired. Go back to share the partial report."
            !permitted -> "Allow camera access here to photograph your Pod. Photos stay on this phone until you share a report."
            reviewing -> "Check that the Pod screen is sharp and readable. Retake if needed."
            live -> "Live Maps screen: frame the Pod. The instruction may change; its timing is recorded with this photo."
            check -> "Camera check: frame the Pod. This practice photo is deleted when you leave."
            else -> "${intent.getStringExtra("title")}\nFrame the Pod only. The test screen stays active; take the photo within two minutes."
        }
        allow.visibility = if (permitted) View.GONE else View.VISIBLE
        preview.visibility = if (reviewing) View.GONE else View.VISIBLE
        review.visibility = if (reviewing) View.VISIBLE else View.GONE
        capture.visibility = if (reviewing) View.GONE else View.VISIBLE
        capture.isEnabled = permitted && valid && imageCapture != null && !capturing
        accept.visibility = if (reviewing) View.VISIBLE else View.GONE; accept.isEnabled = valid
        retake.visibility = if (reviewing) View.VISIBLE else View.GONE; retake.isEnabled = valid
    }
    private fun requestCamera() {
        if (requesting) return
        requesting = true
        requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION)
    }
    @Deprecated("Platform permission callback")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION) {
            requesting = false
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
            updateAvailability()
        }
    }
    private fun startCamera() {
        if (imageCapture != null || isDestroyed) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (isDestroyed || isFinishing) return@addListener
            try {
                val provider = future.get(); camera = provider
                val view = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                val still = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, view, still)
                imageCapture = still; updateAvailability()
            } catch (_: Exception) { message.text = "Camera could not open. Close other camera apps, check permission and reopen this screen." }
        }, ContextCompat.getMainExecutor(this))
    }
    private fun takePhoto() {
        val camera = imageCapture ?: return
        if (capturing || (!check && !validStep())) return
        capturing = true; updateAvailability()
        camera.targetRotation = preview.display.rotation
        camera.takePicture(ImageCapture.OutputFileOptions.Builder(photoFile).build(), ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    capturing = false
                    if (isDestroyed || isFinishing) { photoFile.delete(); return }
                    capturedAt = java.time.Instant.now().toString()
                    reviewing = true; showReview(); updateAvailability()
                }
                override fun onError(exception: ImageCaptureException) {
                    capturing = false; updateAvailability(); message.text = "Photo could not be saved. Check phone storage and try again."
                }
            })
    }
    private fun showReview() {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(photoFile.path, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
            val bitmap = BitmapFactory.decodeFile(photoFile.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("Unreadable photo")
            val degrees = ExifInterface(photoFile).rotationDegrees
            val displayed = if (degrees == 0) bitmap else android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height,
                Matrix().apply { postRotate(degrees.toFloat()) }, true).also { bitmap.recycle() }
            review.setImageBitmap(displayed)
        } catch (_: Exception) { reviewing = false; photoFile.delete(); message.text = "Photo could not be opened; please retake it." }
    }
    companion object { private const val CAMERA_PERMISSION = 31 }
}
