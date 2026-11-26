package cn.crossdesk.mobile;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * Split-touch floating keymap overlay. Wraps RemoteVideoView at child 0 (or sits transparently
 * above it) so holding a floating key/mouse button with one finger lets another finger control
 * the desktop cursor simultaneously without pointer theft.
 */
@SuppressLint("ViewConstructor")
final class FloatingKeymapOverlay extends FrameLayout {
    interface Host {
        void key(int code, boolean down);
        void pasteClipboardOneTap();
        void openClipboardDialog();
        void openDrawer();
        void toggleKeyboard();
    }

    private final MobileUi ui;
    private final FloatingKeymapStore store;
    private final RemoteMouseInput mouseInput;
    private final Host host;
    private String platform = "windows";
    private boolean suppressed;
    private int generation;
    private final List<ActiveKeyState> activeKeys = new ArrayList<>();
    private final List<WidgetControl> widgetControls = new ArrayList<>();

    private static final class ActiveKeyState {
        final int[] sequence;
        boolean pressed;

        ActiveKeyState(int[] sequence) {
            this.sequence = sequence;
        }
    }

    FloatingKeymapOverlay(Context context, MobileUi ui, FloatingKeymapStore store,
                          RemoteMouseInput mouseInput, Host host) {
        super(context);
        this.ui = ui;
        this.store = store;
        this.mouseInput = mouseInput;
        this.host = host;
        setMotionEventSplittingEnabled(true);
        setClipChildren(false);
        setClipToPadding(false);
        mouseInput.addCancelListener(this::resetMouseVisuals);
    }

    void attachVideo(RemoteVideoView video) {
        addView(video, 0, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    void setPlatform(String value) {
        String next = value == null ? "" : value;
        if (!platform.equals(next)) {
            platform = next;
            rebuild();
        }
    }

    void setSuppressed(boolean value) {
        if (suppressed == value) return;
        suppressed = value;
        if (suppressed) releaseAllInputs();
        updateVisibilityAndPositions();
    }

    void refresh() {
        releaseAllInputs();
        rebuild();
    }

    void releaseAllInputs() {
        generation++;
        for (ActiveKeyState state : activeKeys) {
            if (state.pressed) {
                state.pressed = false;
                for (int i = state.sequence.length - 1; i >= 0; i--) {
                    host.key(state.sequence[i], false);
                }
            }
        }
        for (WidgetControl control : widgetControls) {
            control.resetVisual();
        }
        if (mouseInput.hasVirtualButton()) {
            mouseInput.releaseAll();
        }
    }

    private void resetMouseVisuals() {
        for (WidgetControl control : widgetControls) {
            if (control.isMouseWidget()) control.resetVisual();
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(MotionEvent event) {
        return false;
    }

    private void rebuild() {
        for (WidgetControl control : widgetControls) {
            removeView(control.view);
        }
        widgetControls.clear();
        activeKeys.clear();

        if (!store.isEnabled()) return;
        FloatingKeymapStore.Profile profile = store.activeProfile();
        for (FloatingKeymapStore.Widget widget : profile.widgets) {
            WidgetControl control = createControl(widget);
            widgetControls.add(control);
            addView(control.view, new LayoutParams(control.widthPx, control.heightPx, Gravity.TOP | Gravity.LEFT));
        }
        updateVisibilityAndPositions();
    }

    private void updateVisibilityAndPositions() {
        boolean show = store.isEnabled() && !suppressed;
        int w = getWidth();
        int h = getHeight();
        int margin = ui.dp(6);
        for (WidgetControl control : widgetControls) {
            control.view.setVisibility(show ? VISIBLE : GONE);
            if (show && w > 0 && h > 0) {
                float cx = control.widget.normX * w;
                float cy = control.widget.normY * h;
                float x = clamp(cx - control.widthPx / 2f, margin, Math.max(margin, w - control.widthPx - margin));
                float y = clamp(cy - control.heightPx / 2f, margin, Math.max(margin, h - control.heightPx - margin));
                control.view.setTranslationX(x);
                control.view.setTranslationY(y);
            }
        }
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        post(this::updateVisibilityAndPositions);
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        updateVisibilityAndPositions();
    }

    @Override protected void onDetachedFromWindow() {
        releaseAllInputs();
        super.onDetachedFromWindow();
    }

    @Override public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus) releaseAllInputs();
    }

    private int[] buildKeySequence(FloatingKeymapStore.Widget widget) {
        if (widget.type == FloatingKeymapStore.WidgetType.SINGLE_KEY) {
            int k = KeyCatalog.normalizeVk(widget.key);
            return k > 0 ? new int[]{k} : new int[0];
        }
        int[] mods = KeyCatalog.normalizeModifiers(widget.modifiers);
        int main = KeyCatalog.normalizeVk(widget.key);
        boolean includeMain = main > 0;
        for (int m : mods) {
            if (m == main) {
                includeMain = false;
                break;
            }
        }
        int[] seq = new int[mods.length + (includeMain ? 1 : 0)];
        System.arraycopy(mods, 0, seq, 0, mods.length);
        if (includeMain) seq[mods.length] = main;
        return seq;
    }

    @SuppressLint("ClickableViewAccessibility")
    private WidgetControl createControl(FloatingKeymapStore.Widget widget) {
        float scale = store.globalScale();
        int baseDp = Math.max(FloatingKeymapStore.MIN_SIZE_DP,
                Math.min(FloatingKeymapStore.MAX_SIZE_DP, Math.round(widget.sizeDp * scale)));
        boolean isWheel = widget.type == FloatingKeymapStore.WidgetType.SCROLL_WHEEL;
        int widthPx = ui.dp(isWheel ? Math.max(36, Math.round(baseDp * 0.84f)) : baseDp);
        int heightPx = ui.dp(isWheel ? Math.round(baseDp * 1.95f) : baseDp);

        View view;
        if (isWheel) {
            view = new FloatingWheelPill(getContext(), ui, widget.displayLabel(platform));
        } else {
            TextView btn = new TextView(getContext());
            btn.setGravity(Gravity.CENTER);
            btn.setTextColor(0xFFF5F5F7);
            btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            btn.setIncludeFontPadding(false);
            btn.setMaxLines(2);
            btn.setEllipsize(TextUtils.TruncateAt.END);
            btn.setPadding(ui.dp(4), ui.dp(3), ui.dp(4), ui.dp(3));
            String text = widget.displayLabel(platform);
            btn.setText(text);
            btn.setAutoSizeTextTypeUniformWithConfiguration(8, 13, 1, TypedValue.COMPLEX_UNIT_SP);
            view = btn;
        }
        view.setContentDescription(widget.displayLabel(platform));
        view.setClickable(true);
        view.setFocusable(true);
        view.setAlpha(widget.opacity);

        WidgetControl control = new WidgetControl(widget, view, widthPx, heightPx);
        control.applyHighlight(false);

        if (widget.type == FloatingKeymapStore.WidgetType.SINGLE_KEY
                || widget.type == FloatingKeymapStore.WidgetType.COMBO_KEY) {
            int[] seq = buildKeySequence(widget);
            ActiveKeyState keyState = new ActiveKeyState(seq);
            activeKeys.add(keyState);
            view.setOnTouchListener(new OnTouchListener() {
                int pointer = -1;
                int startedGen;

                @Override public boolean onTouch(View v, MotionEvent event) {
                    int action = event.getActionMasked();
                    if (action == MotionEvent.ACTION_DOWN) {
                        pointer = event.getPointerId(0);
                        startedGen = generation;
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        control.applyHighlight(true);
                        if (!keyState.pressed && seq.length > 0) {
                            keyState.pressed = true;
                            for (int code : seq) host.key(code, true);
                        }
                        return true;
                    }
                    if (startedGen != generation || pointer == -1) return true;
                    if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                            || (action == MotionEvent.ACTION_POINTER_UP
                            && event.getPointerId(event.getActionIndex()) == pointer)) {
                        pointer = -1;
                        control.applyHighlight(false);
                        if (keyState.pressed) {
                            keyState.pressed = false;
                            for (int i = seq.length - 1; i >= 0; i--) host.key(seq[i], false);
                        }
                    }
                    return true;
                }
            });
        } else if (widget.type == FloatingKeymapStore.WidgetType.MOUSE_LEFT
                || widget.type == FloatingKeymapStore.WidgetType.MOUSE_RIGHT
                || widget.type == FloatingKeymapStore.WidgetType.MOUSE_MIDDLE) {
            int buttonIndex = widget.type == FloatingKeymapStore.WidgetType.MOUSE_LEFT ? 0
                    : widget.type == FloatingKeymapStore.WidgetType.MOUSE_RIGHT ? 1 : 2;
            float deadzonePx = ui.dp(PointerBallistics.VIRTUAL_BUTTON_DEADZONE_DP);
            view.setOnTouchListener(new OnTouchListener() {
                int pointer = -1;
                int startedGen;
                float downX, downY, lastX, lastY;
                long downTimeMs, lastTimeMs;
                boolean dragUnlocked;

                @Override public boolean onTouch(View v, MotionEvent event) {
                    int action = event.getActionMasked();
                    if (action == MotionEvent.ACTION_DOWN) {
                        pointer = event.getPointerId(0);
                        startedGen = generation;
                        downX = lastX = event.getRawX();
                        downY = lastY = event.getRawY();
                        downTimeMs = lastTimeMs = event.getEventTime();
                        dragUnlocked = false;
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        control.applyHighlight(true);
                        mouseInput.virtualButton(buttonIndex, true);
                        return true;
                    }
                    if (startedGen != generation || pointer == -1) return true;
                    if (action == MotionEvent.ACTION_MOVE) {
                        int p = event.findPointerIndex(pointer);
                        if (p >= 0) {
                            float x = event.getRawX() + (event.getX(p) - event.getX());
                            float y = event.getRawY() + (event.getY(p) - event.getY());
                            long now = event.getEventTime();
                            if (!dragUnlocked) {
                                float dist = (float) Math.hypot(x - downX, y - downY);
                                if (dist < deadzonePx) return true;
                                dragUnlocked = true;
                                float ratio = Math.max(0f, (dist - deadzonePx) / Math.max(0.001f, dist));
                                mouseInput.drag((x - downX) * ratio, (y - downY) * ratio, now - downTimeMs);
                                lastX = x;
                                lastY = y;
                                lastTimeMs = now;
                            } else {
                                mouseInput.drag(x - lastX, y - lastY, now - lastTimeMs);
                                lastX = x;
                                lastY = y;
                                lastTimeMs = now;
                            }
                        }
                        return true;
                    }
                    if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                            || (action == MotionEvent.ACTION_POINTER_UP
                            && event.getPointerId(event.getActionIndex()) == pointer)) {
                        pointer = -1;
                        dragUnlocked = false;
                        control.applyHighlight(false);
                        mouseInput.virtualButton(buttonIndex, false);
                    }
                    return true;
                }
            });
        } else if (isWheel) {
            FloatingWheelPill wheelView = (FloatingWheelPill) view;
            int touchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
            PointerBallistics.ScrollAccumulator wheelAccumulator = new PointerBallistics.ScrollAccumulator();
            float stepPx = ui.dp(8.5f);
            view.setOnTouchListener(new OnTouchListener() {
                int pointer = -1;
                int startedGen;
                float downY, lastY;
                boolean scrolled;

                @Override public boolean onTouch(View v, MotionEvent event) {
                    int action = event.getActionMasked();
                    if (action == MotionEvent.ACTION_DOWN) {
                        pointer = event.getPointerId(0);
                        startedGen = generation;
                        downY = lastY = event.getRawY();
                        wheelAccumulator.reset();
                        scrolled = false;
                        control.applyHighlight(true);
                        return true;
                    }
                    if (startedGen != generation || pointer == -1) return true;
                    if (action == MotionEvent.ACTION_MOVE) {
                        int p = event.findPointerIndex(pointer);
                        if (p >= 0) {
                            float y = event.getRawY() + (event.getY(p) - event.getY());
                            float dy = y - lastY;
                            lastY = y;
                            if (Math.abs(y - downY) > touchSlop) scrolled = true;
                            wheelView.advance(dy);
                            int ticks = wheelAccumulator.consume(dy, stepPx, false);
                            if (ticks != 0) {
                                scrolled = true;
                                int dir = ticks > 0 ? 1 : -1;
                                for (int i = 0; i < Math.abs(ticks); i++) {
                                    mouseInput.scroll(dir);
                                }
                                v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                            }
                        }
                        return true;
                    }
                    if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                            || (action == MotionEvent.ACTION_POINTER_UP
                            && event.getPointerId(event.getActionIndex()) == pointer)) {
                        if (action == MotionEvent.ACTION_UP && !scrolled) {
                            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                            boolean topHalf = event.getY() < v.getHeight() / 2f;
                            wheelView.advance(topHalf ? -ui.dp(10) : ui.dp(10));
                            mouseInput.scroll(topHalf ? 1 : -1);
                        }
                        pointer = -1;
                        wheelAccumulator.reset();
                        control.applyHighlight(false);
                    }
                    return true;
                }
            });
        } else if (widget.type == FloatingKeymapStore.WidgetType.ACTION_CLIPBOARD) {
            view.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                host.pasteClipboardOneTap();
            });
            view.setOnLongClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                host.openClipboardDialog();
                return true;
            });
            attachTapVisualFeedback(view, control);
        } else if (widget.type == FloatingKeymapStore.WidgetType.ACTION_DRAWER) {
            view.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                host.openDrawer();
            });
            attachTapVisualFeedback(view, control);
        } else if (widget.type == FloatingKeymapStore.WidgetType.ACTION_KEYBOARD) {
            view.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                host.toggleKeyboard();
            });
            attachTapVisualFeedback(view, control);
        }
        return control;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void attachTapVisualFeedback(View view, WidgetControl control) {
        view.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) control.applyHighlight(true);
            else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) control.applyHighlight(false);
            return false;
        });
    }

    private final class WidgetControl {
        final FloatingKeymapStore.Widget widget;
        final View view;
        final int widthPx;
        final int heightPx;

        WidgetControl(FloatingKeymapStore.Widget widget, View view, int widthPx, int heightPx) {
            this.widget = widget;
            this.view = view;
            this.widthPx = widthPx;
            this.heightPx = heightPx;
        }

        boolean isMouseWidget() {
            return widget.type == FloatingKeymapStore.WidgetType.MOUSE_LEFT
                    || widget.type == FloatingKeymapStore.WidgetType.MOUSE_RIGHT
                    || widget.type == FloatingKeymapStore.WidgetType.MOUSE_MIDDLE
                    || widget.type == FloatingKeymapStore.WidgetType.SCROLL_WHEEL;
        }

        void resetVisual() {
            applyHighlight(false);
        }

        void applyHighlight(boolean pressed) {
            int radiusDp = Math.round(Math.min(widthPx, heightPx) / (2f * getResources().getDisplayMetrics().density));
            GradientDrawable shape = ui.background(pressed ? MobileUi.BLUE : 0xCC1C1C22, radiusDp);
            shape.setStroke(Math.max(1, ui.dp(1f)), pressed ? 0x99FFFFFF : 0x42FFFFFF);
            view.setBackground(shape);
            view.setScaleX(pressed ? 0.96f : 1.0f);
            view.setScaleY(pressed ? 0.96f : 1.0f);
        }
    }

    /** Crisp linear vector vertical wheel pill for SCROLL_WHEEL floating widget. */
    static final class FloatingWheelPill extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF treadClip = new RectF();
        private final float density;
        private float phase;

        FloatingWheelPill(Context context, MobileUi ui, String description) {
            super(context);
            density = context.getResources().getDisplayMetrics().density;
            setContentDescription(description);
        }

        void advance(float dy) {
            phase = (phase + dy / density * 0.6f) % 6f;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;

            paint.setColor(0xEBFFFFFF);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.7f * density);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);

            float cx = w / 2f;
            float chevW = 4.5f * density;
            float chevH = 3.5f * density;
            float topY = 10f * density;
            path.reset();
            path.moveTo(cx - chevW, topY + chevH);
            path.lineTo(cx, topY);
            path.lineTo(cx + chevW, topY + chevH);
            canvas.drawPath(path, paint);

            float botY = h - 10f * density;
            path.reset();
            path.moveTo(cx - chevW, botY - chevH);
            path.lineTo(cx, botY);
            path.lineTo(cx + chevW, botY - chevH);
            canvas.drawPath(path, paint);

            float trackTop = topY + chevH + 6f * density;
            float trackBot = botY - chevH - 6f * density;
            if (trackBot > trackTop + 8f * density) {
                treadClip.set(cx - 6f * density, trackTop, cx + 6f * density, trackBot);
                int save = canvas.save();
                canvas.clipRect(treadClip);
                paint.setColor(0x99FFFFFF);
                paint.setStrokeWidth(1.5f * density);
                float step = 6f * density;
                for (float y = trackTop - step + phase * density; y <= trackBot + step; y += step) {
                    canvas.drawLine(cx - 5f * density, y, cx + 5f * density, y, paint);
                }
                canvas.restoreToCount(save);
            }
        }
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
