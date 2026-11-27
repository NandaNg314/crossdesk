package cn.crossdesk.mobile;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.util.function.IntConsumer;

/**
 * Safe-area session chrome:
 * - Edge Quick-Action Dock (snaps to right or left edge, vertically draggable, collapsible)
 * - Slide-out Side Control Drawer
 * - 3-tab Docked RemoteKeyboard
 * - Full-screen FloatingKeymapEditor host
 */
@SuppressLint("ViewConstructor")
final class SessionControls extends FrameLayout {
    interface Actions {
        void disconnect();
        void display();
        void clipboard();
        default void pasteClipboardOneTap() { }
        void key(int code);
        default void keyHold(int code, boolean down) {
            if (down) key(code);
        }
        void chord(int code, int[] modifiers);
        default void lockWorkstation() { }
        default void showDesktop() { }
        default void showAllWindows() { }
        default void cycleOrientation() { }
        default String orientationLabel() { return L10n.tr("自动"); }
        default boolean isOrientationLocked() { return false; }
        default void toggleViewOnly() { }
        default boolean isViewOnly() { return false; }
        default void setRelativeMouse(boolean relative) { }
        default void onMousePreferencesChanged() { }
        void text(String value);
        void mute();
        void mouse();
        default boolean textEdit(int deletions, String value) { return false; }
        void videoSettings(int field, int value);
        default void displayResolution(RemoteDisplayResolutions.Mode mode) { }
        void sendFile();
        void saveFile();
        void secureAttention();
        default void togglePrivacy() { }
    }

    private final MobileUi ui;
    private final FloatingKeymapStore keymapStore;
    private final Actions actions;
    private final Runnable keymapUpdated;
    private final SharedPreferences dockPrefs;
    private final SharedPreferences settingsPrefs;

    private final LinearLayout waiting;
    private final LinearLayout edgeDock;
    private final FrameLayout dockCollapseBtn;
    private final MobileUi.Icon dockCollapseIcon;
    private final LinearLayout dockButtonsBox;

    private View drawerScrim;
    private final SideControlDrawer drawer;
    private boolean drawerOpen;

    private FloatingKeymapEditor keymapEditor;

    private final RemoteKeyboard keyboard;
    private final IntConsumer keyboardInsetChanged;
    private java.util.function.Consumer<Boolean> expandedMouseVisibilityListener;
    private VirtualMouseBar mouseBar;
    private float collapsedMouseX = Float.NaN, collapsedMouseY = Float.NaN;
    private float collapsedNormX = Float.NaN, collapsedNormY = Float.NaN;
    private float lastCursorX = Float.NaN, lastCursorY = Float.NaN;
    private boolean relativeMode = true;
    private boolean mouseVisible = true, pictureInPicture, viewOnly;
    private boolean lastNotifiedExpandedMouseVisible;
    private String hostPlatform = "windows";

    private boolean dockRight = true;
    private boolean dockCollapsed = false;
    private float dockNormY = 0.28f;

    private final ViewTreeObserver.OnGlobalLayoutListener legacyKeyboardLayout = this::legacyKeyboardLayout;

    private void legacyKeyboardLayout() {
        if (android.os.Build.VERSION.SDK_INT >= 30) return;
        android.graphics.Rect visible = new android.graphics.Rect();
        getWindowVisibleDisplayFrame(visible);
        int height = Math.max(0, getRootView().getHeight() - visible.bottom);
        keyboardInsets(height, height > ui.dp(80), 0);
    }

    SessionControls(Context context, MobileUi ui, FloatingKeymapStore keymapStore,
                    IntConsumer keyboardInsetChanged, Runnable keymapUpdated, Actions actions) {
        super(context);
        this.ui = ui;
        this.keymapStore = keymapStore;
        this.keyboardInsetChanged = keyboardInsetChanged;
        this.keymapUpdated = keymapUpdated;
        this.actions = actions;
        setMotionEventSplittingEnabled(true);
        setClipChildren(false);
        setClipToPadding(false);

        dockPrefs = context.getSharedPreferences("session_dock_v1", Context.MODE_PRIVATE);
        settingsPrefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        dockRight = dockPrefs.getBoolean("dockRight", true);
        dockNormY = Math.max(0.08f, Math.min(0.88f, dockPrefs.getFloat("dockNormY", 0.28f)));
        relativeMode = settingsPrefs.getBoolean(PointerBallistics.KEY_RELATIVE_MOUSE, true);
        mouseVisible = PointerBallistics.loadVirtualMouseVisible(settingsPrefs, relativeMode);
        if (settingsPrefs.contains(PointerBallistics.KEY_VIRTUAL_MOUSE_NORM_X)
                && settingsPrefs.contains(PointerBallistics.KEY_VIRTUAL_MOUSE_NORM_Y)) {
            collapsedNormX = bound(settingsPrefs.getFloat(PointerBallistics.KEY_VIRTUAL_MOUSE_NORM_X, 0.90f), 0.03f, 0.97f);
            collapsedNormY = bound(settingsPrefs.getFloat(PointerBallistics.KEY_VIRTUAL_MOUSE_NORM_Y, 0.52f), 0.05f, 0.95f);
        }

        keyboard = new RemoteKeyboard(context, ui, actions, () -> {
            requestLayout();
            positionMouse();
            positionEdgeDock();
        });
        addView(keyboard, new LayoutParams(LayoutParams.MATCH_PARENT, ui.dp(44), Gravity.BOTTOM));

        // Waiting indicator before first video frame
        waiting = ui.column();
        waiting.setMinimumWidth(ui.dp(200));
        waiting.setGravity(Gravity.CENTER);
        waiting.setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(20));
        waiting.setBackground(ui.background(0xB0202024, 14));
        ProgressBar progress = new ProgressBar(context);
        progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        waiting.addView(progress, ui.size(28, 28));
        ui.gap(waiting, 12);
        TextView message = ui.text(L10n.tr("等待远端画面…"), 14, false);
        message.setTextColor(Color.WHITE);
        message.setGravity(Gravity.CENTER);
        waiting.addView(message);
        addView(waiting, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        // Edge Quick-Action Dock
        edgeDock = ui.column();
        edgeDock.setGravity(Gravity.CENTER_HORIZONTAL);
        edgeDock.setElevation(ui.dp(9));

        dockCollapseBtn = new FrameLayout(context);
        dockCollapseBtn.setClickable(true);
        dockCollapseBtn.setFocusable(true);
        dockCollapseIcon = ui.icon("chevron_right", 0xFFD1D1D6);
        dockCollapseBtn.addView(dockCollapseIcon, new LayoutParams(ui.dp(14), ui.dp(14), Gravity.CENTER));
        attachDockDragAndTap(dockCollapseBtn, this::toggleDockCollapsed);
        edgeDock.addView(dockCollapseBtn, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(22)));

        dockButtonsBox = ui.column();
        dockButtonsBox.addView(createDockAction("controls", L10n.tr("操作"), this::openDrawer));
        dockButtonsBox.addView(createDockAction("keyboard", L10n.tr("键盘"), this::toggleKeyboard));
        dockButtonsBox.addView(createDockAction("desktop", L10n.tr("显示桌面"), actions::showDesktop));
        dockButtonsBox.addView(createDockAction("multitask", L10n.tr("所有窗口"), actions::showAllWindows));
        edgeDock.addView(dockButtonsBox, new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        styleEdgeDock();
        addView(edgeDock, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT));

        // Slide-out Side Control Drawer + Scrim
        drawerScrim = new View(context);
        drawerScrim.setBackgroundColor(0x66000000);
        drawerScrim.setVisibility(GONE);
        drawerScrim.setAlpha(0f);
        drawerScrim.setOnClickListener(v -> closeDrawer());
        addView(drawerScrim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        drawer = new SideControlDrawer(context, ui, keymapStore, settingsPrefs, actions, new SideControlDrawer.Host() {
            @Override public void closeDrawer() { SessionControls.this.closeDrawer(); }
            @Override public void openKeymapEditor() { SessionControls.this.openKeymapEditor(); }
            @Override public void onKeymapChanged() { keymapUpdated.run(); }
            @Override public void toggleVirtualMouse() { toggleMouse(); }
            @Override public void setVirtualMouseVisible(boolean visible) { SessionControls.this.setVirtualMouseVisible(visible); }
            @Override public boolean isVirtualMouseVisible() { return mouseVisible; }
        });
        drawer.setVisibility(GONE);
        addView(drawer, new LayoutParams(ui.dp(336), LayoutParams.MATCH_PARENT, Gravity.END));
    }

    private View createDockAction(String iconName, String title, Runnable action) {
        LinearLayout item = ui.column();
        item.setGravity(Gravity.CENTER);
        item.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4));
        item.addView(ui.icon(iconName, 0xFFF5F5F7), ui.size(17, 17));
        ui.gap(item, 2);
        TextView label = ui.text(title, 9, false);
        label.setTextColor(0xFFE5E5EA);
        label.setSingleLine(true);
        label.setGravity(Gravity.CENTER);
        label.setAutoSizeTextTypeUniformWithConfiguration(7, 9, 1, TypedValue.COMPLEX_UNIT_SP);
        item.addView(label, new LinearLayout.LayoutParams(ui.dp(42), ui.dp(12)));
        item.setContentDescription(title);
        attachDockDragAndTap(item, action);
        return item;
    }

    private void toggleDockCollapsed() {
        dockCollapsed = !dockCollapsed;
        styleEdgeDock();
        edgeDock.requestLayout();
        post(this::positionEdgeDock);
    }

    private void styleEdgeDock() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(dockCollapsed ? 0xB818181D : 0xE018181D);
        float r = ui.dp(dockCollapsed ? 11 : 14);
        if (dockRight) {
            bg.setCornerRadii(new float[]{r, r, 0, 0, 0, 0, r, r});
        } else {
            bg.setCornerRadii(new float[]{0, 0, r, r, r, r, 0, 0});
        }
        bg.setStroke(Math.max(1, ui.dp(0.8f)), 0x2BFFFFFF);
        edgeDock.setBackground(bg);

        dockButtonsBox.setVisibility(dockCollapsed ? GONE : VISIBLE);
        LinearLayout.LayoutParams handleLp = (LinearLayout.LayoutParams) dockCollapseBtn.getLayoutParams();
        handleLp.width = ui.dp(dockCollapsed ? 22 : 48);
        handleLp.height = ui.dp(dockCollapsed ? 52 : 22);
        dockCollapseBtn.setLayoutParams(handleLp);

        String chevron = dockCollapsed
                ? (dockRight ? "chevron_left" : "chevron_right")
                : (dockRight ? "chevron_right" : "chevron_left");
        dockCollapseIcon.setName(chevron);
        dockCollapseBtn.setContentDescription(L10n.tr(dockCollapsed ? "展开快捷栏" : "收起快捷栏"));
    }

    @SuppressLint("ClickableViewAccessibility")
    private void attachDockDragAndTap(View view, Runnable tap) {
        view.setClickable(true);
        view.setFocusable(true);
        view.setOnClickListener(v -> tap.run());
        int slop = ui.dp(5);
        view.setOnTouchListener(new OnTouchListener() {
            int pointer = -1;
            float startRawX, startRawY, startDockX, startDockY;
            boolean moved;

            @Override public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        pointer = event.getPointerId(0);
                        startRawX = event.getRawX();
                        startRawY = event.getRawY();
                        startDockX = edgeDock.getTranslationX();
                        startDockY = edgeDock.getTranslationY();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (event.getPointerId(0) != pointer) break;
                        float dx = event.getRawX() - startRawX;
                        float dy = event.getRawY() - startRawY;
                        if (!moved && Math.hypot(dx, dy) <= slop) break;
                        moved = true;
                        int w = getWidth();
                        int h = getHeight();
                        if (w > 0 && h > 0) {
                            float nx = bound(startDockX + dx, 0, Math.max(0, w - edgeDock.getWidth()));
                            float ny = bound(startDockY + dy, ui.dp(6), Math.max(ui.dp(6), h - edgeDock.getHeight() - ui.dp(6)));
                            edgeDock.setTranslationX(nx);
                            edgeDock.setTranslationY(ny);
                            dockNormY = bound((ny + edgeDock.getHeight() / 2f) / h, 0.08f, 0.88f);
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_POINTER_UP:
                        if (event.getPointerId(event.getActionIndex()) == pointer) {
                            pointer = -1;
                            if (!moved) {
                                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                                v.performClick();
                            } else {
                                snapDockToNearestEdge();
                            }
                        }
                        break;
                    case MotionEvent.ACTION_CANCEL:
                        pointer = -1;
                        if (moved) snapDockToNearestEdge();
                        break;
                }
                return true;
            }
        });
    }

    private void snapDockToNearestEdge() {
        int w = getWidth();
        if (w <= 0) return;
        float center = edgeDock.getTranslationX() + edgeDock.getWidth() / 2f;
        boolean nextRight = center >= w / 2f;
        if (dockRight != nextRight) {
            dockRight = nextRight;
            styleEdgeDock();
        }
        dockPrefs.edit()
                .putBoolean("dockRight", dockRight)
                .putFloat("dockNormY", dockNormY)
                .apply();
        float targetX = dockRight ? Math.max(0, w - edgeDock.getWidth()) : 0;
        edgeDock.animate()
                .translationX(targetX)
                .setDuration(160)
                .setInterpolator(new PathInterpolator(0.22f, 1f, 0.36f, 1f))
                .start();
    }

    private void positionEdgeDock() {
        int w = getWidth();
        int h = getHeight();
        int dw = edgeDock.getMeasuredWidth();
        int dh = edgeDock.getMeasuredHeight();
        if (w <= 0 || h <= 0 || dw <= 0 || dh <= 0) return;
        float x = dockRight ? Math.max(0, w - dw) : 0;
        float maxBottom = h - dh - ui.dp(6);
        if (keyboard.isOpen() && keyboard.getTop() > 0) {
            maxBottom = Math.min(maxBottom, keyboard.getTop() - dh - ui.dp(6));
        }
        float y = bound(dockNormY * h - dh / 2f, ui.dp(6), Math.max(ui.dp(6), maxBottom));
        edgeDock.setTranslationX(x);
        edgeDock.setTranslationY(y);
    }

    void openDrawer() {
        if (pictureInPicture) return;
        cancelMouseInput();
        keymapUpdated.run();
        drawer.resetToMain();
        drawerOpen = true;
        drawerScrim.setVisibility(VISIBLE);
        drawer.setVisibility(VISIBLE);
        drawerScrim.bringToFront();
        drawer.bringToFront();

        int dw = drawerWidth(getWidth());
        ViewGroup.LayoutParams lp = drawer.getLayoutParams();
        lp.width = dw;
        drawer.setLayoutParams(lp);

        drawer.animate().cancel();
        drawerScrim.animate().cancel();
        drawer.setTranslationX(dw);
        drawerScrim.setAlpha(0f);
        drawerScrim.animate().alpha(1f).setDuration(200).start();
        drawer.animate()
                .translationX(0f)
                .setDuration(220)
                .setInterpolator(new PathInterpolator(0.22f, 1f, 0.36f, 1f))
                .start();
        keymapUpdated.run();
    }

    void closeDrawer() {
        if (!drawerOpen) return;
        drawerOpen = false;
        drawer.closeDialogs();
        int dw = Math.max(ui.dp(280), drawer.getWidth());
        drawerScrim.animate().cancel();
        drawer.animate().cancel();
        drawerScrim.animate().alpha(0f).setDuration(160)
                .withEndAction(() -> {
                    if (!drawerOpen) drawerScrim.setVisibility(GONE);
                }).start();
        drawer.animate().translationX(dw).setDuration(180)
                .setInterpolator(new PathInterpolator(0.22f, 1f, 0.36f, 1f))
                .withEndAction(() -> {
                    if (!drawerOpen) drawer.setVisibility(GONE);
                }).start();
        keymapUpdated.run();
    }

    void openKeymapEditor() {
        if (keymapEditor != null || pictureInPicture) return;
        closeDrawer();
        closeKeyboard();
        cancelMouseInput();
        keymapEditor = new FloatingKeymapEditor(getContext(), ui, keymapStore, hostPlatform,
                new FloatingKeymapEditor.Listener() {
                    @Override public void onSaved() {
                        closeKeymapEditor();
                        drawer.refreshState();
                        keymapUpdated.run();
                    }

                    @Override public void onCancelled() {
                        closeKeymapEditor();
                    }
                });
        addView(keymapEditor, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        keymapEditor.bringToFront();
        edgeDock.setVisibility(GONE);
        positionMouse();
        keymapUpdated.run();
    }

    private void closeKeymapEditor() {
        if (keymapEditor == null) return;
        removeView(keymapEditor);
        keymapEditor = null;
        edgeDock.setVisibility(pictureInPicture ? GONE : VISIBLE);
        positionMouse();
        keymapUpdated.run();
    }

    boolean isOverlayBlockingKeymap() {
        return pictureInPicture || viewOnly || keymapEditor != null || waiting.getVisibility() == VISIBLE;
    }

    boolean handleBack() {
        if (keymapEditor != null) {
            closeKeymapEditor();
            return true;
        }
        if (drawerOpen) {
            if (!drawer.handleBack()) closeDrawer();
            return true;
        }
        return closeKeyboard();
    }

    void releaseTransientInputs() {
        cancelMouseInput();
        keyboard.releaseLockedModifiers();
    }

    void setExpandedMouseVisibilityListener(java.util.function.Consumer<Boolean> listener) {
        expandedMouseVisibilityListener = listener;
        notifyExpandedMouseVisibility();
    }

    private void notifyExpandedMouseVisibility() {
        boolean expandedVisible = mouseBar != null && mouseBar.isExpanded()
                && !pictureInPicture && !viewOnly && keymapEditor == null
                && mouseVisible && waiting.getVisibility() == GONE;
        if (lastNotifiedExpandedMouseVisible != expandedVisible) {
            lastNotifiedExpandedMouseVisible = expandedVisible;
            if (expandedMouseVisibilityListener != null) {
                expandedMouseVisibilityListener.accept(expandedVisible);
            }
        }
    }

    void onCursorPositionChanged(float cursorPx, float cursorPy, boolean fromScreenTouch) {
        lastCursorX = cursorPx;
        lastCursorY = cursorPy;
        if (mouseBar != null && mouseBar.isExpanded() && mouseVisible) {
            positionMouse();
        }
    }

    void setRelative(boolean value) {
        relativeMode = value;
        mouseVisible = PointerBallistics.loadVirtualMouseVisible(settingsPrefs, value);
        drawer.setRelative(value);
        positionMouse();
    }

    void setVirtualMouseVisible(boolean value) {
        if (mouseVisible == value) return;
        cancelMouseInput();
        mouseVisible = value;
        PointerBallistics.saveVirtualMouseVisible(settingsPrefs, relativeMode, value);
        positionMouse();
        drawer.post(drawer::refreshState);
    }

    void setViewOnly(boolean value) {
        viewOnly = value;
        if (value) {
            releaseTransientInputs();
            closeKeyboard();
        }
        drawer.refreshState();
        positionMouse();
        keymapUpdated.run();
    }

    void refreshOrientation() {
        drawer.refreshState();
    }

    void setHostPlatform(String platform) {
        hostPlatform = platform == null ? "" : platform;
        keyboard.setPlatform(hostPlatform);
        drawer.setPlatform(hostPlatform);
    }

    void attachMouseBar(FrameLayout safeArea, int controlsIndex, VirtualMouseBar bar) {
        mouseBar = bar;
        boolean initialExpanded = settingsPrefs.getBoolean(PointerBallistics.KEY_VIRTUAL_MOUSE_EXPANDED, false);
        bar.setExpanded(initialExpanded);
        safeArea.addView(bar, controlsIndex, new FrameLayout.LayoutParams(
                ui.dp(bar.currentWidthDp()), ui.dp(bar.currentHeightDp()), Gravity.TOP | Gravity.LEFT));
        bar.setPositionHost(new VirtualMouseBar.PositionHost() {
            @Override public void moveCollapsedBy(float dx, float dy) {
                float curX = Float.isNaN(collapsedMouseX) ? bar.getTranslationX() : collapsedMouseX;
                float curY = Float.isNaN(collapsedMouseY) ? bar.getTranslationY() : collapsedMouseY;
                collapsedMouseX = curX + dx;
                collapsedMouseY = curY + dy;
                int w = getWidth();
                int h = getHeight();
                if (w > 0 && h > 0) {
                    float barW = ui.dp(bar.currentWidthDp());
                    float barH = ui.dp(bar.currentHeightDp());
                    collapsedNormX = bound((collapsedMouseX + barW * 0.5f) / w, 0.03f, 0.97f);
                    collapsedNormY = bound((collapsedMouseY + barH * 0.5f) / h, 0.05f, 0.95f);
                }
                positionMouse();
            }

            @Override public void onCollapsedDragEnd() {
                if (!Float.isNaN(collapsedNormX) && !Float.isNaN(collapsedNormY)) {
                    settingsPrefs.edit()
                            .putFloat(PointerBallistics.KEY_VIRTUAL_MOUSE_NORM_X, collapsedNormX)
                            .putFloat(PointerBallistics.KEY_VIRTUAL_MOUSE_NORM_Y, collapsedNormY)
                            .apply();
                }
            }

            @Override public void onStateChanged() {
                settingsPrefs.edit()
                        .putBoolean(PointerBallistics.KEY_VIRTUAL_MOUSE_EXPANDED, bar.isExpanded())
                        .apply();
                ViewGroup.LayoutParams lp = bar.getLayoutParams();
                int nextW = ui.dp(bar.currentWidthDp());
                int nextH = ui.dp(bar.currentHeightDp());
                if (lp.width != nextW || lp.height != nextH) {
                    lp.width = nextW;
                    lp.height = nextH;
                    bar.setLayoutParams(lp);
                }
                collapsedMouseX = Float.NaN;
                collapsedMouseY = Float.NaN;
                positionMouse();
            }
        });
        bar.setVisibility(GONE);
    }

    void cancelMouseInput() {
        if (mouseBar != null) mouseBar.cancelInput();
    }

    void setPictureInPicture(boolean value) {
        pictureInPicture = value;
        if (value) {
            releaseTransientInputs();
            closeKeymapEditor();
            closeDrawer();
            closeKeyboard();
        }
        setVisibility(value ? GONE : VISIBLE);
        positionMouse();
        keymapUpdated.run();
    }

    private void toggleMouse() {
        setVirtualMouseVisible(!mouseVisible);
    }

    private void positionMouse() {
        if (mouseBar == null) return;
        boolean visible = !pictureInPicture && !viewOnly && keymapEditor == null
                && mouseVisible && waiting.getVisibility() == GONE;
        notifyExpandedMouseVisibility();
        if (!visible) {
            if (mouseBar.getVisibility() == VISIBLE) cancelMouseInput();
            mouseBar.setVisibility(GONE);
            return;
        }
        int w = getWidth();
        int h = getHeight();
        float barW = ui.dp(mouseBar.currentWidthDp());
        float barH = ui.dp(mouseBar.currentHeightDp());
        if (w <= 0 || h <= 0) return;

        float margin = ui.dp(6);
        float gap = ui.dp(6);
        float maxBottomLimit = h - margin;
        if (keyboard.isOpen() && keyboard.getTop() > 0) {
            maxBottomLimit = Math.min(maxBottomLimit, keyboard.getTop() - gap);
        }

        if (mouseBar.isExpanded()) {
            // Coupled mode (UU Remote-aligned): rigidly anchor the sculpted mouse beside the cursor arrow,
            // allowing the mouse body to slide smoothly past screen edges without covering the cursor or taskbar.
            float cx = Float.isNaN(lastCursorX) ? w * 0.5f : lastCursorX;
            float cy = Float.isNaN(lastCursorY) ? h * 0.5f : lastCursorY;
            float coupledX = PointerBallistics.coupledMouseCoord(
                    cx, ui.dp(VirtualMouseBar.COUPLED_OFFSET_X_DP));
            float coupledY = PointerBallistics.coupledMouseCoord(
                    cy, ui.dp(VirtualMouseBar.COUPLED_OFFSET_Y_DP));
            mouseBar.setTranslationX(coupledX);
            mouseBar.setTranslationY(coupledY);
            mouseBar.setVisibility(VISIBLE);
        } else {
            // Collapsed mini mouse badge: park at user's chosen spot (default: right margin beside dock).
            float rawX;
            float rawY;
            if (!Float.isNaN(collapsedMouseX) && !Float.isNaN(collapsedMouseY)) {
                rawX = collapsedMouseX;
                rawY = collapsedMouseY;
            } else if (!Float.isNaN(collapsedNormX) && !Float.isNaN(collapsedNormY)) {
                rawX = collapsedNormX * w - barW * 0.5f;
                rawY = collapsedNormY * h - barH * 0.5f;
            } else {
                rawX = dockRight ? (w - barW - ui.dp(56)) : (w - barW - ui.dp(12));
                rawY = h * 0.52f - barH * 0.5f;
            }
            float maxY = Math.max(margin, maxBottomLimit - barH);
            float clampedX = bound(rawX, margin, Math.max(margin, w - barW - margin));
            float clampedY = bound(rawY, margin, maxY);
            collapsedMouseX = clampedX;
            collapsedMouseY = clampedY;
            mouseBar.setTranslationX(clampedX);
            mouseBar.setTranslationY(clampedY);
            mouseBar.setVisibility(VISIBLE);
        }
    }

    void setMuted(boolean value) {
        drawer.setMuted(value);
    }

    void videoSize(int width, int height) {
        waiting.setVisibility(GONE);
        positionMouse();
        keymapUpdated.run();
    }

    void resetVideoStatistics() {
        RemoteNetworkStatistics.Snapshot empty = new RemoteNetworkStatistics().snapshot(0);
        statistics(empty);
    }

    void statistics(RemoteNetworkStatistics.Snapshot value) {
        drawer.setNetwork(value);
    }

    void videoSettings(RemoteVideoSettings value) {
        drawer.setVideoSettings(value);
    }

    void displayResolutions(RemoteDisplayResolutions value, int display) {
        drawer.setDisplayResolutions(value, display);
    }

    void privacyScreen(RemotePrivacyScreen value) {
        drawer.setPrivacy(value);
    }

    void fileStatus(String status, boolean received) {
        drawer.setFileStatus(status, received);
    }

    boolean closeKeyboard() {
        return keyboard.close();
    }

    void resetKeyboardContext() {
        keyboard.resetContext();
    }

    void keyboardInsets(int height, boolean visible, int navigation) {
        keyboard.updateInsets(height, visible, navigation);
    }

    void toggleKeyboard() {
        if (viewOnly) return;
        closeDrawer();
        keyboard.bringToFront();
        keyboard.toggle();
    }

    private int drawerWidth(int viewportWidth) {
        if (viewportWidth <= 0) return ui.dp(320);
        return Math.max(ui.dp(260), Math.min(ui.dp(344), viewportWidth - ui.dp(36)));
    }

    void constrainPanels() {
        cancelMouseInput();
        if (drawerOpen) {
            ViewGroup.LayoutParams lp = drawer.getLayoutParams();
            lp.width = drawerWidth(getWidth());
            drawer.setLayoutParams(lp);
            drawer.setTranslationX(0f);
        }
        if (keyboard.isOpen()) requestLayout();
        positionEdgeDock();
        positionMouse();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, heightSpec);
        boolean resized = false;
        int targetDrawerW = drawerWidth(getMeasuredWidth());
        ViewGroup.LayoutParams dlp = drawer.getLayoutParams();
        if (dlp.width != targetDrawerW) {
            dlp.width = targetDrawerW;
            resized = true;
        }
        if (keyboard.isOpen()) {
            LayoutParams p = (LayoutParams) keyboard.getLayoutParams();
            int height = keyboard.preferredHeight();
            int inset = keyboard.bottomInset();
            if (p.height != height || p.bottomMargin != inset) {
                p.height = height;
                p.bottomMargin = inset;
                resized = true;
            }
        }
        if (resized) super.onMeasure(widthSpec, heightSpec);
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        keyboardInsetChanged.accept(keyboard.isOpen() ? getHeight() - keyboard.getTop() : 0);
        positionEdgeDock();
        positionMouse();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (android.os.Build.VERSION.SDK_INT < 30) {
            getViewTreeObserver().addOnGlobalLayoutListener(legacyKeyboardLayout);
        }
    }

    @Override protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnGlobalLayoutListener(legacyKeyboardLayout);
        releaseTransientInputs();
        drawer.closeDialogs();
        super.onDetachedFromWindow();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        post(this::constrainPanels);
    }

    private static float bound(float value, float min, float max) {
        return Math.max(min, Math.min(value, Math.max(min, max)));
    }
}
