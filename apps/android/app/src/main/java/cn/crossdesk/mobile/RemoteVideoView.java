package cn.crossdesk.mobile;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import java.util.function.Consumer;

@SuppressLint("ViewConstructor") // Constructed only with a live session, never from XML.
final class RemoteVideoView extends FrameLayout implements SurfaceHolder.Callback {
    interface CursorPositionListener {
        void onCursorPositionChanged(float screenX, float screenY, boolean fromScreenTouch);
    }

    private static final long LONG_PRESS_TIMEOUT_MS = 430L;
    private static final long DOUBLE_TAP_WINDOW_MS = 280L;
    private static final long TAP_DRAG_HOLD_MS = 180L;
    private static final long TWO_FINGER_TAP_MAX_TOTAL_MS = 280L;
    private static final long TWO_FINGER_TAP_MAX_PAIR_MS = 260L;
    private static final long DIRECT_CURSOR_HIDE_DELAY_MS = 1200L;
    private static final long CLICK_PULSE_MS = 150L;

    private final SurfaceView video;
    private final Cursor cursor;
    private final NativeSession session;
    final RemoteMouseInput mouseInput;
    Runnable remoteFocusChanged;
    CursorPositionListener cursorPositionListener;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float density;
    private final float touchSlopPx;
    private final float pinchSlop;
    private final float doubleTapSlopPx;
    private final float tapDragSlopPx;
    private final float twoFingerTapSlopPx;

    private final RemoteViewport viewport = new RemoteViewport();
    private final RemoteViewport pictureInPictureViewport = new RemoteViewport();
    private final PointerBallistics.ScrollAccumulator scrollAccumulator = new PointerBallistics.ScrollAccumulator();
    private final PointerBallistics.ScrollAccumulator hardwareWheelAccumulator = new PointerBallistics.ScrollAccumulator();

    private boolean pictureInPicture;
    private int frameWidth = 16, frameHeight = 9;
    private float x = .5f, y = .5f;
    private boolean dragging, twoFinger, relative = true, pinching, viewportPanActive, viewOnly;
    private boolean forceCursorVisible;
    private boolean hardwareHovering;
    private int hardwareButtonState;

    private float pointerSensitivity = PointerBallistics.DEFAULT_SENSITIVITY;
    private boolean pointerAcceleration = PointerBallistics.DEFAULT_ACCELERATION;
    private boolean naturalScroll = PointerBallistics.DEFAULT_NATURAL_SCROLL;

    // Single-finger & tap-to-drag state
    private float downX, downY, lastX, lastY;
    private long downTimeMs, lastMoveTimeMs;
    private boolean relativeMoveUnlocked;
    private boolean directTouchValid;
    private boolean longPressTriggered;
    private boolean tapDragCandidate;
    private long lastSingleTapUpTimeMs;
    private float lastSingleTapX, lastSingleTapY;

    // Two-finger scroll / pinch / right-click tap state
    private float twoFingerX, twoFingerY, pinchSpan;
    private boolean twoFingerScrolling;
    private boolean twoFingerTapCandidate;
    private int maxPointersInGesture;
    private long twoFingerDownTimeMs;
    private float p0StartX, p0StartY, p1StartX, p1StartY;
    private float twoFingerStartMidX, twoFingerStartMidY;

    private long touchRevision;
    private boolean suppressPointerGesture;

    // Cursor visibility & click feedback state
    private float cursorAlpha = 1f;
    private long clickPulseUntilMs;
    private ValueAnimator cursorFadeAnimator;

    private final Runnable longPressRunnable = this::handleLongPressTimeout;
    private final Runnable tapDragHoldRunnable = this::handleTapDragHoldTimeout;
    private final Runnable autoHideCursorRunnable = this::fadeOutDirectCursor;

    RemoteVideoView(Context context, NativeSession session) {
        super(context);
        this.session = session;
        density = context.getResources().getDisplayMetrics().density;
        ViewConfiguration vc = ViewConfiguration.get(context);
        touchSlopPx = vc.getScaledTouchSlop();
        pinchSlop = touchSlopPx * 0.5f;
        doubleTapSlopPx = Math.max(touchSlopPx * 3f, 36f * density);
        tapDragSlopPx = Math.max(3.5f * density, touchSlopPx * 0.45f);
        twoFingerTapSlopPx = 10f * density;

        reloadMousePreferences(context.getSharedPreferences("settings", Context.MODE_PRIVATE));

        mouseInput = new RemoteMouseInput(this::sendMouse);
        mouseInput.relativeMotion = (dx, dy, dtMs) -> moveRelativeBallistic(dx, dy, dtMs, false);

        setBackgroundColor(Color.BLACK);
        video = new SurfaceView(context);
        video.getHolder().addCallback(this);
        addView(video, new LayoutParams(1, 1, Gravity.CENTER));
        cursor = new Cursor(context);
        // Keep input in container coordinates so transforms never move its hit area.
        addView(cursor, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        mouseInput.buttonStateChanged = () -> {
            onPointerActivity();
            cursor.invalidate();
        };

        cursor.setOnHoverListener((v, event) -> handleHover(event));
        cursor.setOnGenericMotionListener((v, event) -> handleGenericMotion(event));
        setContentDescription(L10n.tr("远程桌面：轻触点击，拖动移动，长按右键，双指捏合缩放，放大后双指拖动画面，原始比例下双指上下滑动滚动"));
    }

    private void handleLongPressTimeout() {
        if (viewOnly || twoFinger || dragging || tapDragCandidate || longPressTriggered || suppressPointerGesture()) {
            return;
        }
        if (!relative) {
            if (!directTouchValid || !viewport.contains(downX, downY)) return;
            x = viewport.normalizedX(downX);
            y = viewport.normalizedY(downY);
            mouseInput.move(x, y);
            notifyCursorPosition(true);
        }
        longPressTriggered = true;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        pulseCursor();
        click(3, 4);
        onPointerActivity();
    }

    private void handleTapDragHoldTimeout() {
        if (viewOnly || !relative || twoFinger || dragging || !tapDragCandidate || suppressPointerGesture()) {
            return;
        }
        mouseInput.gestureLeft(true);
        dragging = true;
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        pulseCursor();
    }

    void reloadMousePreferences(SharedPreferences prefs) {
        pointerSensitivity = PointerBallistics.loadSensitivity(prefs);
        pointerAcceleration = PointerBallistics.loadAcceleration(prefs);
        naturalScroll = PointerBallistics.loadNaturalScroll(prefs);
    }

    void setRelative(boolean value) {
        releaseMouse();
        relative = value;
        lastSingleTapUpTimeMs = 0L;
        if (relative || forceCursorVisible || hardwareHovering) {
            showCursorImmediately();
        } else {
            scheduleDirectCursorHide(260L);
        }
        notifyCursorPosition(false);
    }

    void setForceCursorVisible(boolean value) {
        if (forceCursorVisible == value) return;
        forceCursorVisible = value;
        if (relative || forceCursorVisible || hardwareHovering) {
            showCursorImmediately();
        } else {
            scheduleDirectCursorHide(DIRECT_CURSOR_HIDE_DELAY_MS);
        }
    }

    float cursorScreenX() {
        return viewport.left() + x * viewport.width();
    }

    float cursorScreenY() {
        return viewport.top() + y * viewport.height();
    }

    boolean isViewOnly() { return viewOnly; }

    void setViewOnly(boolean value) {
        if (viewOnly == value) return;
        releaseMouse();
        viewOnly = value;
        cursor.invalidate();
    }

    void setPictureInPicture(boolean value) {
        if (pictureInPicture == value) return;
        releaseMouse();
        pictureInPicture = value;
        cursor.setVisibility(value ? GONE : VISIBLE);
        fit();
    }

    Rect pictureInPictureBounds() {
        Rect bounds = new Rect();
        video.getGlobalVisibleRect(bounds);
        return bounds;
    }

    void releaseMouse() {
        cancelTimers();
        syncHardwareMouseButtons(0);
        mouseInput.releaseAll();
        dragging = false;
        tapDragCandidate = false;
        twoFingerTapCandidate = false;
        twoFingerScrolling = false;
        scrollAccumulator.reset();
        hardwareWheelAccumulator.reset();
        suppressPointerGesture = true;
    }

    private void cancelTimers() {
        handler.removeCallbacks(longPressRunnable);
        handler.removeCallbacks(tapDragHoldRunnable);
    }

    private boolean suppressPointerGesture() {
        return suppressPointerGesture || mouseInput.hasVirtualButton() || touchRevision != mouseInput.revision();
    }

    void videoSize(int width, int height) {
        if (width <= 0 || height <= 0) return;
        frameWidth = width;
        frameHeight = height;
        fit();
    }

    void resetViewport() {
        releaseMouse();
        viewport.reset();
        applyViewport();
    }

    void setKeyboardInset(int inset) {
        if (viewport.setKeyboardInset(inset)) applyViewport();
    }

    static Rect previewCrop(int width, int height) {
        int w = width, h = height;
        if ((long) width * RemotePreviewStore.HEIGHT > (long) height * RemotePreviewStore.WIDTH)
            w = Math.max(1, height * RemotePreviewStore.WIDTH / RemotePreviewStore.HEIGHT);
        else
            h = Math.max(1, width * RemotePreviewStore.HEIGHT / RemotePreviewStore.WIDTH);
        return new Rect((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2);
    }

    void capturePreview(Consumer<Bitmap> completion) {
        // Copy only the decoded-video Surface, excluding cursor, controls, keyboard and black margins.
        if (!isAttachedToWindow() || !video.getHolder().getSurface().isValid()) {
            completion.accept(null);
            return;
        }
        Bitmap image = Bitmap.createBitmap(RemotePreviewStore.WIDTH, RemotePreviewStore.HEIGHT, Bitmap.Config.ARGB_8888);
        try {
            PixelCopy.request(video.getHolder().getSurface(), previewCrop(frameWidth, frameHeight), image, result -> {
                if (result == PixelCopy.SUCCESS) completion.accept(image);
                else { image.recycle(); completion.accept(null); }
            }, new Handler(Looper.getMainLooper()));
        } catch (IllegalArgumentException error) {
            image.recycle();
            completion.accept(null);
        }
    }

    private void fit() {
        if (getWidth() == 0 || getHeight() == 0) return;
        RemoteViewport active = pictureInPicture ? pictureInPictureViewport : viewport;
        active.fit(getWidth(), getHeight(), frameWidth, frameHeight);
        video.setLayoutParams(new LayoutParams(active.baseWidth(), active.baseHeight(), Gravity.CENTER));
        applyViewport();
    }

    private void applyViewport() {
        // Transform the existing Surface without enlarging its decoder buffers.
        RemoteViewport active = pictureInPicture ? pictureInPictureViewport : viewport;
        video.setScaleX(active.scale());
        video.setScaleY(active.scale());
        video.setTranslationX(active.offsetX());
        video.setTranslationY(active.offsetY());
        mouseInput.viewportWidth = active.width();
        mouseInput.viewportHeight = active.height();
        notifyCursorPosition(false);
        cursor.invalidate();
    }

    private void sendMouse(float x, float y, int flag, int wheel) {
        if (viewOnly) return;
        this.x = x;
        this.y = y;
        if ((flag == 1 || flag == 3 || flag == 5) && remoteFocusChanged != null) {
            remoteFocusChanged.run();
        }
        if (flag >= 1 && flag <= 6) {
            pulseCursor();
        }
        session.pointer(x, y, flag, wheel);
        cursor.invalidate();
    }

    private void notifyCursorPosition(boolean fromScreenTouch) {
        if (cursorPositionListener != null && viewport.width() > 0 && viewport.height() > 0) {
            cursorPositionListener.onCursorPositionChanged(cursorScreenX(), cursorScreenY(), fromScreenTouch);
        }
    }

    private void showCursorImmediately() {
        handler.removeCallbacks(autoHideCursorRunnable);
        if (cursorFadeAnimator != null) {
            cursorFadeAnimator.cancel();
            cursorFadeAnimator = null;
        }
        if (cursorAlpha != 1f) {
            cursorAlpha = 1f;
            cursor.invalidate();
        }
    }

    private void scheduleDirectCursorHide(long delayMs) {
        handler.removeCallbacks(autoHideCursorRunnable);
        if (relative || forceCursorVisible || hardwareHovering || dragging
                || mouseInput.hasAnyButton() || hardwareButtonState != 0) {
            showCursorImmediately();
            return;
        }
        handler.postDelayed(autoHideCursorRunnable, delayMs);
    }

    private void fadeOutDirectCursor() {
        if (relative || forceCursorVisible || hardwareHovering || dragging
                || mouseInput.hasAnyButton() || hardwareButtonState != 0) {
            return;
        }
        if (cursorFadeAnimator != null) cursorFadeAnimator.cancel();
        if (!ValueAnimator.areAnimatorsEnabled()) {
            cursorAlpha = 0f;
            cursor.invalidate();
            return;
        }
        ValueAnimator anim = ValueAnimator.ofFloat(cursorAlpha, 0f);
        cursorFadeAnimator = anim;
        anim.setDuration(220L);
        anim.addUpdateListener(a -> {
            if (cursorFadeAnimator != a) return;
            cursorAlpha = (float) a.getAnimatedValue();
            cursor.invalidate();
        });
        anim.start();
    }

    private void onPointerActivity() {
        showCursorImmediately();
        if (!relative && !forceCursorVisible && !hardwareHovering) {
            scheduleDirectCursorHide(DIRECT_CURSOR_HIDE_DELAY_MS);
        }
    }

    private void pulseCursor() {
        onPointerActivity();
        clickPulseUntilMs = SystemClock.uptimeMillis() + CLICK_PULSE_MS;
        cursor.invalidate();
        cursor.postInvalidateDelayed(CLICK_PULSE_MS + 16L);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        post(this::fit);
    }

    private void moveRelativeBallistic(float rawDx, float rawDy, long dtMs, boolean fromScreenTouch) {
        if (pictureInPicture || viewport.width() <= 0 || viewport.height() <= 0) return;
        if (!Float.isFinite(rawDx) || !Float.isFinite(rawDy) || (rawDx == 0f && rawDy == 0f)) return;
        float dx = PointerBallistics.scaleDeltaX(
                rawDx, rawDy, density, dtMs, pointerSensitivity, pointerAcceleration);
        float dy = PointerBallistics.scaleDeltaY(
                rawDx, rawDy, density, dtMs, pointerSensitivity, pointerAcceleration);
        if (dx == 0f && dy == 0f) return;
        float nextX = Math.max(0f, Math.min(1f, x + dx / viewport.width()));
        float nextY = Math.max(0f, Math.min(1f, y + dy / viewport.height()));
        viewport.panForPointer(nextX, nextY, y, dy, 18f * density);
        applyViewport();
        mouseInput.move(nextX, nextY);
        notifyCursorPosition(fromScreenTouch);
        onPointerActivity();
    }

    private boolean isHardwareMouse(MotionEvent event) {
        if (event.getPointerCount() > 0 && event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) {
            return true;
        }
        return (event.getSource() & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE;
    }

    private boolean usesRelativeMotion(MotionEvent event) {
        return relative && !isHardwareMouse(event);
    }

    private boolean updateAbsoluteCursor(float px, float py, boolean allowEdgePan, boolean fromScreenTouch) {
        if (viewOnly || viewport.width() <= 0 || viewport.height() <= 0) return false;
        if (allowEdgePan && (px != lastX || py != lastY)
                && viewport.panForAbsolutePointer(px, py, 18f * density)) {
            applyViewport();
        }
        x = viewport.normalizedX(px);
        y = viewport.normalizedY(py);
        lastX = px;
        lastY = py;
        mouseInput.move(x, y);
        notifyCursorPosition(fromScreenTouch);
        onPointerActivity();
        return true;
    }

    private boolean handleHover(MotionEvent event) {
        if (viewOnly) return true;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_HOVER_EXIT) {
            hardwareHovering = false;
            if (!relative && !forceCursorVisible) scheduleDirectCursorHide(DIRECT_CURSOR_HIDE_DELAY_MS);
            return true;
        }
        if (action == MotionEvent.ACTION_HOVER_ENTER || action == MotionEvent.ACTION_HOVER_MOVE) {
            hardwareHovering = true;
            showCursorImmediately();
            float px = event.getX(), py = event.getY();
            if (viewport.contains(px, py)) {
                updateAbsoluteCursor(px, py, action == MotionEvent.ACTION_HOVER_MOVE, false);
            }
        }
        return true;
    }

    private boolean handleGenericMotion(MotionEvent event) {
        if (viewOnly) return true;
        int action = event.getActionMasked();
        float px = event.getX(), py = event.getY();
        if (action == MotionEvent.ACTION_HOVER_MOVE || action == MotionEvent.ACTION_HOVER_ENTER) {
            return handleHover(event);
        }
        if (viewport.contains(px, py) && isHardwareMouse(event)) {
            updateAbsoluteCursor(px, py, true, false);
        }
        if (action == MotionEvent.ACTION_BUTTON_PRESS || action == MotionEvent.ACTION_BUTTON_RELEASE) {
            if (viewport.contains(px, py) || hardwareButtonState != 0) {
                syncHardwareMouseButtons(event.getButtonState());
            }
            return true;
        }
        if (action == MotionEvent.ACTION_SCROLL) {
            float vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if (vScroll != 0f) {
                if (Math.abs(vScroll) >= 0.95f) {
                    hardwareWheelAccumulator.reset();
                    int count = Math.min(12, Math.max(1, Math.round(Math.abs(vScroll))));
                    int dir = vScroll > 0f ? 1 : -1;
                    for (int i = 0; i < count; i++) session.pointer(x, y, 7, dir);
                } else {
                    int ticks = hardwareWheelAccumulator.consume(vScroll, 0.35f, true);
                    emitScrollTicks(ticks, false);
                }
                onPointerActivity();
            }
        }
        return true;
    }

    private boolean handleHardwareMouseTouch(MotionEvent event) {
        int action = event.getActionMasked();
        if (viewOnly) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                syncHardwareMouseButtons(0);
            }
            return true;
        }
        float px = event.getX(), py = event.getY();
        if (action == MotionEvent.ACTION_DOWN && !viewport.contains(px, py)) {
            return false;
        }
        if (viewport.contains(px, py) || hardwareButtonState != 0) {
            updateAbsoluteCursor(px, py, action == MotionEvent.ACTION_MOVE, false);
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (hardwareButtonState == 0 && action == MotionEvent.ACTION_UP && viewport.contains(px, py)) {
                // Tap-to-click on external keyboard trackpad that emits DOWN/UP with buttonState == 0
                pulseCursor();
                click(1, 2);
            } else {
                syncHardwareMouseButtons(0);
            }
        } else {
            int state = event.getButtonState();
            if (action == MotionEvent.ACTION_DOWN && state == 0) {
                // External trackpad physical press or tap without button bitmask
                state = MotionEvent.BUTTON_PRIMARY;
            }
            syncHardwareMouseButtons(state);
        }
        return true;
    }

    private void syncHardwareMouseButtons(int nextState) {
        int masked = nextState & (MotionEvent.BUTTON_PRIMARY
                | MotionEvent.BUTTON_SECONDARY | MotionEvent.BUTTON_TERTIARY);
        int diff = hardwareButtonState ^ masked;
        if (diff == 0) return;
        hardwareButtonState = masked;
        if ((diff & MotionEvent.BUTTON_PRIMARY) != 0) {
            sendMouse(x, y, (masked & MotionEvent.BUTTON_PRIMARY) != 0 ? 1 : 2, 0);
        }
        if ((diff & MotionEvent.BUTTON_SECONDARY) != 0) {
            sendMouse(x, y, (masked & MotionEvent.BUTTON_SECONDARY) != 0 ? 3 : 4, 0);
        }
        if ((diff & MotionEvent.BUTTON_TERTIARY) != 0) {
            sendMouse(x, y, (masked & MotionEvent.BUTTON_TERTIARY) != 0 ? 5 : 6, 0);
        }
        onPointerActivity();
        cursor.invalidate();
    }

    private void click(int down, int up) {
        mouseInput.click(down, up);
    }

    private void fireRightClickWithFeedback() {
        int haptic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? HapticFeedbackConstants.CONTEXT_CLICK
                : HapticFeedbackConstants.LONG_PRESS;
        performHapticFeedback(haptic);
        pulseCursor();
        click(3, 4);
    }

    private void emitScrollTicks(int signedTicks, boolean haptic) {
        if (signedTicks == 0 || viewOnly) return;
        int dir = signedTicks > 0 ? 1 : -1;
        int count = Math.abs(signedTicks);
        for (int i = 0; i < count; i++) {
            session.pointer(x, y, 7, dir);
        }
        if (haptic) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
        onPointerActivity();
    }

    private boolean touch(MotionEvent e) {
        if (isHardwareMouse(e)) {
            return handleHardwareMouseTouch(e);
        }

        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            cancelTimers();
            scrollAccumulator.reset();
            twoFinger = pinching = viewportPanActive = twoFingerScrolling = false;
            twoFingerTapCandidate = false;
            longPressTriggered = false;
            tapDragCandidate = false;
            maxPointersInGesture = 1;

            touchRevision = mouseInput.revision();
            suppressPointerGesture = mouseInput.hasVirtualButton();
            downX = lastX = e.getX();
            downY = lastY = e.getY();
            downTimeMs = lastMoveTimeMs = e.getEventTime();
            relativeMoveUnlocked = false;
            directTouchValid = viewport.contains(downX, downY);
            onPointerActivity();

            if (!viewOnly) {
                boolean stylusSecondary = e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
                        && (e.getButtonState() & (MotionEvent.BUTTON_STYLUS_PRIMARY | MotionEvent.BUTTON_SECONDARY)) != 0;
                if (stylusSecondary) {
                    if (!usesRelativeMotion(e) && directTouchValid) {
                        updateAbsoluteCursor(downX, downY, false, true);
                    }
                    if (usesRelativeMotion(e) || directTouchValid) {
                        longPressTriggered = true;
                        fireRightClickWithFeedback();
                    }
                    return true;
                }

                if (suppressPointerGesture) {
                    // Two-hand multi-touch synergy: virtual button is already held on VirtualMouseBar / KeymapOverlay.
                    if (!usesRelativeMotion(e) && directTouchValid) {
                        updateAbsoluteCursor(downX, downY, false, true);
                    }
                } else if (usesRelativeMotion(e)) {
                    // Trackpad mode: NEVER move on ACTION_DOWN so thumb taps stay pixel-accurate.
                    if (lastSingleTapUpTimeMs > 0L
                            && (downTimeMs - lastSingleTapUpTimeMs) <= DOUBLE_TAP_WINDOW_MS
                            && Math.hypot(downX - lastSingleTapX, downY - lastSingleTapY) <= doubleTapSlopPx) {
                        tapDragCandidate = true;
                        handler.postDelayed(tapDragHoldRunnable, TAP_DRAG_HOLD_MS);
                    } else {
                        handler.postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT_MS);
                    }
                } else if (directTouchValid) {
                    handler.postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT_MS);
                }
            }
            return true;
        }

        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            cancelTimers();
            tapDragCandidate = false;
            lastSingleTapUpTimeMs = 0L;
            if (dragging) {
                mouseInput.gestureLeft(false);
                dragging = false;
            }
            maxPointersInGesture = Math.max(maxPointersInGesture, e.getPointerCount());
            if (!twoFinger) {
                twoFinger = true;
                twoFingerDownTimeMs = e.getEventTime();
                scrollAccumulator.reset();
                if (e.getPointerCount() == 2 && !viewOnly && !suppressPointerGesture()
                        && (e.getEventTime() - downTimeMs) <= 180L) {
                    twoFingerTapCandidate = true;
                    p0StartX = e.getX(0);
                    p0StartY = e.getY(0);
                    p1StartX = e.getX(1);
                    p1StartY = e.getY(1);
                    twoFingerStartMidX = (p0StartX + p1StartX) * 0.5f;
                    twoFingerStartMidY = (p0StartY + p1StartY) * 0.5f;
                }
            } else {
                twoFingerTapCandidate = false;
            }
            if (e.getPointerCount() > 2) twoFingerTapCandidate = false;
            updateTwoFingers(e);
            return true;
        }

        if (twoFinger && e.getPointerCount() >= 2 && (action == MotionEvent.ACTION_MOVE
                || action == MotionEvent.ACTION_POINTER_UP)) {
            if (action == MotionEvent.ACTION_MOVE && twoFingerTapCandidate) {
                if (e.getPointerCount() != 2
                        || Math.hypot(e.getX(0) - p0StartX, e.getY(0) - p0StartY) > twoFingerTapSlopPx
                        || Math.hypot(e.getX(1) - p1StartX, e.getY(1) - p1StartY) > twoFingerTapSlopPx) {
                    twoFingerTapCandidate = false;
                }
            }
            updateTwoFingers(e);
            return true;
        }

        if (!twoFinger && action == MotionEvent.ACTION_MOVE) {
            if (touchRevision != mouseInput.revision()) {
                // Virtual mouse button was pressed mid-gesture by the other thumb
                cancelTimers();
                tapDragCandidate = false;
                dragging = false;
            }
            float px = e.getX(), py = e.getY();
            long nowMs = e.getEventTime();
            float distFromDown = (float) Math.hypot(px - downX, py - downY);

            if (viewOnly) {
                if (viewport.isZoomed()) {
                    viewport.panBy(px - lastX, py - lastY);
                    applyViewport();
                    lastX = px;
                    lastY = py;
                }
                return true;
            }

            if (usesRelativeMotion(e)) {
                if (tapDragCandidate && !dragging && !suppressPointerGesture() && distFromDown >= tapDragSlopPx) {
                    handler.removeCallbacks(tapDragHoldRunnable);
                    mouseInput.gestureLeft(true);
                    dragging = true;
                    pulseCursor();
                }

                boolean immediateMode = dragging || suppressPointerGesture();
                float deadzonePx = (immediateMode || (nowMs - downTimeMs) > PointerBallistics.TAP_STABILIZE_MS)
                        ? PointerBallistics.SLOW_NUDGE_DEADZONE_DP * density
                        : PointerBallistics.TAP_DEADZONE_DP * density;

                if (!relativeMoveUnlocked) {
                    if (distFromDown < deadzonePx) {
                        return true;
                    }
                    cancelTimers();
                    relativeMoveUnlocked = true;
                    float excessRatio = Math.max(0f, (distFromDown - deadzonePx) / Math.max(0.001f, distFromDown));
                    float stepDx = (px - downX) * excessRatio;
                    float stepDy = (py - downY) * excessRatio;
                    moveRelativeBallistic(stepDx, stepDy, nowMs - downTimeMs, true);
                    lastX = px;
                    lastY = py;
                    lastMoveTimeMs = nowMs;
                } else {
                    float stepDx = px - lastX;
                    float stepDy = py - lastY;
                    if (stepDx != 0f || stepDy != 0f) {
                        moveRelativeBallistic(stepDx, stepDy, nowMs - lastMoveTimeMs, true);
                        lastX = px;
                        lastY = py;
                        lastMoveTimeMs = nowMs;
                    }
                }
            } else {
                // Direct touch mode (屏幕触控)
                if (suppressPointerGesture()) {
                    if (directTouchValid || viewport.contains(px, py)) {
                        directTouchValid = true;
                        updateAbsoluteCursor(px, py, true, true);
                        lastMoveTimeMs = nowMs;
                    }
                } else if (!longPressTriggered) {
                    if (!dragging && distFromDown >= touchSlopPx) {
                        cancelTimers();
                        if (directTouchValid) {
                            updateAbsoluteCursor(downX, downY, false, true);
                            mouseInput.gestureLeft(true);
                            dragging = true;
                            pulseCursor();
                        }
                    }
                    if (dragging) {
                        updateAbsoluteCursor(px, py, true, true);
                        lastMoveTimeMs = nowMs;
                    }
                }
            }
            return true;
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            cancelTimers();
            boolean canceled = action == MotionEvent.ACTION_CANCEL;
            if (dragging) {
                mouseInput.gestureLeft(false);
                dragging = false;
                lastSingleTapUpTimeMs = 0L;
            } else if (!canceled && !viewOnly && !suppressPointerGesture() && !longPressTriggered) {
                if (twoFinger) {
                    if (twoFingerTapCandidate && maxPointersInGesture == 2 && !pinching && !twoFingerScrolling
                            && (e.getEventTime() - downTimeMs) <= TWO_FINGER_TAP_MAX_TOTAL_MS
                            && (e.getEventTime() - twoFingerDownTimeMs) <= TWO_FINGER_TAP_MAX_PAIR_MS) {
                        if (usesRelativeMotion(e)) {
                            fireRightClickWithFeedback();
                        } else if (viewport.contains(twoFingerStartMidX, twoFingerStartMidY)) {
                            updateAbsoluteCursor(twoFingerStartMidX, twoFingerStartMidY, false, true);
                            fireRightClickWithFeedback();
                        }
                    }
                    lastSingleTapUpTimeMs = 0L;
                } else {
                    float totalMove = (float) Math.hypot(e.getX() - downX, e.getY() - downY);
                    if (usesRelativeMotion(e)) {
                        if (!relativeMoveUnlocked && totalMove <= touchSlopPx) {
                            pulseCursor();
                            cursor.performClick();
                            lastSingleTapUpTimeMs = e.getEventTime();
                            lastSingleTapX = e.getX();
                            lastSingleTapY = e.getY();
                        } else {
                            lastSingleTapUpTimeMs = 0L;
                        }
                    } else if (directTouchValid && totalMove <= touchSlopPx && viewport.contains(e.getX(), e.getY())) {
                        updateAbsoluteCursor(e.getX(), e.getY(), false, true);
                        pulseCursor();
                        cursor.performClick();
                        lastSingleTapUpTimeMs = 0L;
                    }
                }
            }
            tapDragCandidate = false;
            twoFingerTapCandidate = false;
            twoFingerScrolling = false;
            scrollAccumulator.reset();
            viewport.finishGesture();
            applyViewport();
            viewportPanActive = false;
            onPointerActivity();
        }
        return true;
    }

    private void updateTwoFingers(MotionEvent event) {
        int action = event.getActionMasked();
        int lifted = action == MotionEvent.ACTION_POINTER_UP ? event.getActionIndex() : -1;
        int count = event.getPointerCount() - (lifted >= 0 ? 1 : 0);
        if (count <= 0) return;
        float focusX = 0, focusY = 0;
        for (int i = 0; i < event.getPointerCount(); i++) {
            if (i == lifted) continue;
            focusX += event.getX(i);
            focusY += event.getY(i);
        }
        focusX /= count;
        focusY /= count;
        float radiusSum = 0;
        for (int i = 0; i < event.getPointerCount(); i++) {
            if (i == lifted) continue;
            radiusSum += (float) Math.hypot(event.getX(i) - focusX, event.getY(i) - focusY);
        }
        float span = Math.max(pinchSlop, 2 * radiusSum / count);
        boolean canMoveViewport = !mouseInput.hasVirtualButton() && viewport.width() > 0;
        if (action == MotionEvent.ACTION_MOVE && count >= 2 && canMoveViewport) {
            moveTwoFingers(focusX, focusY, span);
        } else {
            // A change of contacts establishes a new baseline without moving the image.
            pinchSpan = span;
            pinching = pinching && count >= 2 && canMoveViewport;
            viewportPanActive = viewport.isZoomed() && viewport.contains(focusX, focusY);
            scrollAccumulator.reset();
        }
        twoFingerX = focusX;
        twoFingerY = focusY;
    }

    private void moveTwoFingers(float focusX, float focusY, float span) {
        float pinchThreshold = twoFingerScrolling ? pinchSlop * 2.5f : pinchSlop * 1.65f;
        if (!pinching && Math.abs(span - pinchSpan) >= pinchThreshold) {
            pinching = true;
            twoFingerTapCandidate = false;
            scrollAccumulator.reset();
        }
        if (pinching) {
            viewport.zoomBy(span / pinchSpan, twoFingerX, twoFingerY);
            pinchSpan = span;
        }
        if (pinching || viewport.isZoomed()) {
            // Pinches can start in the letterbox. A pan alone starts over the image.
            if (pinching || viewportPanActive || viewport.contains(focusX, focusY)) {
                viewportPanActive = true;
                twoFingerTapCandidate = false;
                viewport.panBy(focusX - twoFingerX, focusY - twoFingerY);
                applyViewport();
            }
        } else if (!viewOnly && (relative || viewport.contains(focusX, focusY))) {
            float dy = focusY - twoFingerY;
            float stepPx = PointerBallistics.SCROLL_STEP_DP * density;
            int ticks = scrollAccumulator.consume(dy, stepPx, naturalScroll);
            if (ticks != 0) {
                twoFingerScrolling = true;
                twoFingerTapCandidate = false;
                emitScrollTicks(ticks, false);
            }
        }
    }

    @Override public boolean performClick() { super.performClick(); return true; }
    @Override public void surfaceCreated(SurfaceHolder holder) { session.surface(holder.getSurface()); }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { releaseMouse(); session.surface(null); }

    @Override protected void onDetachedFromWindow() {
        handler.removeCallbacks(autoHideCursorRunnable);
        if (cursorFadeAnimator != null) {
            cursorFadeAnimator.cancel();
            cursorFadeAnimator = null;
        }
        releaseMouse();
        super.onDetachedFromWindow();
    }

    private final class Cursor extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrow = new Path();

        Cursor(Context context) {
            super(context);
            setClickable(true);
            setContentDescription(L10n.tr("远程桌面触控区域"));
            // High-craft Windows 11 / macOS sculpted desktop pointer arrow (~17 x 23.5 dp)
            // Hotspot tip is anchored cleanly at (0, 0).
            arrow.moveTo(0f, 0f);
            arrow.lineTo(0f, 19.2f);
            arrow.lineTo(4.7f, 14.9f);
            arrow.lineTo(8.1f, 22.4f);
            arrow.quadTo(8.7f, 23.5f, 9.9f, 22.9f);
            arrow.lineTo(11.3f, 22.2f);
            arrow.quadTo(12.4f, 21.6f, 11.8f, 20.4f);
            arrow.lineTo(8.4f, 13.1f);
            arrow.lineTo(14.6f, 12.7f);
            arrow.close();
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override public boolean onTouchEvent(MotionEvent event) { return touch(event); }

        @Override public boolean performClick() {
            super.performClick();
            if (!viewOnly) click(1, 2);
            return true;
        }

        @Override protected void onDraw(Canvas canvas) {
            if (viewOnly || viewport.width() <= 0 || viewport.height() <= 0) return;
            float alpha = (relative || forceCursorVisible || hardwareHovering) ? 1f : cursorAlpha;
            if (alpha <= 0.01f) return;

            boolean buttonActive = dragging || mouseInput.hasAnyButton()
                    || hardwareButtonState != 0
                    || SystemClock.uptimeMillis() < clickPulseUntilMs;

            int alphaInt = Math.max(0, Math.min(255, Math.round(alpha * 255f)));
            int saved = canvas.save();
            canvas.translate(viewport.left() + x * viewport.width(), viewport.top() + y * viewport.height());
            canvas.scale(density, density);

            if (buttonActive) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.argb(Math.round(alpha * 54f), 0x3B, 0x82, 0xF6));
                canvas.drawCircle(0f, 0f, 9.5f, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.5f);
                paint.setColor(Color.argb(Math.round(alpha * 195f), 0x60, 0xA5, 0xFA));
                canvas.drawCircle(0f, 0f, 9.5f, paint);
            } else if (!relative && !forceCursorVisible) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.2f);
                paint.setColor(Color.argb(Math.round(alpha * 110f), 0xFF, 0xFF, 0xFF));
                canvas.drawCircle(0f, 0f, 7.5f, paint);
            }

            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeCap(Paint.Cap.ROUND);

            // Soft drop shadow so the white pointer reads clearly over bright white documents
            int shadowSave = canvas.save();
            canvas.translate(0.85f, 1.45f);
            paint.setStyle(Paint.Style.FILL_AND_STROKE);
            paint.setStrokeWidth(2.1f);
            paint.setColor(Color.argb(Math.round(alpha * 92f), 0, 0, 0));
            canvas.drawPath(arrow, paint);
            canvas.restoreToCount(shadowSave);

            // Crisp dark charcoal outline + bright white body
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.0f);
            paint.setColor(Color.argb(alphaInt, 0x14, 0x16, 0x1B));
            canvas.drawPath(arrow, paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(buttonActive
                    ? Color.argb(alphaInt, 0xEA, 0xF2, 0xFF)
                    : Color.argb(alphaInt, 0xFF, 0xFF, 0xFF));
            canvas.drawPath(arrow, paint);

            canvas.restoreToCount(saved);
        }
    }
}
