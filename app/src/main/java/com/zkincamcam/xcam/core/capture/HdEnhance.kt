package com.zkincamcam.xcam.core.capture

import kotlin.math.exp

/**
 * "Blur -> HD": multi-scale detail recovery parameters for the editor shader.
 *
 * A soft source (out-of-focus scan, compressed messenger copy) is missing detail
 * at two scales. Recovery therefore uses two unsharp bands — a fine radius for
 * micro-texture and a coarse radius for contours lost to defocus — plus a
 * local-variance probe so the effect pushes hard on genuinely blurry patches and
 * stays out of the way where the image is already crisp. [haloFactor] damps the
 * lift on strong edges, because that is exactly where over-sharpening rings.
 *
 * The parameters are pure numbers; the shader performs the sampling.
 */
object HdEnhance {

    /** Shader-side bundle for one level in [0, 1]. */
    data class Params(
        /** Fine-band unsharp amount (micro-texture). */
        val amount: Float,
        /** Coarse-band clarity amount (defocus contour). */
        val clarity: Float,
        /** Fine-band sampling radius, in texels. */
        val radius: Float,
        /** Adaptive gain strength: how much softer patches are pushed. */
        val adaptive: Float,
    )

    /** Level 0 is a strict no-op, so the control can default to off. */
    fun params(level: Float): Params {
        val l = level.coerceIn(0f, 1f)
        return Params(
            amount = 1.35f * l,
            clarity = 0.75f * l,
            radius = 1.0f + 1.5f * l,
            adaptive = 0.85f * l,
        )
    }

    /**
     * Local-variance driven multiplier for the detail lift. Soft patches (low
     * variance) get up to [MAX_GAIN]; already-sharp ones stay at ~1 so the effect cannot turn grain into crunch.
     * Disabled (level 0) always returns 1.
     */
    fun adaptiveGain(variance: Float, level: Float): Float {
        if (level <= 0f) return 1f
        val l = level.coerceIn(0f, 1f)
        // Squared so the soft/sharp contrast is pronounced: grain-free crisp
        // areas get ~1.0x while defocus mush gets the full lift.
        val softness = SOFT_VARIANCE / (SOFT_VARIANCE + variance.coerceAtLeast(0f))
        return 1f + MAX_GAIN * softness * softness * l
    }

    /**
     * Weight applied to a detail sample of magnitude [detailMagnitude]. Flat areas
     * pass through untouched; strong edges (halo territory) are damped toward
     * [MIN_EDGE_WEIGHT].
     */
    fun haloFactor(detailMagnitude: Float): Float {
        val d = detailMagnitude.coerceAtLeast(0f)
        val damp = d / (d + EDGE_SOFTNESS)
        return (1f - (1f - MIN_EDGE_WEIGHT) * damp).coerceIn(MIN_EDGE_WEIGHT, 1f)
    }

    private const val SOFT_VARIANCE = 0.01f
    private const val MAX_GAIN = 1.6f
    private const val EDGE_SOFTNESS = 0.18f
    private const val MIN_EDGE_WEIGHT = 0.35f
}
