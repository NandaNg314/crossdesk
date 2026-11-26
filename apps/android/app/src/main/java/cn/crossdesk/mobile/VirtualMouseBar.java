package cn.crossdesk.mobile;

import android.annotation.SuppressLint;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Expandable two-state floating virtual mouse:
 * 1) Collapsed: 44x44 dp semi-transparent draggable circular mouse button; tap to expand.
 * 2) Expanded: 156x148 dp ergonomic pad with top drag bar + close button, Left/Right drag buttons,
 *    and center Scroll Up / Middle drag-click / Scroll Down column with continuous swipe scrolling.
 */
@SuppressLint("ViewConstructor")
final class VirtualMouseBar extends FrameLayout {
    static final int COLLAPSED_SIZE_DP = 44;
    static final int EXPANDED_WIDTH_DP = 156;
    static final int EXPANDED_HEIGHT_DP = 148;
    // Keep legacy constants mapped to expanded size for callers inspecting defaults.
    static final int WIDTH_DP = EXPANDED_WIDTH_DP;
    static final int HEIGHT_DP = EXPANDED_HEIGHT_DP;

    interface PositionHost {
        void moveBy(float dx, float dy);
        void onStateChanged();
    }

    final FrameLayout dragHandle;
    final FrameLayout collapsedButton;
    private final LinearLayout expandedShell;
    private final RemoteMouseInput input;
    private final MobileUi ui;
    private PositionHost positionHost;
    private boolean expanded;

    private final TextView leftBtn;
    private final TextView rightBtn;
    private final FrameLayout scrollUpBtn;
    private final FrameLayout middleBtn;
    private final FrameLayout scrollDownBtn;
    private int generation;

    VirtualMouseBar(MobileUi ui, RemoteMouseInput input) {
        super(ui.context);
        this.ui = ui;
        this.input = input;
        setMotionEventSplittingEnabled(true);
        setClipChildren(false);
        setClipToPadding(false);

        // 1. Collapsed 44x44 dp circular mouse button
        collapsedButton = new FrameLayout(ui.context);
        GradientDrawable orbBg = ui.background(0xCC1C1C22, 22);
        orbBg.setStroke(Math.max(1, ui.dp(1f)), 0x3DFFFFFF);
        collapsedButton.setBackground(orbBg);
        collapsedButton.setContentDescription(L10n.tr("展开虚拟鼠标"));
        collapsedButton.addView(ui.icon("mouse", Color.WHITE),
                new LayoutParams(ui.dp(21), ui.dp(21), Gravity.CENTER));
        attachDragGesture(collapsedButton, () -> setExpanded(true));
        addView(collapsedButton, new LayoutParams(ui.dp(COLLAPSED_SIZE_DP), ui.dp(COLLAPSED_SIZE_DP)));

        // 2. Expanded 156x148 dp mouse pad
        expandedShell = ui.column();
        GradientDrawable padBg = ui.background(0xE318181D, 18);
        padBg.setStroke(Math.max(1, ui.dp(0.8f)), 0x30FFFFFF);
        expandedShell.setBackground(padBg);
        expandedShell.setMotionEventSplittingEnabled(true);
        expandedShell.setVisibility(GONE);

        // Top drag & collapse header (28 dp)
        LinearLayout header = ui.row();
        header.setPadding(ui.dp(8), 0, ui.dp(4), 0);
        dragHandle = new FrameLayout(ui.context);
        dragHandle.setContentDescription(L10n.tr("拖动虚拟鼠标"));
        View grip = new View(ui.context);
        grip.setBackground(ui.background(0x77FFFFFF, 2));
        dragHandle.addView(grip, new LayoutParams(ui.dp(28), ui.dp(3.5f), Gravity.CENTER));
        attachDragGesture(dragHandle, null);
        header.addView(dragHandle, new LinearLayout.LayoutParams(0, ui.dp(28), 1f));

        FrameLayout collapseBtn = new FrameLayout(ui.context);
        collapseBtn.setContentDescription(L10n.tr("收起虚拟鼠标"));
        collapseBtn.setClickable(true);
        collapseBtn.setFocusable(true);
        collapseBtn.addView(ui.icon("close", 0xFFD1D1D6),
                new LayoutParams(ui.dp(15), ui.dp(15), Gravity.CENTER));
        collapseBtn.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            cancelInput();
            setExpanded(false);
        });
        header.addView(collapseBtn, new LinearLayout.LayoutParams(ui.dp(28), ui.dp(28)));
        expandedShell.addView(header, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, ui.dp(28)));

        // Pad body (120 dp)
        LinearLayout body = ui.row();
        body.setPadding(ui.dp(5), ui.dp(2), ui.dp(5), ui.dp(5));
        body.setMotionEventSplittingEnabled(true);

        leftBtn = createPrimaryMouseButton("L", L10n.tr("鼠标左键，按住并拖动，松开结束拖拽"), RemoteMouseInput.BUTTON_LEFT, true);
        rightBtn = createPrimaryMouseButton("R", L10n.tr("鼠标右键，按住并拖动，松开结束拖拽"), RemoteMouseInput.BUTTON_RIGHT, false);

        LinearLayout centerCol = ui.column();
        centerCol.setMotionEventSplittingEnabled(true);
        scrollUpBtn = createScrollCap(true);
        middleBtn = createMiddleButton();
        scrollDownBtn = createScrollCap(false);

        LinearLayout.LayoutParams topLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f);
        LinearLayout.LayoutParams midLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, ui.dp(38));
        midLp.topMargin = ui.dp(3);
        midLp.bottomMargin = ui.dp(3);
        LinearLayout.LayoutParams botLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f);

        centerCol.addView(scrollUpBtn, topLp);
        centerCol.addView(middleBtn, midLp);
        centerCol.addView(scrollDownBtn, botLp);

        body.addView(leftBtn, new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));
        LinearLayout.LayoutParams centerLp = new LinearLayout.LayoutParams(ui.dp(40), LayoutParams.MATCH_PARENT);
        centerLp.leftMargin = ui.dp(4);
        centerLp.rightMargin = ui.dp(4);
        body.addView(centerCol, centerLp);
        body.addView(rightBtn, new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));

        expandedShell.addView(body, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
        addView(expandedShell, new LayoutParams(ui.dp(EXPANDED_WIDTH_DP), ui.dp(EXPANDED_HEIGHT_DP)));

        input.addCancelListener(this::resetTouches);
        setContentDescription(L10n.tr("虚拟鼠标"));
    }

    void setPositionHost(PositionHost host) {
        this.positionHost = host;
    }

    boolean isExpanded() { return expanded; }

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

    @SuppressLint("ClickableViewAccessibility")
    private void attachDragGesture(View handle, Runnable onTap) {
        handle.setClickable(true);
        handle.setFocusable(true);
        if (onTap != null) handle.setOnClickListener(v -> onTap.run());
        int slop = ui.dp(4);
        handle.setOnTouchListener(new OnTouchListener() {
            int pointer = -1;
            float lastRawX, lastRawY, totalDx, totalDy;
            boolean moved;

            @Override public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        pointer = event.getPointerId(0);
                        lastRawX = event.getRawX();
                        lastRawY = event.getRawY();
                        totalDx = totalDy = 0;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (event.getPointerId(0) != pointer) break;
                        float dx = event.getRawX() - lastRawX;
                        float dy = event.getRawY() - lastRawY;
                        lastRawX = event.getRawX();
                        lastRawY = event.getRawY();
                        totalDx += dx;
                        totalDy += dy;
                        if (!moved && Math.hypot(totalDx, totalDy) <= slop) break;
                        if (!moved) {
                            moved = true;
                            if (positionHost != null) positionHost.moveBy(totalDx, totalDy);
                        } else if (positionHost != null) {
                            positionHost.moveBy(dx, dy);
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_POINTER_UP:
                        if (event.getPointerId(event.getActionIndex()) == pointer) {
                            if (!moved && onTap != null) {
                                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                                v.performClick();
                            }
                            pointer = -1;
                        }
                        break;
                    case MotionEvent.ACTION_CANCEL:
                        pointer = -1;
                        break;
                }
                return true;
            }
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    private TextView createPrimaryMouseButton(String label, String desc, int buttonCode, boolean leftSide) {
        TextView btn = new TextView(ui.context);
        btn.setText(label);
        btn.setTextSize(12);
        btn.setGravity(Gravity.CENTER);
        btn.setContentDescription(desc);
        btn.setClickable(true);
        btn.setFocusable(true);
        stylePrimaryButton(btn, leftSide, false);

        btn.setOnClickListener(v -> {
            input.virtualButton(buttonCode, true);
            input.virtualButton(buttonCode, false);
        });
        btn.setOnTouchListener(new OnTouchListener() {
            int pointer = -1, startedGen;
            float lastX, lastY;

            @Override public boolean onTouch(View v, MotionEvent event) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    pointer = event.getPointerId(0);
                    startedGen = generation;
                    lastX = event.getRawX();
                    lastY = event.getRawY();
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    stylePrimaryButton(btn, leftSide, true);
                    input.virtualButton(buttonCode, true);
                    return true;
                }
                if (startedGen != generation || pointer == -1) return true;
                if (action == MotionEvent.ACTION_MOVE) {
                    int p = event.findPointerIndex(pointer);
                    if (p >= 0) {
                        float x = event.getRawX() + (event.getX(p) - event.getX());
                        float y = event.getRawY() + (event.getY(p) - event.getY());
                        input.drag(x - lastX, y - lastY);
                        lastX = x;
                        lastY = y;
                    }
                    return true;
                }
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                        || (action == MotionEvent.ACTION_POINTER_UP
                        && event.getPointerId(event.getActionIndex()) == pointer)) {
                    pointer = -1;
                    stylePrimaryButton(btn, leftSide, false);
                    input.virtualButton(buttonCode, false);
                }
                return true;
            }
        });
        return btn;
    }

    @SuppressLint("ClickableViewAccessibility")
    private FrameLayout createMiddleButton() {
        FrameLayout box = new FrameLayout(ui.context);
        box.setContentDescription(L10n.tr("鼠标中键，轻触点击或按住拖动"));
        box.setClickable(true);
        box.setFocusable(true);
        View dot = new View(ui.context) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas c) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.7f * getResources().getDisplayMetrics().density);
                p.setColor(0xE6FFFFFF);
                float r = Math.min(getWidth(), getHeight()) * 0.28f;
                c.drawCircle(getWidth() / 2f, getHeight() / 2f, r, p);
            }
        };
        box.addView(dot, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        styleCenterButton(box, false);

        box.setOnClickListener(v -> {
            input.virtualMiddle(true);
            input.virtualMiddle(false);
        });
        int touchSlop = ViewConfiguration.get(ui.context).getScaledTouchSlop();
        box.setOnTouchListener(new OnTouchListener() {
            int pointer = -1, startedGen;
            float lastX, lastY;

            @Override public boolean onTouch(View v, MotionEvent event) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    pointer = event.getPointerId(0);
                    startedGen = generation;
                    lastX = event.getRawX();
                    lastY = event.getRawY();
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    styleCenterButton(box, true);
                    input.virtualMiddle(true);
                    return true;
                }
                if (startedGen != generation || pointer == -1) return true;
                if (action == MotionEvent.ACTION_MOVE) {
                    int p = event.findPointerIndex(pointer);
                    if (p >= 0) {
                        float x = event.getRawX() + (event.getX(p) - event.getX());
                        float y = event.getRawY() + (event.getY(p) - event.getY());
                        if (Math.hypot(x - lastX, y - lastY) > 0.2f || touchSlop >= 0) {
                            input.drag(x - lastX, y - lastY);
                            lastX = x;
                            lastY = y;
                        }
                    }
                    return true;
                }
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                        || (action == MotionEvent.ACTION_POINTER_UP
                        && event.getPointerId(event.getActionIndex()) == pointer)) {
                    pointer = -1;
                    styleCenterButton(box, false);
                    input.virtualMiddle(false);
                }
                return true;
            }
        });
        return box;
    }

    @SuppressLint("ClickableViewAccessibility")
    private FrameLayout createScrollCap(boolean up) {
        FrameLayout box = new FrameLayout(ui.context);
        box.setContentDescription(up ? L10n.tr("向上滚动，可上下滑动连续滚轮") : L10n.tr("向下滚动，可上下滑动连续滚轮"));
        box.setClickable(true);
        box.setFocusable(true);
        box.addView(ui.icon(up ? "chevron_up" : "chevron_down", 0xDDFFFFFF),
                new LayoutParams(ui.dp(15), ui.dp(15), Gravity.CENTER));
        styleCenterButton(box, false);

        int touchSlop = ViewConfiguration.get(ui.context).getScaledTouchSlop();
        box.setOnClickListener(v -> input.scroll(up ? 1 : -1));
        box.setOnTouchListener(new OnTouchListener() {
            int pointer = -1, startedGen;
            float downY, lastY, remainder;
            boolean swiped;

            @Override public boolean onTouch(View v, MotionEvent event) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    pointer = event.getPointerId(0);
                    startedGen = generation;
                    downY = lastY = event.getRawY();
                    remainder = 0;
                    swiped = false;
                    styleCenterButton(box, true);
                    return true;
                }
                if (startedGen != generation || pointer == -1) return true;
                if (action == MotionEvent.ACTION_MOVE) {
                    int p = event.findPointerIndex(pointer);
                    if (p >= 0) {
                        float y = event.getRawY() + (event.getY(p) - event.getY());
                        float dy = y - lastY;
                        lastY = y;
                        if (Math.abs(y - downY) > touchSlop) swiped = true;
                        remainder += dy;
                        float step = ui.dp(10);
                        int ticks = (int) (remainder / step);
                        for (int i = 0; i < Math.min(Math.abs(ticks), 32); i++) {
                            input.scroll(ticks > 0 ? -1 : 1);
                        }
                        remainder -= ticks * step;
                    }
                    return true;
                }
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                        || (action == MotionEvent.ACTION_POINTER_UP
                        && event.getPointerId(event.getActionIndex()) == pointer)) {
                    if (action == MotionEvent.ACTION_UP && !swiped) {
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        input.scroll(up ? 1 : -1);
                    }
                    pointer = -1;
                    remainder = 0;
                    styleCenterButton(box, false);
                }
                return true;
            }
        });
        return box;
    }

    private void stylePrimaryButton(TextView btn, boolean leftSide, boolean pressed) {
        btn.setPressed(pressed);
        btn.setTextColor(pressed ? Color.WHITE : 0x88FFFFFF);
        GradientDrawable face = ui.background(pressed ? MobileUi.BLUE : 0xFF282830, 8);
        float outer = ui.dp(13), inner = ui.dp(6);
        float tl = leftSide ? outer : inner;
        float tr = leftSide ? inner : outer;
        face.setCornerRadii(new float[]{tl, tl, tr, tr, tr, tr, tl, tl});
        face.setStroke(Math.max(1, ui.dp(0.6f)), pressed ? 0x66FFFFFF : 0x1EFFFFFF);
        btn.setBackground(face);
    }

    private void styleCenterButton(View view, boolean pressed) {
        view.setPressed(pressed);
        GradientDrawable face = ui.background(pressed ? MobileUi.BLUE : 0xFF282830, 7);
        face.setStroke(Math.max(1, ui.dp(0.6f)), pressed ? 0x66FFFFFF : 0x1EFFFFFF);
        view.setBackground(face);
    }

    void cancelInput() {
        input.releaseAll();
    }

    private void resetTouches() {
        generation++;
        stylePrimaryButton(leftBtn, true, false);
        stylePrimaryButton(rightBtn, false, false);
        styleCenterButton(middleBtn, false);
        styleCenterButton(scrollUpBtn, false);
        styleCenterButton(scrollDownBtn, false);
    }

    @Override protected void onDetachedFromWindow() {
        cancelInput();
        super.onDetachedFromWindow();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused) cancelInput();
    }
}
