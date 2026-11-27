package cn.crossdesk.mobile;

import android.content.SharedPreferences;

/**
 * Deterministic pointer ballistics, fractional wheel scroll accumulation, coupled virtual mouse
 * offset geometry, and SharedPreferences persistence for UU-style mouse & touch interaction.
 */
final class PointerBallistics {
    static final String KEY_RELATIVE_MOUSE = "relativeMouse";
    static final String KEY_POINTER_SENSITIVITY = "pointerSensitivityV3";
    static final String KEY_POINTER_ACCELERATION = "pointerAcceleration";
    static final String KEY_NATURAL_SCROLL = "naturalScroll";
    static final String KEY_VIRTUAL_MOUSE_RELATIVE = "virtualMouseRelative";
    static final String KEY_VIRTUAL_MOUSE_DIRECT = "virtualMouseDirect";
    static final String KEY_VIRTUAL_MOUSE_EXPANDED = "virtualMouseExpanded";
    static final String KEY_VIRTUAL_MOUSE_NORM_X = "virtualMouseNormX";
    static final String KEY_VIRTUAL_MOUSE_NORM_Y = "virtualMouseNormY";

    static final float MIN_SENSITIVITY = 0.25f;
    static final float MAX_SENSITIVITY = 2.00f;
    static final float DEFAULT_SENSITIVITY = 1.00f;
    static final int SLIDER_MIN_PCT = 25;
    static final int SLIDER_STEP_PCT = 5;
    static final int SLIDER_MAX_PROGRESS = 35; // 25% .. 200%, default 100% -> progress 15
    static final int SLIDER_DEFAULT_PROGRESS = 15;

    // Base physical trackpad ratio at 100% user sensitivity, calibrated to UU Remote on 120/144Hz tablets.
    static final float TRACKPAD_BASE_RATIO = 0.80f;
    static final boolean DEFAULT_ACCELERATION = true;
    static final boolean DEFAULT_NATURAL_SCROLL = true;

    static final float TAP_DEADZONE_DP = 2.0f;
    static final float SLOW_NUDGE_DEADZONE_DP = 0.6f;
    static final long TAP_STABILIZE_MS = 115L;
    static final float VIRTUAL_BUTTON_DEADZONE_DP = 4.0f;
    static final float SCROLL_STEP_DP = 12.5f;

    static final float MIN_DT_MS = 8f;
    static final float MAX_DT_MS = 50f;
    static final float FALLBACK_DT_MS = 16f;
    static final float SPEED_EMA_ALPHA = 0.42f;

    private static final float SLOW_SPEED_DP_PER_MS = 0.22f;
    private static final float CRUISE_SPEED_DP_PER_MS = 0.85f;
    private static final float FAST_SPEED_DP_PER_MS = 2.15f;
    private static final float MIN_ACCEL_GAIN = 0.72f;
    private static final float KNEE_ACCEL_GAIN = 0.96f;
    private static final float CRUISE_ACCEL_GAIN = 1.42f;
    private static final float MAX_ACCEL_GAIN = 1.95f;

    private static final float PALM_ASSIST_START_DP_PER_MS = 0.55f;
    private static final float PALM_ASSIST_FAST_DP_PER_MS = 2.00f;
    private static final float PALM_ASSIST_MAX_GAIN = 1.55f;

    private PointerBallistics() {}

    static float clampSensitivity(float value) {
        if (!Float.isFinite(value)) return DEFAULT_SENSITIVITY;
        return Math.max(MIN_SENSITIVITY, Math.min(MAX_SENSITIVITY, value));
    }

    static int sensitivityPercent(float value) {
        return Math.round(clampSensitivity(value) * 100f);
    }

    static float clampDtMs(long dtMs) {
        if (dtMs <= 0L) return FALLBACK_DT_MS;
        return Math.max(MIN_DT_MS, Math.min(MAX_DT_MS, (float) dtMs));
    }

    static float smoothSpeedDpPerMs(float prevSpeedDpPerMs, float distanceDp, long dtMs) {
        if (!Float.isFinite(distanceDp) || distanceDp <= 0f) {
            return Float.isFinite(prevSpeedDpPerMs) ? prevSpeedDpPerMs * (1f - SPEED_EMA_ALPHA) : 0f;
        }
        float instant = distanceDp / clampDtMs(dtMs);
        if (!Float.isFinite(prevSpeedDpPerMs) || prevSpeedDpPerMs <= 0f) {
            return instant;
        }
        return SPEED_EMA_ALPHA * instant + (1f - SPEED_EMA_ALPHA) * prevSpeedDpPerMs;
    }

    /**
     * Three-stage velocity-adaptive pointer gain (applied on top of TRACKPAD_BASE_RATIO * sensitivity):
     * - 0 .. 0.22 dp/ms: 0.72x..0.96x (net 0.58x..0.77x) sub-pixel precision for tiny buttons & text carets.
     * - 0.22 .. 0.85 dp/ms: smooth Hermite ramp 0.96x..1.42x (crosses exact 1.0x finger tracking at ~0.52 dp/ms).
     * - 0.85 .. 2.15 dp/ms: smooth Hermite ramp 1.42x..1.95x (net ~1.56x) for effortless cross-screen flicks.
     */
    static float accelerationGainFromSpeed(float speedDpPerMs) {
        if (!Float.isFinite(speedDpPerMs) || speedDpPerMs <= 0f) return MIN_ACCEL_GAIN;
        if (speedDpPerMs <= SLOW_SPEED_DP_PER_MS) {
            return MIN_ACCEL_GAIN + (speedDpPerMs / SLOW_SPEED_DP_PER_MS) * (KNEE_ACCEL_GAIN - MIN_ACCEL_GAIN);
        }
        if (speedDpPerMs <= CRUISE_SPEED_DP_PER_MS) {
            float t = (speedDpPerMs - SLOW_SPEED_DP_PER_MS) / (CRUISE_SPEED_DP_PER_MS - SLOW_SPEED_DP_PER_MS);
            float smooth = t * t * (3f - 2f * t);
            return KNEE_ACCEL_GAIN + smooth * (CRUISE_ACCEL_GAIN - KNEE_ACCEL_GAIN);
        }
        if (speedDpPerMs >= FAST_SPEED_DP_PER_MS) {
            return MAX_ACCEL_GAIN;
        }
        float t = (speedDpPerMs - CRUISE_SPEED_DP_PER_MS) / (FAST_SPEED_DP_PER_MS - CRUISE_SPEED_DP_PER_MS);
        float smooth = t * t * (3f - 2f * t);
        return CRUISE_ACCEL_GAIN + smooth * (MAX_ACCEL_GAIN - CRUISE_ACCEL_GAIN);
    }

    /**
     * Multiplier when dragging the coupled VirtualMouseBar directly:
     * stays strictly 1.0x at slow/medium speeds so the mouse stays glued under the user's thumb,
     * and gently assists brisk flicks up to 1.55x so one thumb can throw the mouse across wide tablets.
     */
    static float palmDragMultiplier(float speedDpPerMs, float sensitivity, boolean acceleration) {
        float sensScale = 0.35f + 0.65f * clampSensitivity(sensitivity);
        if (!acceleration || !Float.isFinite(speedDpPerMs) || speedDpPerMs <= PALM_ASSIST_START_DP_PER_MS) {
            return 1.0f;
        }
        if (speedDpPerMs >= PALM_ASSIST_FAST_DP_PER_MS) {
            return 1.0f + (PALM_ASSIST_MAX_GAIN - 1.0f) * sensScale;
        }
        float t = (speedDpPerMs - PALM_ASSIST_START_DP_PER_MS)
                / (PALM_ASSIST_FAST_DP_PER_MS - PALM_ASSIST_START_DP_PER_MS);
        float smooth = t * t * (3f - 2f * t);
        return 1.0f + smooth * (PALM_ASSIST_MAX_GAIN - 1.0f) * sensScale;
    }

    static float accelerationGain(float distanceDp, long dtMs) {
        if (!Float.isFinite(distanceDp) || distanceDp <= 0f) return MIN_ACCEL_GAIN;
        return accelerationGainFromSpeed(distanceDp / clampDtMs(dtMs));
    }

    static float effectiveMultiplierForSpeed(float speedDpPerMs, float sensitivity, boolean acceleration) {
        float base = TRACKPAD_BASE_RATIO * clampSensitivity(sensitivity);
        if (!acceleration) return base;
        return base * accelerationGainFromSpeed(speedDpPerMs);
    }

    static float effectiveMultiplier(float dxPx, float dyPx, float density, long dtMs,
                                     float sensitivity, boolean acceleration) {
        float safeDensity = density > 0f && Float.isFinite(density) ? density : 1f;
        float distDp = (float) Math.hypot(dxPx, dyPx) / safeDensity;
        float speed = distDp > 0f ? distDp / clampDtMs(dtMs) : 0f;
        return effectiveMultiplierForSpeed(speed, sensitivity, acceleration);
    }

    static float scaleDeltaX(float dxPx, float dyPx, float density, long dtMs,
                             float sensitivity, boolean acceleration) {
        if (!Float.isFinite(dxPx) || !Float.isFinite(dyPx)) return 0f;
        return dxPx * effectiveMultiplier(dxPx, dyPx, density, dtMs, sensitivity, acceleration);
    }

    static float scaleDeltaY(float dxPx, float dyPx, float density, long dtMs,
                             float sensitivity, boolean acceleration) {
        if (!Float.isFinite(dxPx) || !Float.isFinite(dyPx)) return 0f;
        return dyPx * effectiveMultiplier(dxPx, dyPx, density, dtMs, sensitivity, acceleration);
    }

    /**
     * Computes coupled VirtualMouseBar top-left coordinates rigidly anchored beside the cursor hotspot.
     * Like UU Remote, the virtual mouse body is NOT artificially trapped inside the screen borders:
     * when the cursor reaches the right/bottom edges or taskbar, the mouse body smoothly slides
     * partially or fully outside the screen edge so it never detaches from or covers the cursor tip.
     */
    static float coupledMouseCoord(float cursorCoord, float offsetPx) {
        if (!Float.isFinite(cursorCoord) || !Float.isFinite(offsetPx)) return 0f;
        return cursorCoord + offsetPx;
    }

    static float coupledMouseCoord(float cursorCoord, float offsetPx, float barSize,
                                   float minBound, float maxLimit) {
        return coupledMouseCoord(cursorCoord, offsetPx);
    }

    static float loadSensitivity(SharedPreferences prefs) {
        return prefs == null ? DEFAULT_SENSITIVITY
                : clampSensitivity(prefs.getFloat(KEY_POINTER_SENSITIVITY, DEFAULT_SENSITIVITY));
    }

    static void saveSensitivity(SharedPreferences prefs, float sensitivity) {
        if (prefs != null) {
            prefs.edit().putFloat(KEY_POINTER_SENSITIVITY, clampSensitivity(sensitivity)).apply();
        }
    }

    static boolean loadAcceleration(SharedPreferences prefs) {
        return prefs == null ? DEFAULT_ACCELERATION
                : prefs.getBoolean(KEY_POINTER_ACCELERATION, DEFAULT_ACCELERATION);
    }

    static void saveAcceleration(SharedPreferences prefs, boolean enabled) {
        if (prefs != null) {
            prefs.edit().putBoolean(KEY_POINTER_ACCELERATION, enabled).apply();
        }
    }

    static boolean loadNaturalScroll(SharedPreferences prefs) {
        return prefs == null ? DEFAULT_NATURAL_SCROLL
                : prefs.getBoolean(KEY_NATURAL_SCROLL, DEFAULT_NATURAL_SCROLL);
    }

    static void saveNaturalScroll(SharedPreferences prefs, boolean natural) {
        if (prefs != null) {
            prefs.edit().putBoolean(KEY_NATURAL_SCROLL, natural).apply();
        }
    }

    static boolean loadVirtualMouseVisible(SharedPreferences prefs, boolean relativeMode) {
        if (prefs == null) return relativeMode;
        return relativeMode
                ? prefs.getBoolean(KEY_VIRTUAL_MOUSE_RELATIVE, true)
                : prefs.getBoolean(KEY_VIRTUAL_MOUSE_DIRECT, false);
    }

    static void saveVirtualMouseVisible(SharedPreferences prefs, boolean relativeMode, boolean visible) {
        if (prefs == null) return;
        prefs.edit().putBoolean(relativeMode ? KEY_VIRTUAL_MOUSE_RELATIVE : KEY_VIRTUAL_MOUSE_DIRECT, visible).apply();
    }

    /**
     * Preserves sub-step pixel remainder across touch move events for smooth wheel scrolling.
     * Positive return value = emit N ticks of wheel UP (+1); negative = emit |N| ticks of wheel DOWN (-1).
     */
    static final class ScrollAccumulator {
        private static final int MAX_TICKS_PER_STEP = 6;
        private float remainderPx;

        void reset() {
            remainderPx = 0f;
        }

        float remainder() {
            return remainderPx;
        }

        int consume(float dyPx, float stepPx, boolean naturalScroll) {
            if (!Float.isFinite(dyPx) || !Float.isFinite(stepPx) || stepPx <= 0.5f || dyPx == 0f) {
                return 0;
            }
            remainderPx += dyPx;
            int rawSteps = (int) (remainderPx / stepPx);
            if (rawSteps == 0) return 0;
            remainderPx -= rawSteps * stepPx;
            int clamped = Math.max(-MAX_TICKS_PER_STEP, Math.min(MAX_TICKS_PER_STEP, rawSteps));
            // dyPx > 0 means fingers moved DOWN:
            // - naturalScroll (content follows fingers): page moves DOWN -> wheel UP (+1)
            // - standard wheel (fingers roll wheel down): page scrolls DOWN -> wheel DOWN (-1)
            return naturalScroll ? clamped : -clamped;
        }
    }
}
