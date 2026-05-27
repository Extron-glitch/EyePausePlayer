package com.eyepause.player

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.MediaController
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.eyepause.player.databinding.ActivityMainBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var faceDetector: FaceDetector

    // State
    private var isVideoLoaded = false
    private var userIsLooking = true
    private var videoPausedByEye = false

    // Debounce: only pause after 1.5s of no face (avoids flicker)
    private val handler = Handler(Looper.getMainLooper())
    private var pauseRunnable: Runnable? = null
    private val PAUSE_DELAY_MS = 1500L

    // ─── Permission launchers ─────────────────────────────────────────────────

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else Toast.makeText(this, "Camera permission needed for eye tracking", Toast.LENGTH_LONG).show()
    }

    private val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { loadVideo(it) }
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.all { it }
        if (granted) videoPickerLauncher.launch("video/*")
        else Toast.makeText(this, "Storage permission needed to pick video", Toast.LENGTH_SHORT).show()
    }

    // ─── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Fullscreen immersive
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        cameraExecutor = Executors.newSingleThreadExecutor()
        setupFaceDetector()
        setupClickListeners()

        // Check if launched with a video intent (e.g. from file manager)
        intent?.data?.let { loadVideo(it) }

        // Request camera permission on start
        requestCameraPermission()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        faceDetector.close()
        handler.removeCallbacksAndMessages(null)
    }

    // ─── Setup ─────────────────────────────────────────────────────────────────

    private fun setupFaceDetector() {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.15f) // Detect faces taking at least 15% of frame
            .build()
        faceDetector = FaceDetection.getClient(options)
    }

    private fun setupClickListeners() {
        binding.pickVideoBtn.setOnClickListener {
            requestStorageAndPickVideo()
        }

        // Tap video to show/hide media controls
        binding.videoView.setOnClickListener {
            // Let native controls handle this
        }
    }

    // ─── Permissions ───────────────────────────────────────────────────────────

    private fun requestCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED -> startCamera()
            else -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun requestStorageAndPickVideo() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO)
                    == PackageManager.PERMISSION_GRANTED -> videoPickerLauncher.launch("video/*")
                else -> storagePermissionLauncher.launch(
                    arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
                )
            }
        } else {
            when {
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED -> videoPickerLauncher.launch("video/*")
                else -> storagePermissionLauncher.launch(
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                )
            }
        }
    }

    // ─── Camera / Eye Tracking ─────────────────────────────────────────────────

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, FaceAnalyzer())
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_SHORT).show()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    // ─── Face Analyzer ─────────────────────────────────────────────────────────

    @androidx.camera.core.ExperimentalGetImage
    inner class FaceAnalyzer : ImageAnalysis.Analyzer {
        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image ?: run {
                imageProxy.close()
                return
            }
            val inputImage = InputImage.fromMediaImage(
                mediaImage,
                imageProxy.imageInfo.rotationDegrees
            )
            faceDetector.process(inputImage)
                .addOnSuccessListener { faces ->
                    val faceDetected = faces.isNotEmpty()
                    onFaceDetectionResult(faceDetected)
                }
                .addOnFailureListener {
                    // Silently ignore
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        }
    }

    // ─── Video Control Logic ────────────────────────────────────────────────────

    private fun onFaceDetectionResult(faceDetected: Boolean) {
        handler.post {
            if (faceDetected) {
                // Cancel any pending pause
                pauseRunnable?.let { handler.removeCallbacks(it) }
                pauseRunnable = null

                if (!userIsLooking) {
                    userIsLooking = true
                    updateStatusBadge(looking = true)

                    // Resume only if WE paused it (not user)
                    if (videoPausedByEye && isVideoLoaded) {
                        binding.videoView.start()
                        videoPausedByEye = false
                        binding.pauseOverlay.visibility = View.GONE
                    }
                }
            } else {
                // Schedule pause after debounce delay
                if (pauseRunnable == null && userIsLooking) {
                    pauseRunnable = Runnable {
                        userIsLooking = false
                        updateStatusBadge(looking = false)

                        if (isVideoLoaded && binding.videoView.isPlaying) {
                            binding.videoView.pause()
                            videoPausedByEye = true
                            binding.pauseOverlay.visibility = View.VISIBLE
                        }
                        pauseRunnable = null
                    }
                    handler.postDelayed(pauseRunnable!!, PAUSE_DELAY_MS)
                }
            }
        }
    }

    private fun updateStatusBadge(looking: Boolean) {
        if (looking) {
            binding.statusText.text = "Watching"
            binding.statusDot.setBackgroundResource(R.drawable.status_dot) // green
        } else {
            binding.statusText.text = "Paused"
            // Tint the dot red
            binding.statusDot.background.setTint(
                ContextCompat.getColor(this, android.R.color.holo_red_light)
            )
        }
    }

    // ─── Video Loading ─────────────────────────────────────────────────────────

    private fun loadVideo(uri: Uri) {
        binding.pickVideoLayout.visibility = View.GONE
        binding.videoView.visibility = View.VISIBLE
        isVideoLoaded = true
        videoPausedByEye = false

        val mediaController = MediaController(this)
        mediaController.setAnchorView(binding.videoView)
        binding.videoView.setMediaController(mediaController)
        binding.videoView.setVideoURI(uri)

        binding.videoView.setOnPreparedListener { mp: MediaPlayer ->
            mp.isLooping = false
            binding.videoView.start()
            Toast.makeText(this, "Eye tracking active 👁️", Toast.LENGTH_SHORT).show()
        }

        binding.videoView.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, "Cannot play this video format", Toast.LENGTH_SHORT).show()
            false
        }

        binding.videoView.setOnCompletionListener {
            videoPausedByEye = false
            binding.pauseOverlay.visibility = View.GONE
        }
    }
}
