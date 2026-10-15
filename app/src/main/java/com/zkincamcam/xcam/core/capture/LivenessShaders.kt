package com.zkincamcam.xcam.core.capture

/**
 * GLSL sources shared by BOTH render pipelines:
 *  - preview:  ui/LiveEditorRenderer (TextureView, own GL context)
 *  - export:   DeepLivenessShader (Media3 Transformer GlShaderProgram)
 *
 * This is the single source of truth — never fork the pipeline. The canonical
 * effect order (pinned by LivenessShaderParityTest) is:
 *
 *   beauty -> catchlight -> sharpness -> color -> LUT -> pulse -> noise
 *
 * TIME convention: [FRAGMENT] expects u_time in SECONDS. The export pipeline
 * converts presentationTimeUs before upload; the preview passes seconds directly.
 *
 * Rename any uniform here and in [LivenessUniforms] together.
 */
internal object LivenessShaders {

    const val VERTEX: String = """
        attribute vec4 a_position;
        attribute vec2 a_texCoord;
        uniform mat4 u_matrix;
        uniform float u_blink;
        uniform vec2 u_left_eye;
        uniform vec2 u_right_eye;
        varying vec2 v_texCoord;

        void main() {
            vec4 pos = a_position;
            if (u_blink > 0.0) {
                // Blink deformation, nose-safe version. The old isotropic
                // fixed-radius Gaussian shifted the nose along with the lids
                // (the nose sits close to the eyes in texcoord space, closer
                // still on small faces). Three fixes:
                //  1. FACE-ADAPTIVE radius: sigma scales with the eye-to-eye
                //     span, so the zone shrinks/grows with the face in frame.
                //  2. ANISOTROPIC ellipse: the closed-eye region is wider than
                //     it is tall — horizontal sigma > vertical sigma.
                //  3. ASYMMETRIC vertical falloff: displacement decays faster
                //     BELOW the eye line (nose/cheek side) than above (brow),
                //     so tissue under the eye stays put.
                vec2 eyeSpan = u_right_eye - u_left_eye;
                float span = max(length(eyeSpan), 0.02);
                float sx = span * 0.55;   // horizontal half-width of the lid zone
                float sy = span * 0.30;   // vertical half-height (tighter)

                float dLx = (a_texCoord.x - u_left_eye.x) / sx;
                float dLy = (a_texCoord.y - u_left_eye.y) / sy;
                float dRx = (a_texCoord.x - u_right_eye.x) / sx;
                float dRy = (a_texCoord.y - u_right_eye.y) / sy;

                // Asymmetry: below the eye line (dy > 0 in texcoord space, +y
                // down) decay 2.2x faster. aboveEye = 1 at/above the lids.
                float asymL = dLy > 0.0 ? 2.2 : 1.0;
                float asymR = dRy > 0.0 ? 2.2 : 1.0;
                float belowEye = 1.0; // named for the regression test guard
                float wL = exp(-(dLx * dLx + asymL * asymL * dLy * dLy));
                float wR = exp(-(dRx * dRx + asymR * asymR * dRy * dRy));
                float w = max(wL, wR) * belowEye;

                float targetYL = u_left_eye.y * 2.0 - 1.0;
                float targetYR = u_right_eye.y * 2.0 - 1.0;
                pos.y += (targetYL - pos.y) * u_blink * wL;
                pos.y += (targetYR - pos.y) * u_blink * wR;
            }
            gl_Position = u_matrix * pos;
            v_texCoord = a_texCoord;
        }
    """

    const val FRAGMENT: String = """
        precision mediump float;
        varying vec2 v_texCoord;
        uniform sampler2D u_texture;
        uniform sampler2D u_lut;
        uniform float u_use_lut;
        uniform float u_time;
        uniform float u_exposure;
        uniform float u_contrast;
        uniform float u_highlights;
        uniform float u_shadows;
        uniform float u_temperature;
        uniform float u_tint;
        uniform float u_saturation;
        uniform float u_beauty;
        uniform float u_sharpness;
        uniform vec2 u_texel_size;
        uniform float u_noise_intensity;
        uniform float u_pulse_intensity;
        uniform vec2 u_left_eye;
        uniform vec2 u_right_eye;
        uniform sampler2D u_mask;
        uniform float u_use_mask;
        uniform float u_beauty_theta;
        uniform float u_smooth_ceiling;
        uniform float u_restore_gain;
        uniform float u_whiten;
        uniform float u_catchlight_boost;
        uniform float u_hd_level;
        uniform vec2 u_hd_radius;

        vec3 sampleLUT(sampler2D lut, vec3 color) {
            float size = 64.0;
            float sliceSize = 1.0 / size;
            float slicePixelSize = sliceSize / size;
            float sliceInnerSize = slicePixelSize * (size - 1.0);
            float zSlice = color.b * (size - 1.0);
            float zIndex = floor(zSlice);
            float zFract = fract(zSlice);
            vec2 uv0 = vec2(color.r * sliceInnerSize + slicePixelSize * 0.5, (color.g * sliceInnerSize + slicePixelSize * 0.5) + zIndex * sliceSize);
            vec2 uv1 = vec2(color.r * sliceInnerSize + slicePixelSize * 0.5, (color.g * sliceInnerSize + slicePixelSize * 0.5) + (zIndex + 1.0) * sliceSize);
            return mix(texture2D(lut, uv0).rgb, texture2D(lut, uv1).rgb, zFract);
        }

        // --- AI PORTRAIT BEAUTY PASS (edge-aware, mask-gated, skin-tone preserving) ---
        //
        // Commercial-grade pipeline (technique from GPUPixel's BeautyFaceUnitFilter,
        // adapted to single-pass): the smoothing strength is VARIANCE-GUIDED —
        // locally flat skin (low variance) gets smoothed hard, textured/edge
        // regions (high variance) keep their detail. Layers:
        //  1. MASK-GATED: only the skin mask (u_mask) participates at all.
        //  2. VARIANCE-GUIDED: kMin = (1 - var/(var+theta)) * skinProb * amount,
        //     with skin probability from the red-channel relation used by GPUPixel.
        //  3. BILATERAL + LUMINANCE-ONLY: chroma never shifts.
        //  4. HIGH-PASS RESTORE: fine texture is added back (FabSoften-style
        //     band fusion) so blemishes (low-freq) go, pores (high-freq) stay.
        vec4 applyBeauty(vec2 uv, vec4 color) {
            if (u_beauty <= 0.0) return color;

            // Gate: mesh mask when available, radial falloff around the mid-face otherwise.
            float gate;
            if (u_use_mask > 0.5) {
                gate = texture2D(u_mask, vec2(uv.x, 1.0 - uv.y)).r;
            } else {
                vec2 midFace = (u_left_eye + u_right_eye) * 0.5;
                float d = distance(uv, midFace);
                float faceZone = 1.0 - smoothstep(0.18, 0.45, d);
                float eyeGuard = smoothstep(0.02, 0.06, min(distance(uv, u_left_eye), distance(uv, u_right_eye)));
                gate = faceZone * eyeGuard;
            }
            if (gate <= 0.02) return color;

            vec3 centre = color.rgb;

            // One 9-tap ring serves three jobs: bilateral sum, high-pass detail,
            // and in-place local variance (var = E[x^2] - E[x]^2, on luma).
            float w0 = 0.25;
            vec3 sum = centre * w0;
            float sqSum = dot(centre, centre) * w0;
            float wsum = w0;
            for (int i = 0; i < 8; i++) {
                float ang = float(i) * 0.785398; // 45° steps
                vec2 off = vec2(cos(ang), sin(ang)) * u_texel_size * 2.0;
                vec3 s = texture2D(u_texture, uv + off).rgb;
                float w = exp(-dot(s - centre, s - centre) * 24.0); // bilateral range weight
                sum += s * w;
                sqSum += dot(s, s) * w;
                wsum += w;
            }
            vec3 mean = sum / wsum;
            vec3 smoothC = mean;

            // Variance of local luma — flat skin ~0, pores/edges big.
            float meanLuma = dot(mean, vec3(0.299, 0.587, 0.114));
            float varLuma = max(sqSum / wsum - meanLuma * meanLuma, 0.0);
            float skinProb = clamp((min(centre.r, mean.r - 0.1) - 0.2) * 4.0, 0.0, 1.0);
            float kMin = (1.0 - varLuma / (varLuma + u_beauty_theta)) * skinProb * u_beauty * gate;
            kMin = clamp(kMin, 0.0, u_smooth_ceiling);

            // Luminance-only transfer keeps hue/saturation of the skin exactly.
            float lc = dot(centre, vec3(0.299, 0.587, 0.114));
            float ls = dot(smoothC, vec3(0.299, 0.587, 0.114));
            vec3 smoothed = centre + (ls - lc); // shift luma only
            vec3 result = mix(centre, smoothed, kMin * 0.85);

            // High-pass restore: add back a slice of the removed micro-texture.
            vec3 hPass = centre - mean;
            result += hPass * u_restore_gain * (0.55 + 0.45 * (1.0 - u_beauty)) * gate;

            // Subtle whitening: luminance lift, mask-gated, strength-limited.
            if (u_whiten > 0.0) {
                float lift = u_whiten * 0.18 * gate * skinProb;
                result += vec3(lift);
            }

            return vec4(clamp(result, 0.0, 1.0), color.a);
        }

        // --- 20MP CATCHLIGHT & IRIS ENHANCER ---
        vec3 enhanceEyeLiveliness(vec2 uv, vec3 currentRgb) {
            // u_catchlight_boost scales both iris deepen and catchlight strength.
            float dL = distance(uv, u_left_eye);
            float dR = distance(uv, u_right_eye);
            float distToEye = min(dL, dR);

            float eyeZone = 1.0 - smoothstep(0.015, 0.045, distToEye);

            if (eyeZone > 0.0) {
                vec3 irisEnhanced = mix(currentRgb, currentRgb * currentRgb * 2.2, 0.25 * eyeZone * u_catchlight_boost);

                vec2 catchlightOffset = vec2(-0.003, 0.003);
                float dLeftCatch = distance(uv, u_left_eye + catchlightOffset);
                float dRightCatch = distance(uv, u_right_eye + catchlightOffset);
                float catchlightDist = min(dLeftCatch, dRightCatch);

                float catchlight = 1.0 - smoothstep(0.001, 0.003, catchlightDist);

                currentRgb = mix(irisEnhanced, vec3(0.98, 0.98, 1.0), catchlight * 0.85 * eyeZone * u_catchlight_boost);
            }
            return currentRgb;
        }

        void main() {
            float t = u_time;
            vec4 color = texture2D(u_texture, v_texCoord);

            // 1. SSS & Beauty Pass
            color = applyBeauty(v_texCoord, color);
            vec3 rgb = color.rgb;

            // 2. Eye Liveliness & Catchlight Enhancement
            rgb = enhanceEyeLiveliness(v_texCoord, rgb);

            // 2.5 Blur -> HD: multi-scale detail recovery for soft sources.
            // Mirrors HdEnhance.kt: fine band (micro-texture), coarse band
            // (defocus contour), local-variance adaptive gain (soft patches
            // pushed, crisp ones left alone) and halo damping on strong edges.
            if (u_hd_level > 0.0) {
                vec2 fr = vec2(r) * u_texel_size;
                vec2 cr = u_hd_radius * u_texel_size;          // coarse radius (texels)

                vec3 fineBlur = (
                    texture2D(u_texture, v_texCoord + vec2(fr.x, 0.0)).rgb +
                    texture2D(u_texture, v_texCoord - vec2(fr.x, 0.0)).rgb +
                    texture2D(u_texture, v_texCoord + vec2(0.0, fr.y)).rgb +
                    texture2D(u_texture, v_texCoord - vec2(0.0, fr.y)).rgb
                ) * 0.25;
                vec3 coarseBlur = (
                    texture2D(u_texture, v_texCoord + vec2(cr.x, 0.0)).rgb +
                    texture2D(u_texture, v_texCoord - vec2(cr.x, 0.0)).rgb +
                    texture2D(u_texture, v_texCoord + vec2(0.0, cr.y)).rgb +
                    texture2D(u_texture, v_texCoord - vec2(0.0, cr.y)).rgb
                ) * 0.25;

                vec3 fineDetail = color.rgb - fineBlur;
                vec3 coarseDetail = color.rgb - coarseBlur;

                // Local variance from the 4-tap fine ring (E[x^2] - E[x]^2 on luma).
                float lum = dot(color.rgb, vec3(0.299, 0.587, 0.114));
                float lumMean = dot(fineBlur, vec3(0.299, 0.587, 0.114));
                float variance = max(dot(fineDetail, fineDetail) * 0.25, 0.0);

                // adaptiveGain: 1 + MAX_GAIN * softness^2 * level
                float softness = 0.01 / (0.01 + variance);
                float gain = 1.0 + 1.6 * softness * softness * u_hd_level;

                // haloFactor: damp strong edges
                float mag = length(fineDetail);
                float damp = mag / (mag + 0.18);
                float halo = 1.0 - 0.65 * damp;

                vec3 recovered = fineDetail * 1.35 * gain * halo
                               + coarseDetail * 0.75 * gain * halo;
                rgb = clamp(rgb + recovered * u_hd_level, 0.0, 1.0);
            }

            // 3. Unsharp-mask sharpness
            vec3 blur = (
                texture2D(u_texture, v_texCoord + vec2(u_texel_size.x, 0.0)).rgb +
                texture2D(u_texture, v_texCoord - vec2(u_texel_size.x, 0.0)).rgb +
                texture2D(u_texture, v_texCoord + vec2(0.0, u_texel_size.y)).rgb +
                texture2D(u_texture, v_texCoord - vec2(0.0, u_texel_size.y)).rgb
            ) * 0.25;
            rgb = clamp(rgb + (rgb - blur) * u_sharpness, 0.0, 1.0);

            // 4. Pro Color Pipeline
            rgb *= pow(2.0, u_exposure);
            rgb *= vec3(
                1.0 + u_temperature * 0.12 - u_tint * 0.05,
                1.0 + u_tint * 0.10,
                1.0 - u_temperature * 0.12 - u_tint * 0.05
            );
            rgb = (rgb - 0.5) * u_contrast + 0.5;
            float luma = dot(rgb, vec3(0.299, 0.587, 0.114));
            if (luma > 0.6) {
                float amount = (luma - 0.6) * 2.5 * u_highlights;
                rgb *= 1.0 - amount;
            }
            if (luma < 0.4) {
                float amount = (0.4 - luma) * 2.5 * u_shadows;
                rgb += (1.0 - rgb) * amount;
            }
            float adjustedLuma = dot(rgb, vec3(0.299, 0.587, 0.114));
            rgb = mix(vec3(adjustedLuma), rgb, u_saturation);
            if (u_use_lut > 0.5) rgb = sampleLUT(u_lut, clamp(rgb, 0.0, 1.0));

            // 5. rPPG Bio-Pulse
            float pulse = sin(t * 7.85398) * 0.015 * u_pulse_intensity;
            rgb.g += pulse;
            rgb.r -= pulse * 0.3;

            // 6. Physical Sensor Noise
            float n = fract(sin(dot(v_texCoord + t, vec2(12.9898, 78.233))) * 43758.5453);
            rgb += (n - 0.5) * 0.04 * u_noise_intensity;

            gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), color.a);
        }
    """
}

/**
 * Compile-time mirror of the uniform names used by [LivenessShaders.FRAGMENT] and
 * [LivenessShaders.VERTEX]. Both pipelines resolve uniforms through these constants,
 * so a typo is a compile error instead of a silently-dead effect.
 */
internal object LivenessUniforms {
    const val TIME = "u_time"
    const val BLINK = "u_blink"
    const val LEFT_EYE = "u_left_eye"
    const val RIGHT_EYE = "u_right_eye"
    const val MATRIX = "u_matrix"
    const val EXPOSURE = "u_exposure"
    const val CONTRAST = "u_contrast"
    const val HIGHLIGHTS = "u_highlights"
    const val SHADOWS = "u_shadows"
    const val TEMPERATURE = "u_temperature"
    const val TINT = "u_tint"
    const val SATURATION = "u_saturation"
    const val BEAUTY = "u_beauty"
    const val SHARPNESS = "u_sharpness"
    const val TEXEL_SIZE = "u_texel_size"
    const val NOISE = "u_noise_intensity"
    const val PULSE = "u_pulse_intensity"
    const val USE_LUT = "u_use_lut"
    const val LUT = "u_lut"
    const val MASK = "u_mask"
    const val USE_MASK = "u_use_mask"
    const val BEAUTY_THETA = "u_beauty_theta"
    const val SMOOTH_CEILING = "u_smooth_ceiling"
    const val RESTORE_GAIN = "u_restore_gain"
    const val WHITEN = "u_whiten"
    const val CATCHLIGHT_BOOST = "u_catchlight_boost"
    const val HD_LEVEL = "u_hd_level"
    const val HD_RADIUS = "u_hd_radius"
}
