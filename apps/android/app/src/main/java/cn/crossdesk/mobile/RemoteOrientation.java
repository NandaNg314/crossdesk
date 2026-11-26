package cn.crossdesk.mobile;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.ActivityInfo;

/**
 * Applies the session orientation policy. Supports manual in-session switching
 * across Landscape / Portrait / Auto on both phones and tablets.
 */
final class RemoteOrientation {
    static final int MODE_AUTO = 0;
    static final int MODE_LANDSCAPE = 1;
    static final int MODE_PORTRAIT = 2;

    private final Activity activity;
    private boolean autoLandscape;
    private int mode = MODE_AUTO;

    RemoteOrientation(Activity activity) {
        this.activity = activity;
    }

    void begin(boolean autoLandscape, boolean fullScreenPhone) {
        this.autoLandscape = autoLandscape;
        this.mode = (autoLandscape && fullScreenPhone) ? MODE_LANDSCAPE : MODE_AUTO;
        apply();
    }

    void beginWithMode(int savedMode, boolean autoLandscape) {
        this.autoLandscape = autoLandscape;
        this.mode = (savedMode >= MODE_AUTO && savedMode <= MODE_PORTRAIT) ? savedMode : MODE_AUTO;
        apply();
    }

    boolean autoLandscape() { return autoLandscape; }

    int mode() { return mode; }

    int cycleMode() {
        mode = switch (mode) {
            case MODE_LANDSCAPE -> MODE_PORTRAIT;
            case MODE_PORTRAIT -> MODE_AUTO;
            default -> MODE_LANDSCAPE;
        };
        apply();
        return mode;
    }

    void setMode(int nextMode) {
        mode = (nextMode >= MODE_AUTO && nextMode <= MODE_PORTRAIT) ? nextMode : MODE_AUTO;
        apply();
    }

    String modeLabel() {
        return switch (mode) {
            case MODE_LANDSCAPE -> L10n.tr("横屏");
            case MODE_PORTRAIT -> L10n.tr("竖屏");
            default -> L10n.tr("自动");
        };
    }

    void end() {
        autoLandscape = false;
        mode = MODE_AUTO;
    }

    void update(boolean fullScreenPhone) {
        apply();
    }

    @SuppressLint("SourceLockedOrientationActivity")
    private void apply() {
        int orientation = switch (mode) {
            case MODE_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
            case MODE_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
            default -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR;
        };
        if (activity.getRequestedOrientation() != orientation) {
            activity.setRequestedOrientation(orientation);
        }
    }
}
