package com.zkincamcam.xcam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the beauty engine presets: named parameter bundles for the
 * variance-guided beauty pass. Each preset tunes the shader's theta, restore
 * gain, and whitening so one slider (beauty amount) stays the master control.
 */
class BeautyEnginePresetTest {

    @Test
    fun builtInSetIsExactlyTheFourShippedPresets() {
        assertEquals(
            listOf("natural", "studio", "glam", "soft_focus"),
            BeautyEnginePreset.BUILT_INS.map { it.id }
        )
    }

    @Test
    fun naturalKeepsMostTexture() {
        val p = BeautyEnginePreset.BUILT_INS.first { it.id == "natural" }
        // high restore gain, low smoothing ceiling
        assertTrue(p.restoreGain > 0.45f)
        assertTrue(p.smoothCeiling < 0.6f)
        assertEquals(0f, p.whitening)
    }

    @Test
    fun glamIsStrongestAndWhitens() {
        val p = BeautyEnginePreset.BUILT_INS.first { it.id == "glam" }
        val natural = BeautyEnginePreset.BUILT_INS.first { it.id == "natural" }
        assertTrue(p.smoothCeiling > natural.smoothCeiling)
        assertTrue(p.whitening > 0f)
        assertTrue(p.catchlightBoost >= 1f)
    }

    @Test
    fun softFocusSuppressesTextureRestore() {
        val p = BeautyEnginePreset.BUILT_INS.first { it.id == "soft_focus" }
        val natural = BeautyEnginePreset.BUILT_INS.first { it.id == "natural" }
        assertTrue(p.restoreGain < natural.restoreGain)
        assertTrue(p.smoothCeiling > natural.smoothCeiling)
    }

    @Test
    fun allPresetsHaveLabelsAndValidRanges() {
        for (p in BeautyEnginePreset.BUILT_INS) {
            assertTrue(p.label.isNotBlank())
            assertTrue(p.theta in 0.05f..0.2f)
            assertTrue(p.smoothCeiling in 0f..1f)
            assertTrue(p.restoreGain in 0f..1f)
            assertTrue(p.whitening in 0f..0.5f)
        }
    }
}
