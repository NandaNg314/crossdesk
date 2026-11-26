package cn.crossdesk.mobile;

import android.content.SharedPreferences;

/**
 * Deterministic pointer ballistics, fractional wheel scroll accumulation, coupled virtual mouse
 * offset geometry, and SharedPreferences persistence for UU-style mouse & touch interaction.
 */
final class PointerBallistics {
    static final String KEY_RELATIVE_MOUSE = "relativeMouse";
    static final String KEY_POINTER_SENSITIVITY = "pointerSensitivityV2";
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
    static final float TRACKPAD_BASE_RATIO = 0.56f;
    static final boolean DEFAULT_ACCELERATION = true;
    static final boolean DEFAULT_NATURAL_SCROLL = true;

    static final float TAP_DEADZONE_DP = 2.5f;
    static final float SLOW_NUDGE_DEADZONE_DP = 1.0f;
    static final long TAP_STABILIZE_MS = 130L;
    static final float VIRTUAL_BUTTON_DEADZONE_DP = 4.5f;
    static final float SCROLL_STEP_DP = 13.0f;

    static final float MIN_DT_MS = 8f;
    static final float MAX_DT_MS = 50f;
    static final float FALLBACK_DT_MS = 16f;
    static final float SPEED_EMA_ALPHA = 0.30f;

    private static final float SLOW_SPEED_DP_PER_MS = 0.30f;
    private static final float FAST_SPEED_DP_PER_MS = 1.90f;
    private static final float MIN_ACCEL_GAIN = 0.78f;
    private static final float KNEE_ACCEL_GAIN = 0.92f;
    private static final float MAX_ACCEL_GAIN = 1.65f;

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
     * Velocity-adaptive pointer gain (applied on top of TRACKPAD_BASE_RATIO * sensitivity):
     * - Below 0.30 dp/ms: 0.78x..0.92x calm precision for tiny close buttons & text carets.
     * - 0.30..1.90 dp/ms: smooth Hermite ramp up to 1.65x (net ~0.92x at 100% sensitivity) for controlled flicks.
     * - Clamped dt in [8ms, 50ms] prevents spikes on 144Hz/240Hz coalesced events.
     */
    static float accelerationGainFromSpeed(float speedDpPerMs) {
        if (!Float.isFinite(speedDpPerMs) || speedDpPerMs <= 0f) return MIN_ACCEL_GAIN;
        if (speedDpPerMs <= SLOW_SPEED_DP_PER_MS) {
            return MIN_ACCEL_GAIN + (speedDpPerMs / SLOW_SPEED_DP_PER_MS) * (KNEE_ACCEL_GAIN - MIN_ACCEL_GAIN);
        }
        if (speedDpPerMs >= FAST_SPEED_DP_PER_MS) {
            return MAX_ACCEL_GAIN;
        }
        float t = (speedDpPerMs - SLOW_SPEED_DP_PER_MS) / (FAST_SPEED_DP_PER_MS - SLOW_SPEED_DP_PER_MS);
        float smooth = t * t * (3f - 2f * t);
        return KNEE_ACCEL_GAIN + smooth * (MAX_ACCEL_GAIN - KNEE_ACCEL_GAIN);
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
     * Computes coupled VirtualMouseBar top-left coordinates anchored beside the cursor hotspot
     * while clamping cleanly inside container bounds so the cursor reaches all 4 screen corners.
     */
    static float coupledMouseCoord(float cursorCoord, float offsetPx, float barSize,
                                   float minBound, float maxLimit) {
        float upper = Math.max(minBound, maxLimit - barSize);
        float target = cursorCoord + offsetPx;
        return Math.max(minBound, Math.min(upper, target));
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
