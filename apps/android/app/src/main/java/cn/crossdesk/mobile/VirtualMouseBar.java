package cn.crossdesk.mobile;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

/**
 * UU Remote-style two-state floating virtual mouse:
 * 1) Collapsed (44x44 dp): Draggable dark translucent badge with crisp white filled mouse icon.
 * 2) Expanded (140x170 dp): High-craft Canvas vector ergonomic top-down mouse coupled to the
 *    desktop cursor arrow:
 *    - Upper Left / Right sculpted click plates with 4.5dp zero-jitter deadzone + coupled drag
 *    - Center stadium Scroll Wheel (tap up/down + continuous swipe with fractional accumulator)
 *    - Center Middle button ("=" pill straddling seam: tap middle click, hold+slide middle drag)
 *    - Lower curved Palm zone: moves BOTH virtual mouse and remote pointer cursor together
 *    - Top-right floating circular "x" collapse badge
 */
@SuppressLint("ViewConstructor")
final class VirtualMouseBar extends FrameLayout {
    static final int COLLAPSED_SIZE_DP = 44;
    static final int BODY_WIDTH_DP = 130;
    static final int BODY_HEIGHT_DP = 164;
    static final int CLOSE_BADGE_DP = 26;
    static final int TOP_OVERHANG_DP = 6;
    static final int RIGHT_OVERHANG_DP = 10;
    static final int EXPANDED_WIDTH_DP = BODY_WIDTH_DP + RIGHT_OVERHANG_DP;
    static final int EXPANDED_HEIGHT_DP = BODY_HEIGHT_DP + TOP_OVERHANG_DP;

    // Cursor arrow tip sits just off the upper-left shoulder of the expanded mouse body.
    static final float COUPLED_OFFSET_X_DP = 20f;
    static final float COUPLED_OFFSET_Y_DP = -TOP_OVERHANG_DP;

    // Keep legacy constants mapped to expanded dimensions.
    static final int WIDTH_DP = EXPANDED_WIDTH_DP;
    static final int HEIGHT_DP = EXPANDED_HEIGHT_DP;

    interface PositionHost {
        void moveCollapsedBy(float dx, float dy);
        default void onCollapsedDragEnd() {}
        void onStateChanged();
    }

    final FrameLayout dragHandle;
    final FrameLayout collapsedButton;
    private final FrameLayout expandedShell;
    private final MouseSilhouetteView silhouetteView;
    private final RemoteMouseInput input;
    private final MobileUi ui;
    private final float density;
    private PositionHost positionHost;
    private boolean expanded;
    private int generation;
    private int activeStationaryButtons;

    VirtualMouseBar(MobileUi ui, RemoteMouseInput input) {
        super(ui.context);
        this.ui = ui;
        this.input = input;
        this.density = ui.context.getResources().getDisplayMetrics().density;
        setMotionEventSplittingEnabled(true);
        setClipChildren(false);
        setClipToPadding(false);

        // 1. Collapsed 44x44 dp floating mini mouse badge
        collapsedButton = new FrameLayout(ui.context);
        GradientDrawable orbBg = ui.background(0xC41D1F27, 22);
        orbBg.setStroke(Math.max(1, ui.dp(1f)), 0x38FFFFFF);
        collapsedButton.setBackground(orbBg);
        collapsedButton.setElevation(ui.dp(6));
        collapsedButton.setContentDescription(L10n.tr("展开虚拟鼠标"));
        collapsedButton.addView(new CollapsedMouseGlyph(ui.context),
                new LayoutParams(ui.dp(22), ui.dp(26), Gravity.CENTER));
        attachCollapsedDragAndTap(collapsedButton, () -> setExpanded(true));
        addView(collapsedButton, new LayoutParams(ui.dp(COLLAPSED_SIZE_DP), ui.dp(COLLAPSED_SIZE_DP)));

        // 2. Expanded sculpted mouse container (140x170 dp including top-right close badge)
        expandedShell = new FrameLayout(ui.context);
        expandedShell.setMotionEventSplittingEnabled(true);
        expandedShell.setClipChildren(false);
        expandedShell.setClipToPadding(false);
        expandedShell.setVisibility(GONE);

        int bodyW = ui.dp(BODY_WIDTH_DP);
        int bodyH = ui.dp(BODY_HEIGHT_DP);
        int topInset = ui.dp(TOP_OVERHANG_DP);

        FrameLayout bodyContainer = new FrameLayout(ui.context);
        bodyContainer.setMotionEventSplittingEnabled(true);
        bodyContainer.setElevation(ui.dp(8));

        silhouetteView = new MouseSilhouetteView(ui.context);
        bodyContainer.addView(silhouetteView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        int seamY = Math.round(bodyH * 0.53f);
        int halfW = bodyW / 2;

        // Left & Right sculpted click/drag plates (upper 53% of mouse body)
        View leftPlate = createPrimaryPlate(
                L10n.tr("鼠标左键，按住并拖动，松开结束拖拽"), RemoteMouseInput.BUTTON_LEFT, true);
        View rightPlate = createPrimaryPlate(
                L10n.tr("鼠标右键，按住并拖动，松开结束拖拽"), RemoteMouseInput.BUTTON_RIGHT, false);
        bodyContainer.addView(leftPlate, new LayoutParams(halfW, seamY, Gravity.TOP | Gravity.LEFT));
        bodyContainer.addView(rightPlate, new LayoutParams(bodyW - halfW, seamY, Gravity.TOP | Gravity.RIGHT));

        // Lower curved palm zone: drags virtual mouse AND desktop cursor together
        dragHandle = new FrameLayout(ui.context);
        dragHandle.setContentDescription(L10n.tr("拖动虚拟鼠标"));
        attachCoupledPalmDrag(dragHandle);
        LayoutParams palmLp = new LayoutParams(LayoutParams.MATCH_PARENT, bodyH - seamY, Gravity.TOP | Gravity.LEFT);
        palmLp.topMargin = seamY;
        bodyContainer.addView(dragHandle, palmLp);

        // Center Scroll Wheel hit area (generous 34x52 dp touch target over 25x48 dp pill)
        View wheelTouch = createWheelTouchZone();
        LayoutParams wheelLp = new LayoutParams(ui.dp(34), ui.dp(52), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        wheelLp.topMargin = ui.dp(10);
        bodyContainer.addView(wheelTouch, wheelLp);

        // Center Middle ("=") button hit area straddling the horizontal seam
        View middleTouch = createMiddleTouchZone();
        LayoutParams midLp = new LayoutParams(ui.dp(34), ui.dp(33), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        midLp.topMargin = seamY - ui.dp(16.5f);
        bodyContainer.addView(middleTouch, midLp);

        LayoutParams bodyLp = new LayoutParams(bodyW, bodyH, Gravity.TOP | Gravity.LEFT);
        bodyLp.topMargin = topInset;
        expandedShell.addView(bodyContainer, bodyLp);

        // Floating circular close ("x") button at top-right outside shoulder
        FrameLayout collapseBtn = new FrameLayout(ui.context);
        GradientDrawable closeBg = ui.background(0xBD22252D, 13);
        closeBg.setStroke(Math.max(1, ui.dp(0.9f)), 0x42FFFFFF);
        collapseBtn.setBackground(closeBg);
        collapseBtn.setElevation(ui.dp(10));
        collapseBtn.setContentDescription(L10n.tr("收起虚拟鼠标"));
        collapseBtn.setClickable(true);
        collapseBtn.setFocusable(true);
        collapseBtn.addView(ui.icon("close", 0xFFF2F4F8),
                new LayoutParams(ui.dp(13), ui.dp(13), Gravity.CENTER));
        collapseBtn.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            cancelInput();
            setExpanded(false);
        });
        LayoutParams closeLp = new LayoutParams(ui.dp(CLOSE_BADGE_DP), ui.dp(CLOSE_BADGE_DP), Gravity.TOP | Gravity.RIGHT);
        expandedShell.addView(collapseBtn, closeLp);

        addView(expandedShell, new LayoutParams(ui.dp(EXPANDED_WIDTH_DP), ui.dp(EXPANDED_HEIGHT_DP)));

        input.addCancelListener(this::resetTouches);
        setContentDescription(L10n.tr("虚拟鼠标"));
    }

    void setPositionHost(PositionHost host) {
        this.positionHost = host;
    }

    boolean isExpanded() { return expanded; }

    boolean isStationaryButtonHold() { return activeStationaryButtons > 0; }

    int currentWidthDp() { return expanded ? EXPANDED_WIDTH_DP : COLLAPSED_SIZE_DP; }

    int currentHeightDp() { return expanded ? EXPANDED_HEIGHT_DP : COLLAPSED_SIZE_DP; }

    void setExpanded(boolean value) {
        if (expanded == value) return;
        cancelInput();
        expanded = value;
        collapsedButton.setVisibility(expanded ? GONE : VISIBLE);
        expandedShell.setVisibility(expanded ? VISIBLE : GONE);
        if (positionHost != null) positionHost.onStateChanged();
    }

    private static float rawX(View v, MotionEvent event, int pointerIndex) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return event.getRawX(pointerIndex);
        }
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        return loc[0] + event.getX(pointerIndex);
    }

    private static float rawY(View v, MotionEvent event, int pointerIndex) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return event.getRawY(pointerIndex);
        }
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        return loc[1] + event.getY(pointerIndex);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void attachCollapsedDragAndTap(View handle, Runnable onTap) {
        handle.setClickable(true);
        handle.setFocusable(true);
        if (onTap != null) handle.setOnClickListener(v -> onTap.run());
        float slop = ui.dp(4f);
        handle.setOnTouchListener(new OnTouchListener() {
            int pointer = -1;
            float downRawX, downRawY, lastRawX, lastRawY;
            boolean moved;

            @Override public boolean onTouch(View v, MotionEvent event) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    pointer = event.getPointerId(0);
                    downRawX = lastRawX = rawX(v, event, 0);
                    downRawY = lastRawY = rawY(v, event, 0);
                    moved = false;
                    v.setScaleX(0.94f);
                    v.setScaleY(0.94f);
                    return true;
                }
                if (pointer == -1) return true;
                if (action == MotionEvent.ACTION_MOVE) {
                    int p = event.findPointerIndex(pointer);
                    if (p >= 0) {
                        float rx = rawX(v, event, p);
                        float ry = rawY(v, event, p);
                        if (!moved && Math.hypot(rx - downRawX, ry - downRawY) > slop) {
                            moved = true;
                            lastRawX = rx;
                            lastRawY = ry;
                        } else if (moved) {
                            if (positionHost != null) positionHost.moveCollapsedBy(rx - lastRawX, ry - lastRawY);
                            lastRawX = rx;
                            lastRawY = ry;
                        }
                    }
                    return true;
                }
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                        || (action == MotionEvent.ACTION_POINTER_UP
                        && event.getPointerId(event.getActionIndex()) == pointer)) {
                    v.setScaleX(1f);
                    v.setScaleY(1f);
                    boolean wasMoved = moved;
                    pointer = -1;
                    moved = false;
                    if (action != MotionEvent.ACTION_CANCEL && !wasMoved && onTap != null) {
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        v.performClick();
                    } else if (wasMoved && positionHost != null) {
                        positionHost.onCollapsedDragEnd();
                    }
                }
                return true;
            }
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    private void attachCoupledPalmDrag(View palm) {
        palm.setClickable(true);
        palm.setFocusable(true);
        float slop = 1.5f * density;
        palm.setOnTouchListener(new OnTouchListener() {
            int pointer = -1, startedGen;
            float downRawX, downRawY, lastRawX, lastRawY;
            long lastTimeMs;
            boolean draggingPalm;

            @Override public boolean onTouch(View v, MotionEvent event) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    pointer = event.getPointerId(0);
                    startedGen = generation;
                    downRawX = lastRawX = rawX(v, event, 0);
                    downRawY = lastRawY = rawY(v, event, 0);
                    lastTimeMs = event.getEventTime();
                    draggingPalm = false;
                    silhouetteView.setPalmActive(true);
                    return true;
                }
                if (startedGen != generation || pointer == -1) return true;
                if (action == MotionEvent.ACTION_MOVE) {
                    int p = event.findPointerIndex(pointer);
                    if (p >= 0) {
                        float rx = rawX(v, event, p);
                        float ry = rawY(v, event, p);
                        long now = event.getEventTime();
                        if (!draggingPalm) {
                            if (Math.hypot(rx - downRawX, ry - downRawY) >= slop) {
                                draggingPalm = true;
                                input.virtualMouseMove(rx - downRawX, ry - downRawY, now - lastTimeMs);
                                lastRawX = rx;
                                lastRawY = ry;
                                lastTimeMs = now;
                            }
                        } else {
                            float dx = rx - lastRawX;
                            float dy = ry - lastRawY;
                            if (dx != 0f || dy != 0f) {
                                input.virtualMouseMove(dx, dy, now - lastTimeMs);
                                lastRawX = rx;
                                lastRawY = ry;
                                lastTimeMs = now;
                            }
                        }
                    }
                    return true;
                }
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                        || (action == MotionEvent.ACTION_POINTER_UP
                        && event.getPointerId(event.getActionIndex()) == pointer)) {
                    pointer = -1;
                    draggingPalm = false;
                    silhouetteView.setPalmActive(false);
                }
                return true;
            }
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    private View createPrimaryPlate(String desc, int buttonCode, boolean leftSide) {
        View plate = new View(ui.context);
        plate.setContentDescription(desc);
        plate.setClickable(true);
        plate.setFocusable(true);
        plate.setOnClickListener(v -> {
            input.virtualButton(buttonCode, true);
            input.virtualButton(buttonCode, false);
        });

        float deadzonePx = PointerBallistics.VIRTUAL_BUTTON_DEADZONE_DP * density;
        plate.setOnTouchListener(new OnTouchListener() {
            int pointer = -1, startedGen;
            float downRawX, downRawY, lastRawX, lastRawY;
            long downTimeMs, lastTimeMs;
            boolean dragUnlocked;
            boolean countedStationary;

            @Override public boolean onTouch(View v, MotionEvent event) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    pointer = event.getPointerId(0);
                    startedGen = generation;
                    downRawX = lastRawX = rawX(v, event, 0);
                    downRawY = lastRawY = rawY(v, event, 0);
                    downTimeMs = lastTimeMs = event.getEventTime();
                    dragUnlocked = false;
                    countedStationary = true;
                    activeStationaryButtons++;
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    silhouetteView.setButtonPressed(buttonCode, true);
                    input.virtualButton(buttonCode, true);
                    return true;
                }
                if (startedGen != generation || pointer == -1) return true;
                if (action == MotionEvent.ACTION_MOVE) {
                    int p = event.findPointerIndex(pointer);
                    if (p >= 0) {
                        float rx = rawX(v, event, p);
                        float ry = rawY(v, event, p);
                        long now = event.getEventTime();
                        if (!dragUnlocked) {
                            float dist = (float) Math.hypot(rx - downRawX, ry - downRawY);
                            if (dist < deadzonePx) {
                                return true; // Zero cursor jitter during clicks & double-clicks!
                            }
                            dragUnlocked = true;
                            if (countedStationary) {
                                countedStationary = false;
                                activeStationaryButtons = Math.max(0, activeStationaryButtons - 1);
                            }
                            float ratio = Math.max(0f, (dist - deadzonePx) / Math.max(0.001f, dist));
                            input.virtualMouseDrag((rx - downRawX) * ratio, (ry - downRawY) * ratio);
                            lastRawX = rx;
                            lastRawY = ry;
                            lastTimeMs = now;
                        } else {
                            float dx = rx - lastRawX;
                            float dy = ry - lastRawY;
                            if (dx != 0f || dy != 0f) {
                                input.virtualMouseDrag(dx, dy);
                                lastRawX = rx;
                                lastRawY = ry;
                                lastTimeMs = now;
                            }
                        }
                    }
                    return true;
                }
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                        || (action == MotionEvent.ACTION_POINTER_UP
                        && event.getPointerId(event.getActionIndex()) == pointer)) {
                    pointer = -1;
                    dragUnlocked = false;
                    if (countedStationary) {
                        countedStationary = false;
                        activeStationaryButtons = Math.max(0, activeStationaryButtons - 1);
                    }
                    silhouetteView.setButtonPressed(buttonCode, false);
                    input.virtualButton(buttonCode, false);
                }
                return true;
            }
        });
        return plate;
    }

    @SuppressLint("ClickableViewAccessibility")
    private View createMiddleTouchZone() {
        View box = new View(ui.context);
        box.setContentDescription(L10n.tr("鼠标中键，轻触点击或按住拖动"));
        box.setClickable(true);
        box.setFocusable(true);
        box.setOnClickListener(v -> {
            input.virtualMiddle(true);
            input.virtualMiddle(false);
        });

        float deadzonePx = PointerBallistics.VIRTUAL_BUTTON_DEADZONE_DP * density;
        box.setOnTouchListener(new OnTouchListener() {
            int pointer = -1, startedGen;
            float downRawX, downRawY, lastRawX, lastRawY;
            long lastTimeMs;
            boolean movedGrip;

            @Override public boolean onTouch(View v, MotionEvent event) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    pointer = event.getPointerId(0);
                    startedGen = generation;
                    downRawX = lastRawX = rawX(v, event, 0);
                    downRawY = lastRawY = rawY(v, event, 0);
                    lastTimeMs = event.getEventTime();
                    movedGrip = false;
                    silhouetteView.setButtonPressed(RemoteMouseInput.BUTTON_MIDDLE, true);
                    return true;
                }
                if (startedGen != generation || pointer == -1) return true;
                if (action == MotionEvent.ACTION_MOVE) {
                    int p = event.findPointerIndex(pointer);
                    if (p >= 0) {
                        float rx = rawX(v, event, p);
                        float ry = rawY(v, event, p);
                        long now = event.getEventTime();
                        if (!movedGrip) {
                            float dist = (float) Math.hypot(rx - downRawX, ry - downRawY);
                            if (dist < deadzonePx) return true;
                            movedGrip = true;
                            // "=" grip acts as UU Remote's center drag handle without triggering browser middle-button auto-scroll!
                            silhouetteView.setButtonPressed(RemoteMouseInput.BUTTON_MIDDLE, false);
                            silhouetteView.setPalmActive(true);
                            float ratio = Math.max(0f, (dist - deadzonePx) / Math.max(0.001f, dist));
                            input.virtualMouseMove((rx - downRawX) * ratio, (ry - downRawY) * ratio, now - lastTimeMs);
                            lastRawX = rx;
                            lastRawY = ry;
                            lastTimeMs = now;
                        } else {
                            float dx = rx - lastRawX;
                            float dy = ry - lastRawY;
                            if (dx != 0f || dy != 0f) {
                                input.virtualMouseMove(dx, dy, now - lastTimeMs);
                                lastRawX = rx;
                                lastRawY = ry;
                                lastTimeMs = now;
                            }
                        }
                    }
                    return true;
                }
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                        || (action == MotionEvent.ACTION_POINTER_UP
                        && event.getPointerId(event.getActionIndex()) == pointer)) {
                    boolean tapMiddle = action == MotionEvent.ACTION_UP && !movedGrip;
                    pointer = -1;
                    movedGrip = false;
                    silhouetteView.setButtonPressed(RemoteMouseInput.BUTTON_MIDDLE, false);
                    silhouetteView.setPalmActive(false);
                    if (tapMiddle) {
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        input.virtualMiddle(true);
                        input.virtualMiddle(false);
                    }
                }
                return true;
            }
        });
        return box;
    }

    @SuppressLint("ClickableViewAccessibility")
    private View createWheelTouchZone() {
        View wheel = new View(ui.context);
        wheel.setContentDescription(L10n.tr("向上滚动，可上下滑动连续滚轮"));
        wheel.setClickable(true);
        wheel.setFocusable(true);
        wheel.setOnClickListener(v -> input.scroll(1));

        int touchSlop = ViewConfiguration.get(ui.context).getScaledTouchSlop();
        float stepPx = 12.5f * density;
        PointerBallistics.ScrollAccumulator wheelAccumulator = new PointerBallistics.ScrollAccumulator();

        wheel.setOnTouchListener(new OnTouchListener() {
            int pointer = -1, startedGen;
            float downRawY, lastRawY, downLocalY;
            boolean swiped;

            @Override public boolean onTouch(View v, MotionEvent event) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    pointer = event.getPointerId(0);
                    startedGen = generation;
                    downRawY = lastRawY = rawY(v, event, 0);
                    downLocalY = event.getY();
                    wheelAccumulator.reset();
                    swiped = false;
                    silhouetteView.setWheelState(true, downLocalY < v.getHeight() / 2f ? 1 : -1);
                    return true;
                }
                if (startedGen != generation || pointer == -1) return true;
                if (action == MotionEvent.ACTION_MOVE) {
                    int p = event.findPointerIndex(pointer);
                    if (p >= 0) {
                        float ry = rawY(v, event, p);
                        float dy = ry - lastRawY;
                        lastRawY = ry;
                        if (Math.abs(ry - downRawY) > touchSlop * 0.6f) swiped = true;
                        // Rolling finger down on physical wheel cap scrolls down (-1)
                        int ticks = wheelAccumulator.consume(dy, stepPx, false);
                        if (ticks != 0) {
                            swiped = true;
                            int dir = ticks > 0 ? 1 : -1;
                            silhouetteView.setWheelState(true, dir);
                            for (int i = 0; i < Math.abs(ticks); i++) {
                                input.scroll(dir);
                            }
                            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        }
                    }
                    return true;
                }
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                        || (action == MotionEvent.ACTION_POINTER_UP
                        && event.getPointerId(event.getActionIndex()) == pointer)) {
                    if (action == MotionEvent.ACTION_UP && !swiped) {
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        input.scroll(downLocalY < v.getHeight() / 2f ? 1 : -1);
                    }
                    pointer = -1;
                    wheelAccumulator.reset();
                    silhouetteView.setWheelState(false, 0);
                }
                return true;
            }
        });
        return wheel;
    }

    void cancelInput() {
        input.releaseAll();
    }

    private void resetTouches() {
        generation++;
        activeStationaryButtons = 0;
        silhouetteView.resetAll();
    }

    @Override protected void onDetachedFromWindow() {
        cancelInput();
        super.onDetachedFromWindow();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused) cancelInput();
    }

    /** Crisp filled white UU-style mini mouse glyph for the 44x44 dp collapsed badge. */
    private static final class CollapsedMouseGlyph extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF body = new RectF();
        private final RectF wheel = new RectF();

        CollapsedMouseGlyph(Context context) {
            super(context);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;
            float padX = w * 0.14f;
            float padY = h * 0.06f;
            body.set(padX, padY, w - padX, h - padY);
            float r = body.width() * 0.48f;

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            c.drawRoundRect(body, r, r, paint);

            // Top button split + scroll wheel slot cutout
            float cx = w * 0.5f;
            paint.setColor(0xFF1D1F27);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1.4f, w * 0.065f));
            c.drawLine(cx, body.top, cx, body.top + body.height() * 0.42f, paint);

            paint.setStyle(Paint.Style.FILL);
            float ww = body.width() * 0.22f;
            float wh = body.height() * 0.26f;
            float wy = body.top + body.height() * 0.13f;
            wheel.set(cx - ww / 2f, wy, cx + ww / 2f, wy + wh);
            c.drawRoundRect(wheel, ww / 2f, ww / 2f, paint);
        }
    }

    /** High-craft Canvas vector rendering of UU Remote's sculpted top-down mouse. */
    private static final class MouseSilhouetteView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path bodyPath = new Path();
        private final Path triPath = new Path();
        private final RectF bodyRect = new RectF();
        private final RectF wheelRect = new RectF();
        private final RectF midRect = new RectF();
        private final RectF clipRect = new RectF();
        private final float density;
        private LinearGradient bodyGradient;
        private int cachedHeight;

        private boolean leftPressed;
        private boolean rightPressed;
        private boolean middlePressed;
        private boolean wheelActive;
        private int wheelDir;
        private boolean palmActive;

        MouseSilhouetteView(Context context) {
            super(context);
            density = context.getResources().getDisplayMetrics().density;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void setButtonPressed(int code, boolean pressed) {
            if (code == RemoteMouseInput.BUTTON_LEFT) leftPressed = pressed;
            else if (code == RemoteMouseInput.BUTTON_RIGHT) rightPressed = pressed;
            else middlePressed = pressed;
            invalidate();
        }

        void setWheelState(boolean active, int dir) {
            wheelActive = active;
            wheelDir = dir;
            invalidate();
        }

        void setPalmActive(boolean active) {
            palmActive = active;
            invalidate();
        }

        void resetAll() {
            leftPressed = rightPressed = middlePressed = wheelActive = palmActive = false;
            wheelDir = 0;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;

            float stroke = 2.0f * density;
            float inset = stroke * 0.5f + 0.5f * density;
            bodyRect.set(inset, inset, w - inset, h - inset);

            float rTop = 45f * density;
            float rBot = 49f * density;
            float[] radii = {rTop, rTop, rTop, rTop, rBot, rBot, rBot, rBot};
            bodyPath.reset();
            bodyPath.addRoundRect(bodyRect, radii, Path.Direction.CW);

            if (bodyGradient == null || cachedHeight != (int) h) {
                cachedHeight = (int) h;
                bodyGradient = new LinearGradient(0f, 0f, 0f, h,
                        0xF0E8EEF9, 0xE8D5DFEF, Shader.TileMode.CLAMP);
            }

            // 1. Frosted cool-white/ice-blue body fill
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(bodyGradient);
            canvas.drawPath(bodyPath, paint);
            paint.setShader(null);

            float seamY = h * 0.53f;
            float cx = w * 0.5f;

            // 2. Clipped plate press highlights
            if (leftPressed || rightPressed || palmActive) {
                int save = canvas.save();
                canvas.clipPath(bodyPath);
                paint.setStyle(Paint.Style.FILL);
                if (leftPressed) {
                    paint.setColor(0x885B9CFF);
                    clipRect.set(0f, 0f, cx, seamY);
                    canvas.drawRect(clipRect, paint);
                }
                if (rightPressed) {
                    paint.setColor(0x885B9CFF);
                    clipRect.set(cx, 0f, w, seamY);
                    canvas.drawRect(clipRect, paint);
                }
                if (palmActive) {
                    paint.setColor(0x325B9CFF);
                    clipRect.set(0f, seamY, w, h);
                    canvas.drawRect(clipRect, paint);
                }
                canvas.restoreToCount(save);
            }

            // 3. Internal button & palm seam lines
            int seamSave = canvas.save();
            canvas.clipPath(bodyPath);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.45f * density);
            paint.setColor(0xFF9AA3B3);
            canvas.drawLine(bodyRect.left, seamY, bodyRect.right, seamY, paint);
            canvas.drawLine(cx, bodyRect.top, cx, seamY, paint);
            canvas.restoreToCount(seamSave);

            // 4. Outer slate-silver border + subtle inner rim highlight
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setColor(0xFF7B8596);
            canvas.drawPath(bodyPath, paint);

            // 5. Center Scroll Wheel stadium pill (25 x 48 dp)
            float wheelW = 25f * density;
            float wheelH = 48f * density;
            float wheelTop = 12.5f * density;
            wheelRect.set(cx - wheelW * 0.5f, wheelTop, cx + wheelW * 0.5f, wheelTop + wheelH);
            float wheelR = wheelW * 0.5f;

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(wheelActive ? 0xFFDCE9FF : 0xFFF4F7FD);
            canvas.drawRoundRect(wheelRect, wheelR, wheelR, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.55f * density);
            paint.setColor(wheelActive ? MobileUi.BLUE : 0xFF8891A2);
            canvas.drawRoundRect(wheelRect, wheelR, wheelR, paint);

            // Solid upward & downward triangles + center pivot dot inside wheel
            paint.setStyle(Paint.Style.FILL);
            float triHalfW = 3.6f * density;
            float triH = 4.6f * density;

            float upTipY = wheelRect.top + 8.5f * density;
            paint.setColor(wheelActive && wheelDir > 0 ? MobileUi.BLUE : 0xFF768092);
            triPath.reset();
            triPath.moveTo(cx, upTipY);
            triPath.lineTo(cx - triHalfW, upTipY + triH);
            triPath.lineTo(cx + triHalfW, upTipY + triH);
            triPath.close();
            canvas.drawPath(triPath, paint);

            paint.setColor(0xFF9AA3B3);
            canvas.drawCircle(cx, wheelRect.centerY(), 1.6f * density, paint);

            float downTipY = wheelRect.bottom - 8.5f * density;
            paint.setColor(wheelActive && wheelDir < 0 ? MobileUi.BLUE : 0xFF768092);
            triPath.reset();
            triPath.moveTo(cx, downTipY);
            triPath.lineTo(cx - triHalfW, downTipY - triH);
            triPath.lineTo(cx + triHalfW, downTipY - triH);
            triPath.close();
            canvas.drawPath(triPath, paint);

            // 6. Center Middle ("=") pill straddling the horizontal seam
            float midW = 25f * density;
            float midH = 28f * density;
            midRect.set(cx - midW * 0.5f, seamY - midH * 0.5f, cx + midW * 0.5f, seamY + midH * 0.5f);
            float midR = 9.5f * density;

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(middlePressed ? MobileUi.BLUE : 0xFFF4F7FD);
            canvas.drawRoundRect(midRect, midR, midR, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.55f * density);
            paint.setColor(middlePressed ? 0xFF1F61C7 : 0xFF8891A2);
            canvas.drawRoundRect(midRect, midR, midR, paint);

            // Dual parallel "=" strokes
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(1.8f * density);
            paint.setColor(middlePressed ? Color.WHITE : 0xFF768092);
            float eqHalfW = 4.3f * density;
            float eqGap = 2.3f * density;
            canvas.drawLine(cx - eqHalfW, seamY - eqGap, cx + eqHalfW, seamY - eqGap, paint);
            canvas.drawLine(cx - eqHalfW, seamY + eqGap, cx + eqHalfW, seamY + eqGap, paint);
        }
    }
}
