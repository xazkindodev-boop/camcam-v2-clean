package com.zkincamcam.xcam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Catchlight manual override: the user can force eye light even when the source
 * photo has no reflection at all. Semantics:
 *  - `null` override (never touched) -> follow the beauty preset's boost.
 *  - explicit override (0..2 slider) -> wins over the preset; 0 disables catchlight.
 *  - values clamp to [0, 2]; negative input clamps to 0.
 */
class CatchlightControlTest {

    private fun control(override: Float?) = CatchlightControl(userOverride = override)

    @Test
    fun nullOverrideFollowsPreset() {
        assertEquals(1.35f, control(null).resolve(1.35f), 1e-4f)
        assertEquals(1.0f, control(null).resolve(1.0f), 1e-4f)
    }

    @Test
    fun explicitOverrideWinsOverPreset() {
        assertEquals(0f, control(0f).resolve(1.35f), 1e-4f)
        assertEquals(1.8f, control(1.8f).resolve(1.0f), 1e-4f)
    }

    @Test
    fun overrideClampsToValidRange() {
        assertEquals(2f, control(5f).resolve(1f), 1e-4f)
        assertEquals(0f, control(-0.5f).resolve(1f), 1e-4f)
    }

    @Test
    fun factoryFromSliderMarksTouchedOnlyWhenSliderMoved() {
        // Slider default value equals the preset — a value from a never-touched
        // slider is indistinguishable, so the touch flag decides.
        assertNull(CatchlightControl.fromSlider(1.0f, wasTouched = false).userOverride)
        assertEquals(1.0f, CatchlightControl.fromSlider(1.0f, wasTouched = true).userOverride!!)
        assertTrue(CatchlightControl.fromSlider(0.4f, wasTouched = true).userOverride!! > 0f)
    }
}
