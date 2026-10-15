package com.zkincamcam.xcam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterPresetTest {

    @Test
    fun builtInSetIsExactlyTheFiveShippedPresets() {
        assertEquals(
            listOf("none", "portrait", "night", "cinematic", "vivid"),
            FilterPreset.BUILT_INS.map { it.id }
        )
    }

    @Test
    fun noneResetsEverything() {
        val p = FilterPreset.BUILT_INS.first { it.id == "none" }
        assertEquals(0f, p.adjustments.temperature)
        assertEquals(1f, p.adjustments.saturation)
        assertEquals(1f, p.adjustments.contrast)
    }

    @Test
    fun presetsDifferFromNeutral() {
        val neutral = MediaPreprocessor.ColorAdjustments()
        for (p in FilterPreset.BUILT_INS.filter { it.id != "none" }) {
            assertNotEquals(neutral, p.adjustments)
        }
    }

    @Test
    fun allPresetsHaveLabels() {
        for (p in FilterPreset.BUILT_INS) {
            assertTrue(p.label.isNotBlank())
        }
    }

    @Test
    fun cinematicIsTealShadedAndContrasty() {
        val p = FilterPreset.BUILT_INS.first { it.id == "cinematic" }
        assertTrue(p.adjustments.contrast > 1f)
        assertTrue(p.adjustments.temperature < 0f)
    }
}
