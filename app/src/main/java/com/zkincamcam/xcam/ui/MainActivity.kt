package com.zkincamcam.xcam.ui

import android.annotation.SuppressLint
import kotlin.math.abs
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.TextureView
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.slider.RangeSlider
import com.google.android.material.slider.Slider
import com.zkincamcam.xcam.utils.VideoTrim
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.tabs.TabLayout
import com.zkincamcam.xcam.R
import com.zkincamcam.xcam.core.capture.FaceLandmarkAnalyzer
import com.zkincamcam.xcam.core.capture.MediaPreprocessor
import com.zkincamcam.xcam.core.capture.ExifForensicEngine
import com.zkincamcam.xcam.data.Constants.KEY_IS_MIRRORED
import com.zkincamcam.xcam.data.Constants.KEY_ROTATION_ANGLE
import com.zkincamcam.xcam.data.Constants.KEY_MEDIA_PATH
import com.zkincamcam.xcam.data.Constants.PREFS_NAME
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.Future
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

class MainActivity : AppCompatActivity(), XposedServiceHelper.OnServiceListener {

    companion object {
        private const val VIRTUAL_PREFIX = "virtual."
        private const val DEFAULT_VIDEO_NAME = "virtual.mp4"
        private const val DEFAULT_SHARPNESS = 2.0f
        private const val MIN_ROTATION_STEP_DEGREES = 0.6f
    }

    private lateinit var editorPreview: TextureView
    private lateinit var scopesOverlay: ImageView
    private lateinit var tvNoPreview: TextView
    private lateinit var btnDeleteMedia: MaterialButton
    private lateinit var btnMirror: MaterialButton
    private lateinit var btnRotateLeft: MaterialButton
    private lateinit var btnRotateRight: MaterialButton
    private lateinit var btnSelectVideo: MaterialButton
    private lateinit var btnApplyEdits: MaterialButton
    private lateinit var btnAutoLight: MaterialButton
    private lateinit var tvModuleStatus: TextView
    private lateinit var cardModuleStatus: com.google.android.material.card.MaterialCardView
    private lateinit var cardScopedApps: com.google.android.material.card.MaterialCardView
    private lateinit var layoutScopedApps: LinearLayout
    
    private lateinit var sliderZoom: Slider
    private lateinit var sliderFineRotation: Slider
    private lateinit var sliderSharpness: Slider
    private lateinit var sliderBeauty: Slider
    private lateinit var sliderCatchlight: Slider
    private lateinit var sliderHd: Slider
    private var catchlightTouched = false
    private lateinit var sliderNoise: Slider
    private lateinit var sliderPulse: Slider
    private lateinit var switchEyeMovement: SwitchMaterial
    private lateinit var sliderExposure: Slider
    private lateinit var sliderContrast: Slider
    private lateinit var sliderHighlights: Slider
    private lateinit var sliderShadows: Slider
    private lateinit var sliderTemp: Slider
    private lateinit var sliderTint: Slider
    private lateinit var sliderSaturation: Slider
    
    private lateinit var editorTabs: TabLayout
    private lateinit var groupTransform: LinearLayout
    private lateinit var groupLighting: LinearLayout
    private lateinit var groupColor: LinearLayout
    private lateinit var groupLiveness: LinearLayout
    
    private lateinit var btnLoadLut: MaterialButton
    private lateinit var btnCloneForensics: MaterialButton
    private lateinit var btnResetDefaults: MaterialButton

    private var isMirrored = false
    private var rotationAngle = 0
    private var mXposedService: XposedService? = null
    
    private val liveRenderer = LiveEditorRenderer()
    private var currentBitmap: Bitmap? = null
    private var currentImageUri: Uri? = null
    private var currentLut: Bitmap? = null
    private var currentLutUri: Uri? = null
    private var currentLandmarks = FaceLandmarkAnalyzer.EyeLandmarks(0.5f, 0.5f, 0.5f, 0.5f, false)

    private var scaleFactor = 1.0f
    private var translateX = 0f
    private var translateY = 0f
    private val gestureState = EditorGestureState()
    private var prevFingerAngle = 0f
    private var currentTouchAngle = 0f
    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector
    private val editorHistory = EditorHistory()
    private var cropOverlay: CropOverlayView? = null
    private var cropState: CropRectState? = null
    private var cropModeEnabled = false
    private var activeCropAspect: Float? = null
    private var activeCropFraction: CropFraction? = null
    private var activeBeautyPreset: com.zkincamcam.xcam.core.capture.BeautyEnginePreset =
        com.zkincamcam.xcam.core.capture.BeautyEnginePreset.DEFAULT
    private var sliderTrimVideo: RangeSlider? = null
    private var labelTrimVideo: android.widget.TextView? = null
    private var videoDurationUs: Long = 0L
    private var restoringSnapshot = false
    private val landmarkExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "xcam-landmarks").apply { isDaemon = true }
    }
    private var landmarkTask: Future<*>? = null
    private var landmarkGeneration = 0L

    private val videoPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK) res.data?.data?.let { loadMediaForEditing(it) }
    }

    private val lutPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK) res.data?.data?.let { uri ->
            val input = contentResolver.openInputStream(uri)
            val bitmap = input?.use { BitmapFactory.decodeStream(it) }
            if (bitmap == null) {
                Toast.makeText(this, "Could not load LUT image", Toast.LENGTH_LONG).show()
                return@let
            }
            currentLut = bitmap
            currentLutUri = uri
            updateLiveRenderer()
            pushHistory()
        }
    }

    private val forensicPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK) res.data?.data?.let { uri ->
            val temp = File(cacheDir, "ref.jpg")
            contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(temp).use { output -> input.copyTo(output) } }
            ExifForensicEngine.cloneHardwareTables(temp, this)
            temp.delete()
            Toast.makeText(this, "Hardware Forensics Cloned & Saved", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        XposedServiceHelper.registerListener(this)
        ExifForensicEngine.loadSignature(this)
        setupWindowInsets()
        setupUI()
        loadSettings()
        updateModuleStatusUI()
        pushHistory() // baseline state for undo/redo
    }

    private fun setupUI() {
        setupTitleSpannable(findViewById(R.id.tv_title))
        editorPreview = findViewById(R.id.editor_preview); editorPreview.surfaceTextureListener = liveRenderer
        scopesOverlay = findViewById(R.id.scopes_overlay); tvNoPreview = findViewById(R.id.tv_no_preview)
        btnDeleteMedia = findViewById(R.id.btn_delete_media); btnMirror = findViewById(R.id.btn_mirror)
        btnRotateLeft = findViewById(R.id.btn_rotate_left); btnRotateRight = findViewById(R.id.btn_rotate_right)
        btnSelectVideo = findViewById(R.id.btn_select_video); tvModuleStatus = findViewById(R.id.tv_module_status)
        btnApplyEdits = findViewById(R.id.btn_apply_edits); btnAutoLight = findViewById(R.id.btn_auto_light)
        btnApplyEdits.isEnabled = false
        cardModuleStatus = findViewById(R.id.card_module_status); cardScopedApps = findViewById(R.id.card_scoped_apps)
        layoutScopedApps = findViewById(R.id.layout_scoped_apps)
        
        editorTabs = findViewById(R.id.editor_tabs); groupTransform = findViewById(R.id.group_transform)
        groupLighting = findViewById(R.id.group_lighting); groupColor = findViewById(R.id.group_color)
        groupLiveness = findViewById(R.id.group_liveness)
        
        sliderZoom = findViewById(R.id.slider_zoom); sliderFineRotation = findViewById(R.id.slider_fine_rotation)
        sliderSharpness = findViewById(R.id.slider_sharpness)
        sliderBeauty = findViewById(R.id.slider_beauty); sliderCatchlight = findViewById(R.id.slider_catchlight); sliderHd = findViewById(R.id.slider_hd)
        sliderNoise = findViewById(R.id.slider_noise); sliderPulse = findViewById(R.id.slider_pulse)
        switchEyeMovement = findViewById(R.id.switch_eye_movement)
        sliderExposure = findViewById(R.id.slider_exposure); sliderContrast = findViewById(R.id.slider_contrast)
        sliderHighlights = findViewById(R.id.slider_highlights); sliderShadows = findViewById(R.id.slider_shadows)
        sliderTemp = findViewById(R.id.slider_temp); sliderTint = findViewById(R.id.slider_tint)
        sliderSaturation = findViewById(R.id.slider_saturation)
        btnLoadLut = findViewById(R.id.btn_load_lut); btnCloneForensics = findViewById(R.id.btn_clone_forensics)
        btnResetDefaults = findViewById(R.id.btn_reset_defaults)
        sliderTrimVideo = findViewById(R.id.slider_trim_video)
        labelTrimVideo = findViewById(R.id.label_trim_video)
        setupPresetChips()
        setupBeautyEngineChips()
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_crop_toggle).setOnClickListener {
            toggleCropMode(!cropModeEnabled)
            findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_crop_apply).isEnabled = cropModeEnabled
        }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_crop_apply).setOnClickListener { applyCrop() }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_undo).setOnClickListener { undoEdit() }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_redo).setOnClickListener { redoEdit() }

        setupEventListeners()
        setupGestures()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                // Two fingers down: start rotation tracking from the current finger angle.
                gestureState.begin()
                prevFingerAngle = currentTouchAngle
                return true
            }
            override fun onScale(d: ScaleGestureDetector): Boolean {
                // Two-finger rotate: track the angle between the fingers while pinching.
                // onScale only fires while two pointers are down, so no extra guard needed.
                run {
                    val angle = currentTouchAngle
                    val delta = EditorGestureState.normalizeDeltaDegrees(prevFingerAngle, angle)
                    if (abs(delta) > MIN_ROTATION_STEP_DEGREES) {
                        gestureState.addRotation(delta)
                        prevFingerAngle = angle
                        sliderFineRotation.value = gestureState.fineRotationDegrees.coerceIn(sliderFineRotation.valueFrom, sliderFineRotation.valueTo)
                        updateTransformMatrix()
                    }
                }
                scaleFactor *= d.scaleFactor; scaleFactor = scaleFactor.coerceIn(0.5f, 5.0f)
                sliderZoom.value = scaleFactor.coerceIn(1.0f, 3.0f); updateTransformMatrix(); return true
            }
            override fun onScaleEnd(d: ScaleGestureDetector) {
                gestureState.end()
            }
        })
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                translateX -= dx / editorPreview.width * 2f; translateY += dy / editorPreview.height * 2f
                updateTransformMatrix(); return true
            }
        })
        editorPreview.setOnTouchListener { _, ev ->
            if (cropModeEnabled && handleCropTouch(ev)) return@setOnTouchListener true
            if (ev.pointerCount >= 2) {
                currentTouchAngle = EditorGestureState.angleBetweenDegrees(
                    ev.getX(0), ev.getY(0), ev.getX(1), ev.getY(1)
                )
            }
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> editorHistory.beginCoalesce()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    editorHistory.endCoalesce(); pushHistory()
                }
            }
            scaleDetector.onTouchEvent(ev); gestureDetector.onTouchEvent(ev); true
        }
    }

    /** Rotation/zoom anchor: the eye midpoint when landmarks exist, else frame centre. */
    private fun transformPivot(): Pair<Float, Float> {
        val lm = currentLandmarks
        return if (lm.detected) {
            Pair((lm.leftEyeX + lm.rightEyeX) / 2f, (lm.leftEyeY + lm.rightEyeY) / 2f)
        } else Pair(0.5f, 0.42f) // degraded guess: where portraits put eyes
    }

    private fun updateTransformMatrix() {
        val (px, py) = transformPivot()
        val m = currentLandmarks.let {
            LiveEditorTransform.buildMatrix(
                if (it.detected) it.faceCenterX else 0.5f,
                if (it.detected) it.faceCenterY else 0.5f,
                scaleFactor,
                translateX,
                translateY,
                rotationAngle.toFloat() + sliderFineRotation.value,
                isMirrored,
                if (it.detected) it.faceRollDegrees else 0f,
                pivotX = px,
                pivotY = py
            )
        }
        liveRenderer.updateTransform(m)
    }

    private fun setupEventListeners() {
        editorTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(t: TabLayout.Tab?) {
                groupTransform.visibility = if (t?.position == 0) View.VISIBLE else View.GONE
                groupLighting.visibility = if (t?.position == 1) View.VISIBLE else View.GONE
                groupColor.visibility = if (t?.position == 2) View.VISIBLE else View.GONE
                groupLiveness.visibility = if (t?.position == 3) View.VISIBLE else View.GONE
            }
            override fun onTabUnselected(t: TabLayout.Tab?) {}
            override fun onTabReselected(t: TabLayout.Tab?) {}
        })

        val update: (Slider, Float, Boolean) -> Unit = { slider, _, _ ->
            if (slider === sliderZoom) {
                scaleFactor = sliderZoom.value
            }
            if (slider === sliderZoom || slider === sliderFineRotation) {
                updateTransformMatrix()
            }
            updateLiveRenderer()
        }
        sliderCatchlight.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) { catchlightTouched = true }
            override fun onStopTrackingTouch(slider: Slider) {}
        })
        listOf(sliderZoom, sliderFineRotation, sliderExposure, sliderContrast, sliderHighlights, sliderShadows, sliderTemp, sliderTint, sliderSaturation, sliderSharpness, sliderBeauty, sliderCatchlight, sliderHd, sliderNoise, sliderPulse)
            .forEach { slider ->
                slider.addOnChangeListener(Slider.OnChangeListener(update))
                // History: one drag = one entry. Bracket the gesture, commit on release.
                @android.annotation.SuppressLint("ClickableViewAccessibility")
                slider.setOnTouchListener { v, ev ->
                    when (ev.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> editorHistory.beginCoalesce()
                        android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                            editorHistory.endCoalesce(); pushHistory()
                        }
                    }
                    v.onTouchEvent(ev)
                    false // don't consume — slider keeps its own handling
                }
            }
        switchEyeMovement.setOnCheckedChangeListener { _, _ ->
            updateLiveRenderer()
            if (!restoringSnapshot) pushHistory()
        }
        sliderTrimVideo?.addOnChangeListener { _, _, _ -> /* applied on export */ }

        btnMirror.setOnClickListener { isMirrored = !isMirrored; saveSettings(); updateTransformMatrix(); updateLiveRenderer(); pushHistory() }
        btnRotateLeft.setOnClickListener { rotationAngle = (rotationAngle - 90 + 360) % 360; saveSettings(); updateTransformMatrix(); updateLiveRenderer(); pushHistory() }
        btnRotateRight.setOnClickListener { rotationAngle = (rotationAngle + 90) % 360; saveSettings(); updateTransformMatrix(); updateLiveRenderer(); pushHistory() }
        btnResetDefaults.setOnClickListener { resetToDefaults() }
        btnAutoLight.setOnClickListener {
            currentBitmap?.let { bitmap ->
                sliderExposure.value = MediaPreprocessor.suggestExposure(bitmap)
            }
        }
        btnApplyEdits.setOnClickListener {
            currentImageUri?.let(::copyMediaToInternal)
        }
        
        btnDeleteMedia.setOnClickListener { deleteMedia() }
        btnSelectVideo.setOnClickListener {
            videoPickerLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"; putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*")) })
        }
        btnLoadLut.setOnClickListener {
            lutPickerLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "image/*" })
        }
        btnCloneForensics.setOnClickListener {
            forensicPickerLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "image/jpeg" })
        }
    }

    /** Enters/exits crop mode. Entering initializes the rect over the preview. */
    private fun toggleCropMode(enabled: Boolean) {
        cropModeEnabled = enabled
        if (enabled) {
            val w = editorPreview.width.toFloat()
            val h = editorPreview.height.toFloat()
            if (w <= 0f || h <= 0f) { cropModeEnabled = false; return }
            cropState = CropRectState(
                floatArrayOf(0f, 0f, w, h),
                minSize = minOf(w, h) * 0.2f,
                aspect = activeCropAspect
            )
            cropOverlay?.setCropRect(cropState!!.left, cropState!!.top, cropState!!.right, cropState!!.bottom)
            cropOverlay?.visibility = View.VISIBLE
        } else {
            cropOverlay?.visibility = View.GONE
            cropState = null
        }
    }

    /** Routes a preview touch into the crop rect. True if consumed. */
    private fun handleCropTouch(ev: android.view.MotionEvent): Boolean {
        val st = cropState ?: return false
        val x = ev.x; val y = ev.y
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                val kind: CropRectState.HandleKind = st.handleAt(x, y)
                    ?: edgeAt(st, x, y)
                    ?: if (x >= st.left && x <= st.right && y >= st.top && y <= st.bottom) CropRectState.MoveHandle
                    else return false
                st.beginDrag(kind, x, y)
            }
            android.view.MotionEvent.ACTION_MOVE -> { st.dragTo(x, y); cropOverlay?.setCropRect(st.left, st.top, st.right, st.bottom) }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                editorHistory.endCoalesce(); pushHistory()
            }
        }
        return true
    }

    private fun edgeAt(st: CropRectState, x: Float, y: Float): CropRectState.Edge? {
        val tol = 40f
        return when {
            kotlin.math.abs(x - st.left) <= tol && y in st.top..st.bottom -> CropRectState.Edge.LEFT
            kotlin.math.abs(x - st.right) <= tol && y in st.top..st.bottom -> CropRectState.Edge.RIGHT
            kotlin.math.abs(y - st.top) <= tol && x in st.left..st.right -> CropRectState.Edge.TOP
            kotlin.math.abs(y - st.bottom) <= tol && x in st.left..st.right -> CropRectState.Edge.BOTTOM
            else -> null
        }
    }

    /** Bakes the crop into the current bitmap and leaves crop mode. */
    private fun applyCrop() {
        val st = cropState ?: return
        val frac = st.fractional()
        val src = currentBitmap ?: run { toggleCropMode(false); return }
        if (frac.isFullFrame) { toggleCropMode(false); return }
        val x = (frac.left * src.width).toInt().coerceIn(0, src.width - 1)
        val y = (frac.top * src.height).toInt().coerceIn(0, src.height - 1)
        val w = ((frac.right - frac.left) * src.width).toInt().coerceIn(1, src.width - x)
        val h = ((frac.bottom - frac.top) * src.height).toInt().coerceIn(1, src.height - y)
        currentBitmap = Bitmap.createBitmap(src, x, y, w, h)
        if (currentBitmap !== src) src.recycle()
        // Compose with any earlier crop so the fraction stays relative to the
        // ORIGINAL source file — the export path re-reads that file and applies
        // this cumulative fraction, keeping preview and export identical.
        val prev = activeCropFraction
        activeCropFraction = if (prev == null) frac else CropFraction(
            left = prev.left + frac.left * (prev.right - prev.left),
            top = prev.top + frac.top * (prev.bottom - prev.top),
            right = prev.left + frac.right * (prev.right - prev.left),
            bottom = prev.top + frac.bottom * (prev.bottom - prev.top),
        )
        toggleCropMode(false)
        currentBitmap?.let(::requestLandmarkAnalysis)
        updateLiveRenderer()
        pushHistory()
        Toast.makeText(this, "Crop applied", Toast.LENGTH_SHORT).show()
    }

    private fun captureSnapshot(): EditorSnapshot = EditorSnapshot(
        zoom = sliderZoom.value, fineRotation = sliderFineRotation.value,
        translateX = translateX, translateY = translateY,
        exposure = sliderExposure.value, contrast = sliderContrast.value,
        highlights = sliderHighlights.value, shadows = sliderShadows.value,
        temperature = sliderTemp.value, tint = sliderTint.value,
        saturation = sliderSaturation.value, sharpness = sliderSharpness.value,
        beauty = sliderBeauty.value, noise = sliderNoise.value, pulse = sliderPulse.value,
        catchlight = sliderCatchlight.value, hd = sliderHd.value,
        eyeMovement = switchEyeMovement.isChecked, mirrored = isMirrored,
        rotationAngle = rotationAngle, catchlightTouched = catchlightTouched,
        beautyPresetId = activeBeautyPreset.id,
        lutUri = currentLutUri?.toString(),
        cropFraction = activeCropFraction,
        trimStartFraction = sliderTrimVideo?.values?.getOrNull(0),
        trimEndFraction = sliderTrimVideo?.values?.getOrNull(1),
    )

    private fun restoreSnapshot(snap: EditorSnapshot) {
        restoringSnapshot = true
        try {
            sliderZoom.value = snap.zoom; sliderFineRotation.value = snap.fineRotation
            translateX = snap.translateX; translateY = snap.translateY
            sliderExposure.value = snap.exposure; sliderContrast.value = snap.contrast
            sliderHighlights.value = snap.highlights; sliderShadows.value = snap.shadows
            sliderTemp.value = snap.temperature; sliderTint.value = snap.tint
            sliderSaturation.value = snap.saturation; sliderSharpness.value = snap.sharpness
            sliderBeauty.value = snap.beauty; sliderNoise.value = snap.noise; sliderPulse.value = snap.pulse
            sliderCatchlight.value = snap.catchlight; sliderHd.value = snap.hd
            switchEyeMovement.isChecked = snap.eyeMovement
            isMirrored = snap.mirrored; rotationAngle = snap.rotationAngle
            catchlightTouched = snap.catchlightTouched
            activeBeautyPreset = com.zkincamcam.xcam.core.capture.BeautyEnginePreset.BUILT_INS
                .firstOrNull { it.id == snap.beautyPresetId }
                ?: com.zkincamcam.xcam.core.capture.BeautyEnginePreset.DEFAULT
            syncBeautyPresetChip()
            restoreLut(snap.lutUri)
            val cropChanged = activeCropFraction != snap.cropFraction
            activeCropFraction = snap.cropFraction
            if (snap.trimStartFraction != null && snap.trimEndFraction != null && videoDurationUs > 0L) {
                sliderTrimVideo?.values = listOf(snap.trimStartFraction, snap.trimEndFraction)
            }
            if (cropChanged) rebuildPreviewForCrop(snap.cropFraction)
            scaleFactor = snap.zoom
            gestureState.reset()
            updateTransformMatrix()
            updateLiveRenderer()
            saveSettings()
        } finally {
            restoringSnapshot = false
        }
    }

    private fun restoreLut(uriString: String?) {
        if (currentLutUri?.toString() == uriString) return
        currentLutUri = uriString?.let(Uri::parse)
        currentLut = try {
            currentLutUri?.let { uri ->
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Could not restore LUT: ${e.message}", Toast.LENGTH_LONG).show()
            null
        }
    }

    private fun syncBeautyPresetChip() {
        val group = findViewById<com.google.android.material.chip.ChipGroup>(R.id.chip_group_beauty_engine)
        group.children
            .filterIsInstance<com.google.android.material.chip.Chip>()
            .firstOrNull { it.tag == activeBeautyPreset.id }
            ?.let { group.check(it.id) }
    }

    private fun rebuildPreviewForCrop(fraction: CropFraction?) {
        val uri = currentImageUri ?: return
        try {
            val input = contentResolver.openInputStream(uri)
                ?: throw java.io.IOException("Could not reopen source image")
            val source = input.use { BitmapFactory.decodeStream(it) }
                ?: throw java.io.IOException("Could not decode source image")
            currentBitmap = if (fraction == null || fraction.isFullFrame) {
                source
            } else {
                val x = (fraction.left * source.width).toInt().coerceIn(0, source.width - 1)
                val y = (fraction.top * source.height).toInt().coerceIn(0, source.height - 1)
                val w = ((fraction.right - fraction.left) * source.width).toInt().coerceIn(1, source.width - x)
                val h = ((fraction.bottom - fraction.top) * source.height).toInt().coerceIn(1, source.height - y)
                Bitmap.createBitmap(source, x, y, w, h).also { if (it !== source) source.recycle() }
            }
            currentBitmap?.let(::requestLandmarkAnalysis)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not restore crop preview: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun pushHistory() = editorHistory.push(captureSnapshot())

    private fun undoEdit() {
        editorHistory.undo()?.let { restoreSnapshot(it); Toast.makeText(this, "Undo", Toast.LENGTH_SHORT).show() }
    }

    private fun redoEdit() {
        editorHistory.redo()?.let { restoreSnapshot(it); Toast.makeText(this, "Redo", Toast.LENGTH_SHORT).show() }
    }

    private fun resetToDefaults() {
        restoringSnapshot = true
        sliderZoom.value = 1.0f; sliderFineRotation.value = 0f; sliderExposure.value = 0.0f; sliderContrast.value = 1.0f
        sliderHighlights.value = 0.0f; sliderShadows.value = 0.0f; sliderTemp.value = 0.0f
        sliderTint.value = 0.0f; sliderSaturation.value = 1.0f; sliderSharpness.value = DEFAULT_SHARPNESS
        sliderBeauty.value = 0.0f; sliderNoise.value = 0.5f; sliderPulse.value = 0.5f
        switchEyeMovement.isChecked = false; isMirrored = false; rotationAngle = 0
        currentLut = null; currentLutUri = null; scaleFactor = 1.0f; translateX = 0f; translateY = 0f
        gestureState.reset()
        videoDurationUs = 0L; setTrimControlsVisible(false); activeCropFraction = null
        activeBeautyPreset = com.zkincamcam.xcam.core.capture.BeautyEnginePreset.DEFAULT
        catchlightTouched = false; sliderCatchlight.value = 1.0f; sliderHd.value = 0f
        sliderTrimVideo?.values = listOf(0f, 1f)
        rebuildPreviewForCrop(null)
        restoringSnapshot = false
        updateTransformMatrix(); updateLiveRenderer(); saveSettings(); pushHistory()
        syncBeautyPresetChip()
        Toast.makeText(this, "Studio Parameters Reset", Toast.LENGTH_SHORT).show()
    }

    private fun updateLiveRenderer() {
        val adj = MediaPreprocessor.ColorAdjustments(sliderExposure.value, sliderContrast.value, sliderHighlights.value, sliderShadows.value, sliderTemp.value, sliderTint.value, sliderSaturation.value)
        liveRenderer.updateSettings(currentBitmap, currentLut, adj, currentLandmarks, switchEyeMovement.isChecked, sliderNoise.value, sliderPulse.value, sliderSharpness.value, sliderBeauty.value, activeBeautyPreset,
            com.zkincamcam.xcam.core.capture.CatchlightControl.fromSlider(sliderCatchlight.value, catchlightTouched).userOverride, sliderHd.value)
        updateScopes()
    }

    private fun updateScopes() {
        val b = currentBitmap ?: return
        val s = b.scale(128, 128, false)
        val d = ProScopesEngine.analyze(s)
        val sb = createBitmap(256, 120, Bitmap.Config.ARGB_8888)
        ProScopesEngine.renderHistogram(android.graphics.Canvas(sb), d, 256, 120)
        scopesOverlay.setImageBitmap(sb); s.recycle()
    }

    private fun loadMediaForEditing(uri: Uri) {
        try {
            landmarkGeneration++
            val generation = landmarkGeneration
            landmarkTask?.cancel(true)
            activeCropFraction = null
            videoDurationUs = 0L
            setTrimControlsVisible(false)
            editorHistory.clear()
            pushHistory()

            if (contentResolver.getType(uri)?.startsWith("video/") == true) {
                currentImageUri = null
                currentBitmap = null
                currentLandmarks = com.zkincamcam.xcam.core.capture.LandmarkCascade.degraded()
                btnApplyEdits.isEnabled = false
                tvNoPreview.visibility = View.VISIBLE
                updateLiveRenderer()
                setupVideoTrim(uri)
                copyMediaToInternal(uri); return
            }
            val input = contentResolver.openInputStream(uri)
                ?: throw java.io.IOException("Could not open selected image: $uri")
            val bitmap = input.use { BitmapFactory.decodeStream(it) }
                ?: throw java.io.IOException("Selected media is not a decodable image: $uri")

            currentBitmap = bitmap
            currentImageUri = uri
            btnApplyEdits.isEnabled = true
            tvNoPreview.visibility = View.GONE
            btnDeleteMedia.visibility = View.VISIBLE
            scaleFactor = 1.0f
            translateX = 0f
            translateY = 0f
            currentLandmarks = com.zkincamcam.xcam.core.capture.LandmarkCascade.degraded()
            updateTransformMatrix()
            updateLiveRenderer()
            requestLandmarkAnalysis(bitmap, generation)
            copyMediaToInternal(uri)
        } catch (e: Exception) { Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_SHORT).show() }
    }

    private fun requestLandmarkAnalysis(bitmap: Bitmap, generation: Long = ++landmarkGeneration) {
        landmarkTask?.cancel(true)
        landmarkTask = landmarkExecutor.submit {
            val landmarks = try {
                FaceLandmarkAnalyzer.analyze(bitmap)
            } catch (_: Exception) {
                null
            }
            runOnUiThread {
                if (generation == landmarkGeneration && currentBitmap === bitmap && landmarks != null) {
                    currentLandmarks = landmarks
                    updateTransformMatrix()
                    updateLiveRenderer()
                }
            }
        }
    }

    override fun onDestroy() {
        landmarkGeneration++
        landmarkTask?.cancel(true)
        landmarkExecutor.shutdownNow()
        super.onDestroy()
    }

    /** Builds one chip per built-in filter preset; selection applies the grade. */
    private fun setupPresetChips() {
        val group = findViewById<com.google.android.material.chip.ChipGroup>(R.id.chip_group_presets)
        for (preset in com.zkincamcam.xcam.core.capture.FilterPreset.BUILT_INS) {
            val chip = com.google.android.material.chip.Chip(this)
            chip.text = preset.label
            chip.isCheckable = true
            chip.id = android.view.View.generateViewId()
            group.addView(chip)
            chip.setOnClickListener {
                applyFilterPreset(preset)
            }
            if (preset.id == "none") group.check(chip.id)
        }
    }

    /** Builds chips for the beauty ENGINE presets (variance/restore/whiten profiles). */
    private fun setupBeautyEngineChips() {
        val group = findViewById<com.google.android.material.chip.ChipGroup>(R.id.chip_group_beauty_engine)
        for (preset in com.zkincamcam.xcam.core.capture.BeautyEnginePreset.BUILT_INS) {
            val chip = com.google.android.material.chip.Chip(this)
            chip.text = preset.label
            chip.isCheckable = true
            chip.id = android.view.View.generateViewId()
            chip.tag = preset.id
            group.addView(chip)
            chip.setOnClickListener {
                activeBeautyPreset = preset
                if (!catchlightTouched) sliderCatchlight.value = preset.catchlightBoost.coerceIn(0f, 2f)
                updateLiveRenderer()
                pushHistory()
            }
            if (preset.id == activeBeautyPreset.id) group.check(chip.id)
        }
    }

    /** Maps a preset's adjustments + liveness sliders onto the editor controls. */
    private fun applyFilterPreset(preset: com.zkincamcam.xcam.core.capture.FilterPreset) {
        val a = preset.adjustments
        sliderExposure?.value = a.exposure.coerceIn(sliderExposure?.valueFrom ?: 0f, sliderExposure?.valueTo ?: 0f)
        sliderContrast?.value = a.contrast.coerceIn(sliderContrast?.valueFrom ?: 0f, sliderContrast?.valueTo ?: 0f)
        sliderHighlights?.value = a.highlights.coerceIn(sliderHighlights?.valueFrom ?: 0f, sliderHighlights?.valueTo ?: 0f)
        sliderShadows?.value = a.shadows.coerceIn(sliderShadows?.valueFrom ?: 0f, sliderShadows?.valueTo ?: 0f)
        sliderTemp?.value = a.temperature.coerceIn(sliderTemp?.valueFrom ?: 0f, sliderTemp?.valueTo ?: 0f)
        sliderTint?.value = a.tint.coerceIn(sliderTint?.valueFrom ?: 0f, sliderTint?.valueTo ?: 0f)
        sliderSaturation?.value = a.saturation.coerceIn(sliderSaturation?.valueFrom ?: 0f, sliderSaturation?.valueTo ?: 0f)
        sliderNoise?.value = preset.noiseLevel.coerceIn(sliderNoise?.valueFrom ?: 0f, sliderNoise?.valueTo ?: 0f)
        sliderPulse?.value = preset.pulseLevel.coerceIn(sliderPulse?.valueFrom ?: 0f, sliderPulse?.valueTo ?: 0f)
        updateLiveRenderer(); saveSettings(); pushHistory()
    }

    /** Reads the clip length and shows the trim range slider for video imports. */
    private fun setupVideoTrim(uri: Uri) {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, uri)
            val ms = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            videoDurationUs = ms * 1000L
            if (videoDurationUs > 0) {
                sliderTrimVideo?.values = listOf(0f, 1f)
                setTrimControlsVisible(true)
            }
        } catch (_: Exception) {
            videoDurationUs = 0L
            setTrimControlsVisible(false)
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun setTrimControlsVisible(visible: Boolean) {
        sliderTrimVideo?.visibility = if (visible) View.VISIBLE else View.GONE
        labelTrimVideo?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /** Current trim selection in microseconds (empty when no video / no-op). */
    private fun currentVideoTrim(): VideoTrim? {
        if (videoDurationUs <= 0L) return null
        val values = sliderTrimVideo?.values ?: return null
        if (values.size < 2) return null
        val startUs = (values[0] * videoDurationUs).toLong()
        val endUs = (values[1] * videoDurationUs).toLong()
        return com.zkincamcam.xcam.utils.VideoTrim.of(startUs, endUs, videoDurationUs)
    }

    private fun copyMediaToInternal(uri: Uri) {
        try {
            val type = contentResolver.getType(uri) ?: "application/octet-stream"
            val extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(type) ?: "bin"
            val destinationName = if (type.startsWith("image/") || type.startsWith("video/")) {
                DEFAULT_VIDEO_NAME
            } else {
                VIRTUAL_PREFIX + extension
            }
            val stagingFile = File(cacheDir, "xcam_import_${System.nanoTime()}.${if (destinationName.endsWith(".mp4")) "mp4" else extension}")
            if (type.startsWith("image/")) {
                val adj = MediaPreprocessor.ColorAdjustments(sliderExposure.value, sliderContrast.value, sliderHighlights.value, sliderShadows.value, sliderTemp.value, sliderTint.value, sliderSaturation.value)
                val (px, py) = transformPivot()
                val m = LiveEditorTransform.buildMatrix(
                    if (currentLandmarks.detected) currentLandmarks.faceCenterX else 0.5f,
                    if (currentLandmarks.detected) currentLandmarks.faceCenterY else 0.5f,
                    scaleFactor,
                    translateX,
                    translateY,
                    rotationAngle.toFloat() + sliderFineRotation.value,
                    isMirrored,
                    if (currentLandmarks.detected) currentLandmarks.faceRollDegrees else 0f,
                    pivotX = px,
                    pivotY = py
                )
                com.zkincamcam.xcam.utils.MediaConverter.convertImageToMp4(
                    this, uri, stagingFile,
                    com.zkincamcam.xcam.core.capture.LivenessConfig(
                        beautyLevel = sliderBeauty.value,
                        sharpnessLevel = sliderSharpness.value,
                        noiseLevel = sliderNoise.value,
                        pulseLevel = sliderPulse.value,
                        eyeMovementEnabled = switchEyeMovement.isChecked,
                        transformMatrix = m,
                        lutBitmap = currentLut,
                        adjustments = adj,
                        beautyPreset = activeBeautyPreset,
                        catchlightOverride = com.zkincamcam.xcam.core.capture.CatchlightControl.fromSlider(sliderCatchlight.value, catchlightTouched).userOverride,
                        hdLevel = sliderHd.value,
                    ),
                    sliderSharpness.value,
                    cropFraction = activeCropFraction
                ) { success: Boolean ->
                    runOnUiThread { finishMediaImport(stagingFile, destinationName, success) }
                }
            } else {
                if (type.startsWith("video/")) {
                    com.zkincamcam.xcam.utils.MediaConverter.convertVideoWithTrim(
                        this, uri, stagingFile, currentVideoTrim()
                    ) { success ->
                        runOnUiThread { finishMediaImport(stagingFile, destinationName, success) }
                    }
                } else {
                    val input = contentResolver.openInputStream(uri)
                        ?: throw java.io.IOException("Could not open selected media: $uri")
                    input.use { source ->
                        FileOutputStream(stagingFile).use { output -> source.copyTo(output) }
                    }
                    finishMediaImport(stagingFile, destinationName, true)
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun finishMediaImport(stagingFile: File, destinationName: String, conversionSucceeded: Boolean) {
        if (!conversionSucceeded) {
            stagingFile.delete()
            Toast.makeText(this, R.string.toast_media_import_failed, Toast.LENGTH_LONG).show()
            return
        }

        try {
            val destination = commitImportedMedia(stagingFile, destinationName)
            saveMediaPath(destination.absolutePath)
            Toast.makeText(this, R.string.toast_media_imported, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            stagingFile.delete()
            Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun commitImportedMedia(stagingFile: File, destinationName: String): File {
        if (!stagingFile.isFile || stagingFile.length() == 0L) {
            throw java.io.IOException("Conversion produced no media output")
        }
        val destination = File(filesDir, destinationName)
        val token = System.nanoTime()
        val pending = File(filesDir, ".$destinationName.$token.tmp")
        val backup = File(filesDir, ".$destinationName.$token.bak")
        val destinationExisted = destination.exists()
        var preserveBackup = false
        try {
            stagingFile.inputStream().use { input ->
                FileOutputStream(pending).use { output -> input.copyTo(output) }
            }
            if (!pending.isFile || pending.length() != stagingFile.length()) {
                throw java.io.IOException("Staged media copy was incomplete")
            }

            if (destinationExisted) {
                destination.inputStream().use { input ->
                    FileOutputStream(backup).use { output -> input.copyTo(output) }
                }
                if (!backup.isFile || backup.length() != destination.length()) {
                    throw java.io.IOException("Could not create a complete backup of currently active media")
                }
            }
            try {
                java.nio.file.Files.move(
                    pending.toPath(),
                    destination.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                if (destination.exists() && !destination.delete()) {
                    throw java.io.IOException("Could not replace currently active media")
                }
                if (!pending.renameTo(destination)) {
                    pending.copyTo(destination, overwrite = false)
                }
            }
            if (!destination.isFile || destination.length() != stagingFile.length()) {
                throw java.io.IOException("New media failed post-copy verification")
            }

            filesDir.listFiles { _, name -> name.startsWith(VIRTUAL_PREFIX) && name != destinationName }
                ?.forEach { it.delete() }
            return destination
        } catch (e: Exception) {
            if (destinationExisted && backup.isFile) {
                try {
                    val restore = File(filesDir, ".$destinationName.$token.restore")
                    backup.copyTo(restore, overwrite = true)
                    try {
                        java.nio.file.Files.move(
                            restore.toPath(),
                            destination.toPath(),
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        )
                    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                        if (destination.exists() && !destination.delete()) {
                            throw java.io.IOException("Could not clear failed replacement")
                        }
                        if (!restore.renameTo(destination)) restore.copyTo(destination, overwrite = true)
                    }
                    restore.delete()
                } catch (restoreFailure: Exception) {
                    preserveBackup = true
                    e.addSuppressed(restoreFailure)
                    Toast.makeText(this, "Previous media backup retained at ${backup.name}", Toast.LENGTH_LONG).show()
                }
            } else if (!destinationExisted) {
                destination.delete()
            }
            throw e
        } finally {
            pending.delete()
            stagingFile.delete()
            if (!preserveBackup) backup.delete()
        }
    }

    override fun onResume() { super.onResume(); loadSettings(); updateModuleStatusUI() }
    override fun onServiceBind(s: XposedService) { mXposedService = s; runOnUiThread { updateModuleStatusUI() } }
    override fun onServiceDied(s: XposedService) { mXposedService = null; runOnUiThread { updateModuleStatusUI() } }
    private fun updateModuleStatusUI() {
        val scope = mXposedService?.scope?.filter { it != packageName } ?: emptyList()
        val active = mXposedService != null && scope.isNotEmpty()
        tvModuleStatus.text = getString(if (active) R.string.status_module_active else R.string.status_module_inactive)
        val color = MaterialColors.getColor(tvModuleStatus, if (active) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurfaceVariant)
        tvModuleStatus.setTextColor(color); cardModuleStatus.strokeColor = color; refreshLSPosedScope()
    }
    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val s = insets.getInsets(WindowInsetsCompat.Type.systemBars()); v.setPadding(s.left + 48, s.top + 48, s.right + 48, s.bottom + 48); insets
        }
    }
    private fun loadSettings() {
        val p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        isMirrored = p.getBoolean(KEY_IS_MIRRORED, false); rotationAngle = p.getInt(KEY_ROTATION_ANGLE, 0)
        if (p.getString(KEY_MEDIA_PATH, "")!!.isNotEmpty()) { tvNoPreview.visibility = View.GONE; btnDeleteMedia.visibility = View.VISIBLE }
    }
    private fun saveSettings() { getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit { putBoolean(KEY_IS_MIRRORED, isMirrored); putInt(KEY_ROTATION_ANGLE, rotationAngle) } }
    private fun saveMediaPath(path: String) { getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit { putString(KEY_MEDIA_PATH, path); putBoolean(KEY_IS_MIRRORED, isMirrored); putInt(KEY_ROTATION_ANGLE, rotationAngle) } }
    private fun deleteMedia() { filesDir.listFiles { _, n -> n.startsWith(VIRTUAL_PREFIX) }?.forEach { it.delete() }; saveMediaPath(""); currentBitmap = null; currentImageUri = null; btnApplyEdits.isEnabled = false; tvNoPreview.visibility = View.VISIBLE; btnDeleteMedia.visibility = View.GONE; updateLiveRenderer(); Toast.makeText(this, R.string.toast_media_removed, Toast.LENGTH_SHORT).show() }
    private fun setupTitleSpannable(v: TextView) { val s = SpannableStringBuilder(v.text); s.setSpan(ForegroundColorSpan(MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); v.text = s }
    private fun getOfficialScope(): List<String> = mXposedService?.scope ?: emptyList()
    private fun refreshLSPosedScope() {
        layoutScopedApps.removeAllViews(); val scope = getOfficialScope().filter { it != packageName }
        if (scope.isNotEmpty()) { scope.forEach { addAppIconToLayout(it) }; cardScopedApps.visibility = View.VISIBLE } else cardScopedApps.visibility = View.GONE
    }
    private fun addAppIconToLayout(p: String) { try { val i = packageManager.getApplicationIcon(p); val iv = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(110, 110).apply { setMargins(0, 0, 24, 0) }; setImageDrawable(i); setOnClickListener { Toast.makeText(this@MainActivity, p, Toast.LENGTH_SHORT).show() } }; layoutScopedApps.addView(iv) } catch (_: Exception) {} }
}
