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
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

/**
 * UU Remote-style two-state floating virtual mouse:
 * 1) Collapsed (34/38 dp): Draggable dark translucent badge with crisp white filled mouse icon.
 * 2) Expanded (adaptive compact body + exterior close badge): Compact ergonomic top-down mouse
 *    rigidly coupled to the desktop cursor arrow (can smoothly slide off-screen at edges):
 *    - Phone (<540dp short side): 58x68 dp body + 18dp close badge (minimal screen footprint)
 *    - Tablet (>=540dp short side): 72x84 dp body + 20dp close badge
 *    - Upper Left / Right sculpted click plates with 4.0dp zero-jitter deadzone + coupled drag
 *    - Center stadium Scroll Wheel (tap up/down + continuous swipe with fractional accumulator)
 *    - Center Middle button ("=" pill straddling seam: tap middle click, hold+slide smooth move)
 *    - Lower curved Palm zone: moves BOTH virtual mouse and remote pointer cursor together
 *    - Exterior top-right circular "x" collapse badge (zero overlap with Right-Click plate)
 */
@SuppressLint("ViewConstructor")
final class VirtualMouseBar extends FrameLayout {
    static final int COLLAPSED_SIZE_DP = 38;
    static final int BODY_WIDTH_DP = 72;
    static final int BODY_HEIGHT_DP = 84;
    static final int CLOSE_BADGE_DP = 20;
    static final int TOP_OVERHANG_DP = 0;
    static final int RIGHT_OVERHANG_DP = 24; // 4dp gap + 20dp badge outside right shoulder
    static final int EXPANDED_WIDTH_DP = BODY_WIDTH_DP + RIGHT_OVERHANG_DP;
    static final int EXPANDED_HEIGHT_DP = BODY_HEIGHT_DP + TOP_OVERHANG_DP;

    // Cursor arrow tip sits cleanly off the upper-left shoulder of the expanded mouse body.
    static final float COUPLED_OFFSET_X_DP = 14f;
    static final float COUPLED_OFFSET_Y_DP = -2f;

    private static final float EDGE_GLIDE_ZONE_DP = 16f;
    private static final float EDGE_GLIDE_MIN_TRAVEL_DP = 14f;
    private static final float EDGE_GLIDE_DP_PER_MS = 0.58f;

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
    private final int collapsedSizeDp;
    private final int bodyWidthDp;
    private final int bodyHeightDp;
    private final int closeBadgeDp;
    private final int closeGapDp;
    private final int expandedWidthDp;
    private final int expandedHeightDp;
    private final float coupledOffsetXDp;
    private PositionHost positionHost;
    private boolean expanded;
    private int generation;
    private int activeStationaryButtons;

    // Physical bezel edge auto-glide when user's thumb runs out of screen glass while dragging
    private boolean edgeGlideActive;
    private boolean edgeGlideButtonMode;
    private float edgeGlideVx, edgeGlideVy;
    private long edgeGlideLastMs;
    private final Runnable edgeGlideTick = new Runnable() {
        @Override public void run() {
            if (!edgeGlideActive || !expanded || !isAttachedToWindow()) {
                edgeGlideActive = false;
                return;
            }
            long now = SystemClock.uptimeMillis();
            long dt = Math.max(8L, Math.min(32L, now - edgeGlideLastMs));
            edgeGlideLastMs = now;
            float stepBase = EDGE_GLIDE_DP_PER_MS * density * dt;
            float dx = edgeGlideVx * stepBase;
            float dy = edgeGlideVy * stepBase;
            if (dx != 0f || dy != 0f) {
                if (edgeGlideButtonMode) {
                    input.virtualMouseDrag(dx, dy, dt);
                } else {
                    input.virtualMouseMove(dx, dy, dt);
                }
            }
            postOnAnimation(this);
        }
    };

    VirtualMouseBar(MobileUi ui, RemoteMouseInput input) {
        super(ui.context);
        this.ui = ui;
        this.input = input;
        DisplayMetrics dm = ui.context.getResources().getDisplayMetrics();
        this.density = dm.density;
        float shortSideDp = Math.min(dm.widthPixels, dm.heightPixels) / Math.max(1f, density);
        boolean compactPhone = shortSideDp < 540f;
        float tinyScale = compactPhone && shortSideDp < 350f
                ? Math.max(0.85f, shortSideDp / 360f)
                : 1f;
        this.collapsedSizeDp = compactPhone ? 34 : COLLAPSED_SIZE_DP;
        this.bodyWidthDp = Math.round((compactPhone ? 58 : BODY_WIDTH_DP) * tinyScale);
        this.bodyHeightDp = Math.round((compactPhone ? 68 : BODY_HEIGHT_DP) * tinyScale);
        this.closeBadgeDp = compactPhone ? 18 : CLOSE_BADGE_DP;
        this.closeGapDp = compactPhone ? 3 : 4;
        this.expandedWidthDp = bodyWidthDp + closeGapDp + closeBadgeDp;
        this.expandedHeightDp = bodyHeightDp + TOP_OVERHANG_DP;
        this.coupledOffsetXDp = compactPhone ? 12f : COUPLED_OFFSET_X_DP;

        setMotionEventSplittingEnabled(true);
        setClipChildren(false);
        setClipToPadding(false);

        // 1. Collapsed floating mini mouse badge
        collapsedButton = new FrameLayout(ui.context);
        GradientDrawable orbBg = ui.background(0xC41D1F27, collapsedSizeDp / 2);
        orbBg.setStroke(Math.max(1, ui.dp(1f)), 0x38FFFFFF);
        collapsedButton.setBackground(orbBg);
        collapsedButton.setElevation(ui.dp(6));
        collapsedButton.setContentDescription(L10n.tr("展开虚拟鼠标"));
        collapsedButton.addView(new CollapsedMouseGlyph(ui.context),
                new LayoutParams(ui.dp(collapsedSizeDp * 0.5f), ui.dp(collapsedSizeDp * 0.59f), Gravity.CENTER));
        attachCollapsedDragAndTap(collapsedButton, () -> setExpanded(true));
        addView(collapsedButton, new LayoutParams(ui.dp(collapsedSizeDp), ui.dp(collapsedSizeDp)));

        // 2. Expanded compact UU-proportioned mouse container
        expandedShell = new FrameLayout(ui.context);
        expandedShell.setMotionEventSplittingEnabled(true);
        expandedShell.setClipChildren(false);
        expandedShell.setClipToPadding(false);
        expandedShell.setVisibility(GONE);

        int bodyW = ui.dp(bodyWidthDp);
        int bodyH = ui.dp(bodyHeightDp);
        int topInset = ui.dp(TOP_OVERHANG_DP);
        float scale = bodyWidthDp / 98f;

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

        // Center Scroll Wheel hit area (scaled proportionally, minimum 22dp wide for reliable touch)
        View wheelTouch = createWheelTouchZone();
        int wheelTouchW = Math.max(ui.dp(22), Math.round(ui.dp(30f * scale)));
        int wheelTouchH = Math.max(ui.dp(24), Math.round(ui.dp(42f * scale)));
        LayoutParams wheelLp = new LayoutParams(wheelTouchW, wheelTouchH, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        wheelLp.topMargin = Math.max(ui.dp(3), Math.round(ui.dp(6f * scale)));
        bodyContainer.addView(wheelTouch, wheelLp);

        // Center Middle ("=") button hit area straddling the horizontal seam
        View middleTouch = createMiddleTouchZone();
        int midTouchW = Math.max(ui.dp(22), Math.round(ui.dp(30f * scale)));
        int midTouchH = Math.max(ui.dp(18), Math.round(ui.dp(26f * scale)));
        LayoutParams midLp = new LayoutParams(midTouchW, midTouchH, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        midLp.topMargin = seamY - midTouchH / 2;
        bodyContainer.addView(middleTouch, midLp);

        LayoutParams bodyLp = new LayoutParams(bodyW, bodyH, Gravity.TOP | Gravity.LEFT);
        bodyLp.topMargin = topInset;
        expandedShell.addView(bodyContainer, bodyLp);

        // Exterior circular close ("x") button cleanly outside the right shoulder (zero overlap with Right-Click)
        FrameLayout collapseBtn = new FrameLayout(ui.context);
        GradientDrawable closeBg = ui.background(0xC622252D, closeBadgeDp / 2);
        closeBg.setStroke(Math.max(1, ui.dp(0.9f)), 0x45FFFFFF);
        collapseBtn.setBackground(closeBg);
        collapseBtn.setElevation(ui.dp(10));
        collapseBtn.setContentDescription(L10n.tr("收起虚拟鼠标"));
        collapseBtn.setClickable(true);
        collapseBtn.setFocusable(false);
        int closeIconPx = Math.max(ui.dp(9), Math.round(ui.dp(closeBadgeDp * 0.5f)));
        collapseBtn.addView(ui.icon("close", 0xFFF2F4F8),
                new LayoutParams(closeIconPx, closeIconPx, Gravity.CENTER));
        collapseBtn.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            cancelInput();
            setExpanded(false);
        });
        LayoutParams closeLp = new LayoutParams(ui.dp(closeBadgeDp), ui.dp(closeBadgeDp), Gravity.TOP | Gravity.LEFT);
        closeLp.leftMargin = bodyW + ui.dp(closeGapDp);
        closeLp.topMargin = Math.max(ui.dp(3), Math.round(ui.dp(6f * scale)));
        expandedShell.addView(collapseBtn, closeLp);

        addView(expandedShell, new LayoutParams(ui.dp(expandedWidthDp), ui.dp(expandedHeightDp)));

        input.addCancelListener(this::resetTouches);
        setContentDescription(L10n.tr("虚拟鼠标"));
    }

    void setPositionHost(PositionHost host) {
        this.positionHost = host;
    }

    boolean isExpanded() { return expanded; }

    boolean isStationaryButtonHold() { return activeStationaryButtons > 0; }

    int currentWidthDp() { return expanded ? expandedWidthDp : collapsedSizeDp; }

    int currentHeightDp() { return expanded ? expandedHeightDp : collapsedSizeDp; }

    float coupledOffsetXDp() { return coupledOffsetXDp; }

    float coupledOffsetYDp() { return COUPLED_OFFSET_Y_DP; }

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
        handle.setFocusable(false);
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

    private void updateEdgeGlide(float rx, float ry, float downRx, float downRy, boolean buttonMode) {
        DisplayMetrics dm = ui.context.getResources().getDisplayMetrics();
        int sw = dm.widthPixels;
        int sh = dm.heightPixels;
        if (sw <= 0 || sh <= 0) {
            stopEdgeGlide();
            return;
        }
        float minTravelPx = EDGE_GLIDE_MIN_TRAVEL_DP * density;
        if (Math.hypot(rx - downRx, ry - downRy) < minTravelPx) {
            stopEdgeGlide();
            return;
        }
        float zonePx = EDGE_GLIDE_ZONE_DP * density;
        float vx = 0f;
        float vy = 0f;
        if (rx <= zonePx && (rx - downRx) < -minTravelPx * 0.4f) {
            vx = -Math.min(1f, (zonePx - Math.max(0f, rx)) / zonePx + 0.35f);
        } else if (rx >= sw - zonePx && (rx - downRx) > minTravelPx * 0.4f) {
            vx = Math.min(1f, (Math.min(sw, rx) - (sw - zonePx)) / zonePx + 0.35f);
        }
        if (ry <= zonePx && (ry - downRy) < -minTravelPx * 0.4f) {
            vy = -Math.min(1f, (zonePx - Math.max(0f, ry)) / zonePx + 0.35f);
        } else if (ry >= sh - zonePx && (ry - downRy) > minTravelPx * 0.4f) {
            vy = Math.min(1f, (Math.min(sh, ry) - (sh - zonePx)) / zonePx + 0.35f);
        }
        if (vx == 0f && vy == 0f) {
            stopEdgeGlide();
            return;
        }
        edgeGlideVx = vx;
        edgeGlideVy = vy;
        edgeGlideButtonMode = buttonMode;
        if (!edgeGlideActive) {
            edgeGlideActive = true;
            edgeGlideLastMs = SystemClock.uptimeMillis();
            postOnAnimation(edgeGlideTick);
        }
    }

    private void stopEdgeGlide() {
        if (edgeGlideActive) {
            edgeGlideActive = false;
            removeCallbacks(edgeGlideTick);
        }
        edgeGlideVx = 0f;
        edgeGlideVy = 0f;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void attachCoupledPalmDrag(View palm) {
        palm.setClickable(true);
        palm.setFocusable(false);
        float slop = 1.2f * density;
        palm.setOnTouchListener(new OnTouchListener() {
            int pointer = -1, startedGen;
            float downRawX, downRawY, lastRawX, lastRawY;
            long lastTimeMs;
            boolean draggingPalm;

            @Override public boolean onTouch(View v, MotionEvent event) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    stopEdgeGlide();
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
                                input.virtualMouseMove(rx - downRawX, ry - downRawY,
                                        (long) PointerBallistics.FALLBACK_DT_MS);
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
                            updateEdgeGlide(rx, ry, downRawX, downRawY, false);
                        }
                    }
                    return true;
                }
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                        || (action == MotionEvent.ACTION_POINTER_UP
                        && event.getPointerId(event.getActionIndex()) == pointer)) {
                    stopEdgeGlide();
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
        plate.setFocusable(false);
        plate.setOnClickListener(v -> {
            input.virtualButton(buttonCode, true);
            input.virtualButton(buttonCode, false);
        });

        float deadzonePx = PointerBallistics.VIRTUAL_BUTTON_DEADZONE_DP * density;
        plate.setOnTouchListener(new OnTouchListener() {
            int pointer = -1, startedGen;
            float downRawX, downRawY, lastRawX, lastRawY;
            long lastTimeMs;
            boolean dragUnlocked;
            boolean countedStationary;

            @Override public boolean onTouch(View v, MotionEvent event) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    stopEdgeGlide();
                    pointer = event.getPointerId(0);
                    startedGen = generation;
                    downRawX = lastRawX = rawX(v, event, 0);
                    downRawY = lastRawY = rawY(v, event, 0);
                    lastTimeMs = event.getEventTime();
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
                            input.virtualMouseDrag((rx - downRawX) * ratio, (ry - downRawY) * ratio,
                                    (long) PointerBallistics.FALLBACK_DT_MS);
                            lastRawX = rx;
                            lastRawY = ry;
                            lastTimeMs = now;
                        } else {
                            float dx = rx - lastRawX;
                            float dy = ry - lastRawY;
                            if (dx != 0f || dy != 0f) {
                                input.virtualMouseDrag(dx, dy, now - lastTimeMs);
                                lastRawX = rx;
                                lastRawY = ry;
                                lastTimeMs = now;
                            }
                            updateEdgeGlide(rx, ry, downRawX, downRawY, true);
                        }
                    }
                    return true;
                }
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                        || (action == MotionEvent.ACTION_POINTER_UP
                        && event.getPointerId(event.getActionIndex()) == pointer)) {
                    stopEdgeGlide();
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
        box.setFocusable(false);
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
                    stopEdgeGlide();
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
                            input.virtualMouseMove((rx - downRawX) * ratio, (ry - downRawY) * ratio,
                                    (long) PointerBallistics.FALLBACK_DT_MS);
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
                            updateEdgeGlide(rx, ry, downRawX, downRawY, false);
                        }
                    }
                    return true;
                }
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                        || (action == MotionEvent.ACTION_POINTER_UP
                        && event.getPointerId(event.getActionIndex()) == pointer)) {
                    stopEdgeGlide();
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
        wheel.setFocusable(false);
        wheel.setOnClickListener(v -> input.scroll(1));

        int touchSlop = ViewConfiguration.get(ui.context).getScaledTouchSlop();
        float stepPx = 10.5f * density;
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
        stopEdgeGlide();
        input.releaseAll();
    }

    private void resetTouches() {
        stopEdgeGlide();
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

            float s = w / Math.max(1f, 98f * density);
            float u = density * s;
            float stroke = Math.max(1.2f * density, 1.9f * u);
            float seamStroke = Math.max(1.0f * density, 1.45f * u);
            float inset = stroke * 0.5f + 0.5f * u;
            bodyRect.set(inset, inset, w - inset, h - inset);

            float rTop = 37f * u;
            float rBot = 40f * u;
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
            paint.setStrokeWidth(seamStroke);
            paint.setColor(0xFF9AA3B3);
            canvas.drawLine(bodyRect.left, seamY, bodyRect.right, seamY, paint);
            canvas.drawLine(cx, bodyRect.top, cx, seamY, paint);
            canvas.restoreToCount(seamSave);

            // 4. Outer slate-silver border + subtle inner rim highlight
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setColor(0xFF7B8596);
            canvas.drawPath(bodyPath, paint);

            // 5. Center Scroll Wheel stadium pill (scaled proportionally)
            float wheelW = 20f * u;
            float wheelH = 36f * u;
            float wheelTop = 9.0f * u;
            wheelRect.set(cx - wheelW * 0.5f, wheelTop, cx + wheelW * 0.5f, wheelTop + wheelH);
            float wheelR = wheelW * 0.5f;

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(wheelActive ? 0xFFDCE9FF : 0xFFF4F7FD);
            canvas.drawRoundRect(wheelRect, wheelR, wheelR, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(seamStroke);
            paint.setColor(wheelActive ? MobileUi.BLUE : 0xFF8891A2);
            canvas.drawRoundRect(wheelRect, wheelR, wheelR, paint);

            // Solid upward & downward triangles + center pivot dot inside wheel
            paint.setStyle(Paint.Style.FILL);
            float triHalfW = 3.0f * u;
            float triH = 3.8f * u;

            float upTipY = wheelRect.top + 6.4f * u;
            paint.setColor(wheelActive && wheelDir > 0 ? MobileUi.BLUE : 0xFF768092);
            triPath.reset();
            triPath.moveTo(cx, upTipY);
            triPath.lineTo(cx - triHalfW, upTipY + triH);
            triPath.lineTo(cx + triHalfW, upTipY + triH);
            triPath.close();
            canvas.drawPath(triPath, paint);

            paint.setColor(0xFF9AA3B3);
            canvas.drawCircle(cx, wheelRect.centerY(), Math.max(0.9f * density, 1.35f * u), paint);

            float downTipY = wheelRect.bottom - 6.4f * u;
            paint.setColor(wheelActive && wheelDir < 0 ? MobileUi.BLUE : 0xFF768092);
            triPath.reset();
            triPath.moveTo(cx, downTipY);
            triPath.lineTo(cx - triHalfW, downTipY - triH);
            triPath.lineTo(cx + triHalfW, downTipY - triH);
            triPath.close();
            canvas.drawPath(triPath, paint);

            // 6. Center Middle ("=") pill straddling the horizontal seam
            float midW = 20f * u;
            float midH = 21f * u;
            midRect.set(cx - midW * 0.5f, seamY - midH * 0.5f, cx + midW * 0.5f, seamY + midH * 0.5f);
            float midR = 7.5f * u;

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(middlePressed ? MobileUi.BLUE : 0xFFF4F7FD);
            canvas.drawRoundRect(midRect, midR, midR, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(seamStroke);
            paint.setColor(middlePressed ? 0xFF1F61C7 : 0xFF8891A2);
            canvas.drawRoundRect(midRect, midR, midR, paint);

            // Dual parallel "=" strokes
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(Math.max(1.1f * density, 1.65f * u));
            paint.setColor(middlePressed ? Color.WHITE : 0xFF768092);
            float eqHalfW = 3.5f * u;
            float eqGap = 1.9f * u;
            canvas.drawLine(cx - eqHalfW, seamY - eqGap, cx + eqHalfW, seamY - eqGap, paint);
            canvas.drawLine(cx - eqHalfW, seamY + eqGap, cx + eqHalfW, seamY + eqGap, paint);
        }
    }
}
