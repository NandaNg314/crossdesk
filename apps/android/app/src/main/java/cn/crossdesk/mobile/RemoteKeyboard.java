package cn.crossdesk.mobile;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.CancellationSignal;
import android.util.SparseIntArray;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimationControlListener;
import android.view.WindowInsetsAnimationController;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Unified 3-tab bottom keyboard:
 * Tab 0: 输入法 (System IME + pinned horizontal quick strip)
 * Tab 1: 快捷键 (Responsive multi-column shortcut grid with +Add / Edit / Reorder / Reset)
 * Tab 2: 电脑键盘 (Sticky 组合键模式 + Ctrl/Shift/Alt/Win + 主键盘 | 功能符号 | 小键盘)
 */
@android.annotation.SuppressLint("ViewConstructor")
final class RemoteKeyboard extends LinearLayout {
    static final int TAB_IME = 0;
    static final int TAB_SHORTCUTS = 1;
    static final int TAB_PC_KEYBOARD = 2;

    static final int SUBPAGE_MAIN = 0;
    static final int SUBPAGE_FUNC_SYM = 1;
    static final int SUBPAGE_NUMPAD = 2;

    private final MobileUi ui;
    private final SessionControls.Actions actions;
    final RemoteKeyboardInput editor;
    private final Runnable changed;
    private final LinearLayout toolbar;
    private final TextView[] mainTabs = new TextView[3];
    private final LinearLayout shortcutActionsBox;
    private final TextView addShortcutBtn, editShortcutBtn, resetShortcutBtn, doneShortcutBtn;
    private final LinearLayout inlineSubPagesBox;
    private final TextView[] inlineSubPageBtns = new TextView[3];
    private final MobileUi.Icon hideIcon;

    private final HorizontalScrollView quickScroll;
    private final LinearLayout quickKeys;
    private final LinearLayout keys;
    private final RemoteShortcutPanel shortcutPanel;

    private String hostPlatform = "windows";
    private int activeTab = TAB_IME;
    private int pcSubPage = SUBPAGE_MAIN;
    private boolean stickyComboMode;
    private final Set<Integer> oneShotModifiers = new LinkedHashSet<>();
    private final Set<Integer> lockedModifiers = new LinkedHashSet<>();

    private final SparseIntArray heights = new SparseIntArray();
    private boolean opened, computer, imeVisible, measuring, switching, targetComputer;
    private int imeHeight, navigationBottom, layoutWidth, layoutHeight, revision, lastInsetsHeight = -1, lastInsetsKey;
    private CancellationSignal transition;

    RemoteKeyboard(Context context, MobileUi ui, SessionControls.Actions actions, Runnable changed) {
        super(context);
        this.ui = ui;
        this.actions = actions;
        this.changed = changed;
        setOrientation(VERTICAL);
        setVisibility(GONE);
        setClickable(true);
        setMotionEventSplittingEnabled(true);

        editor = new RemoteKeyboardInput(context, new RemoteKeyboardInput.Sender() {
            public boolean edit(int count, String text) { return actions.textEdit(count, text); }
            public void key(int code) { actions.key(code); }
        });
        shortcutPanel = new RemoteShortcutPanel(context, ui, this::sendShortcut, () -> {
            layoutWidth = layoutHeight = 0;
            render();
            changed.run();
        });

        // Header row (44 dp)
        FrameLayout header = new FrameLayout(context);
        addView(header, new LayoutParams(LayoutParams.MATCH_PARENT, ui.dp(44)));
        header.addView(editor, new FrameLayout.LayoutParams(1, 1, Gravity.BOTTOM));

        toolbar = ui.row();
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(ui.dp(6), 0, ui.dp(4), 0);
        header.addView(toolbar, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // 3-tab segmented switch
        LinearLayout tabStrip = ui.row();
        tabStrip.setPadding(ui.dp(2), ui.dp(2), ui.dp(2), ui.dp(2));
        String[] tabTitles = {L10n.tr("输入法"), L10n.tr("快捷键"), L10n.tr("电脑键盘")};
        for (int i = 0; i < 3; i++) {
            final int tabIdx = i;
            TextView tab = smallButton(tabTitles[i], () -> selectTab(tabIdx));
            tab.setPadding(ui.dp(9), 0, ui.dp(9), 0);
            mainTabs[i] = tab;
            tabStrip.addView(tab, new LayoutParams(LayoutParams.WRAP_CONTENT, ui.dp(30)));
        }
        toolbar.addView(tabStrip, new LayoutParams(LayoutParams.WRAP_CONTENT, ui.dp(34)));

        toolbar.addView(new View(context), new LayoutParams(0, 1, 1f));

        // Contextual actions for 快捷键 tab
        shortcutActionsBox = ui.row();
        addShortcutBtn = smallButton("+ " + L10n.tr("添加"), shortcutPanel::openAddDialog);
        editShortcutBtn = smallButton(L10n.tr("编辑"), () -> shortcutPanel.setEditing(true));
        resetShortcutBtn = smallButton(L10n.tr("还原"), shortcutPanel::promptResetDefaults);
        doneShortcutBtn = smallButton(L10n.tr("完成"), () -> shortcutPanel.setEditing(false));
        for (TextView btn : new TextView[]{addShortcutBtn, editShortcutBtn, resetShortcutBtn, doneShortcutBtn}) {
            LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, ui.dp(30));
            lp.leftMargin = ui.dp(4);
            shortcutActionsBox.addView(btn, lp);
        }
        toolbar.addView(shortcutActionsBox, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        // Inline sub-page selector for 电脑键盘 tab on wide viewports
        inlineSubPagesBox = ui.row();
        String[] subTitles = {L10n.tr("主键盘"), L10n.tr("功能符号"), L10n.tr("小键盘")};
        for (int i = 0; i < 3; i++) {
            final int subIdx = i;
            TextView subBtn = smallButton(subTitles[i], () -> selectSubPage(subIdx));
            subBtn.setPadding(ui.dp(8), 0, ui.dp(8), 0);
            inlineSubPageBtns[i] = subBtn;
            LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, ui.dp(28));
            if (i > 0) lp.leftMargin = ui.dp(3);
            inlineSubPagesBox.addView(subBtn, lp);
        }
        toolbar.addView(inlineSubPagesBox, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        // Collapse button
        FrameLayout hide = new FrameLayout(context);
        hide.setContentDescription(L10n.tr("收起键盘"));
        hide.setClickable(true);
        hide.setFocusable(true);
        hideIcon = ui.icon("keyboard_hide", foreground());
        hide.addView(hideIcon, new FrameLayout.LayoutParams(ui.dp(22), ui.dp(22), Gravity.CENTER));
        hide.setOnClickListener(v -> {
            feedback(v);
            close();
        });
        LayoutParams hideLp = new LayoutParams(ui.dp(38), ui.dp(38));
        hideLp.leftMargin = ui.dp(2);
        toolbar.addView(hide, hideLp);

        // Pinned quick strip for 输入法 tab (38 dp)
        quickScroll = new HorizontalScrollView(context);
        quickScroll.setHorizontalScrollBarEnabled(false);
        quickKeys = ui.row();
        quickKeys.setPadding(ui.dp(6), ui.dp(3), ui.dp(6), ui.dp(4));
        quickScroll.addView(quickKeys);
        addView(quickScroll, new LayoutParams(LayoutParams.MATCH_PARENT, ui.dp(38)));

        // Custom bottom keybed for 快捷键 / 电脑键盘 tabs
        keys = ui.column();
        keys.setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(4));
        keys.setMotionEventSplittingEnabled(true);
        addView(keys, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        render();
    }

    private boolean dark() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private int foreground() { return dark() ? Color.WHITE : 0xFF202124; }

    private TextView smallButton(String label, Runnable action) {
        TextView view = ui.action(label, foreground(), action);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setTextSize(12);
        view.setMinHeight(0);
        view.setSingleLine(true);
        view.setPadding(ui.dp(8), 0, ui.dp(8), 0);
        view.setOnClickListener(v -> {
            feedback(v);
            action.run();
        });
        return view;
    }

    private void feedback(View view) {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
    }

    boolean isOpen() { return opened; }

    void setPlatform(String value) {
        hostPlatform = RemoteShortcuts.platform(value);
        shortcutPanel.platform(hostPlatform);
        layoutWidth = layoutHeight = 0;
        if (opened) render();
    }

    int bottomInset() { return opened && !computer ? imeHeight : 0; }

    private int sizeKey() {
        Configuration c = getResources().getConfiguration();
        return c.screenWidthDp * 10000 + c.screenHeightDp;
    }

    private int keyAreaHeight() {
        int height = heights.get(sizeKey(), 0);
        if (height == 0) {
            Configuration c = getResources().getConfiguration();
            height = ui.dp(c.screenWidthDp > c.screenHeightDp ? 216 : 292);
        }
        int available = getParent() instanceof View ? ((View) getParent()).getMeasuredHeight() - ui.dp(44) : height;
        return available > 0 ? Math.min(height, available) : height;
    }

    int preferredHeight() {
        if (!opened) return ui.dp(44);
        return computer ? ui.dp(44) + keyAreaHeight() : ui.dp(82);
    }

    void toggle() {
        if (opened) close();
        else open();
    }

    void open() {
        opened = true;
        activeTab = TAB_IME;
        computer = false;
        shortcutPanel.setEditing(false);
        clearAllModifiers();
        setVisibility(VISIBLE);
        layoutWidth = layoutHeight = 0;
        render();
        changed.run();
        post(() -> {
            if (opened && !computer) changeIme(true, false, false);
        });
    }

    boolean close() {
        shortcutPanel.close();
        clearAllModifiers();
        if (!opened) return false;
        opened = false;
        measuring = switching = false;
        cancelTransition();
        editor.stop();
        getContext().getSystemService(InputMethodManager.class).hideSoftInputFromWindow(getWindowToken(), 0);
        setVisibility(GONE);
        changed.run();
        return true;
    }

    void releaseLockedModifiers() {
        clearAllModifiers();
        if (opened) {
            layoutWidth = layoutHeight = 0;
            render();
        }
    }

    private void clearAllModifiers() {
        oneShotModifiers.clear();
        if (!lockedModifiers.isEmpty()) {
            for (int mod : lockedModifiers) {
                actions.keyHold(mod, false);
            }
            lockedModifiers.clear();
        }
    }

    void resetContext() {
        editor.finishInputContext();
    }

    private void selectSubPage(int subPage) {
        if (pcSubPage == subPage) return;
        pcSubPage = subPage;
        layoutWidth = layoutHeight = 0;
        render();
    }

    private void selectTab(int nextTab) {
        if (!opened || switching || activeTab == nextTab) return;
        boolean nextIsComputer = nextTab != TAB_IME;
        if (!computer && nextIsComputer && !prepareShortcut()) return;

        clearAllModifiers();
        if (nextTab != TAB_SHORTCUTS) shortcutPanel.setEditing(false);
        activeTab = nextTab;
        layoutWidth = layoutHeight = 0;

        if (computer == nextIsComputer) {
            // Instant switch between 快捷键 and 电脑键盘 without touching IME
            render();
            changed.run();
            return;
        }

        targetComputer = nextIsComputer;
        switching = true;
        if (targetComputer) {
            editor.pause();
            computer = true;
            render();
            changed.run();
        } else {
            editor.start();
        }

        if (targetComputer && Build.VERSION.SDK_INT >= 30 && isHardwareAccelerated()) {
            int token = revision;
            getViewTreeObserver().registerFrameCommitCallback(() -> post(() -> {
                if (opened && switching && targetComputer && token == revision) changeIme(false, true, false);
            }));
            invalidate();
        } else {
            changeIme(!targetComputer, true, false);
        }
    }

    private boolean prepareShortcut() {
        if (computer) return true;
        if (editor.hasPendingComposition()) {
            Toast.makeText(getContext(), L10n.tr("请先选词或取消组词"), Toast.LENGTH_SHORT).show();
            return false;
        }
        return editor.prepareShortcut();
    }

    private void sendShortcut(RemoteShortcuts.Item item) {
        if (!opened || switching || !prepareShortcut()) return;
        clearAllModifiers();
        if (item.service) {
            actions.secureAttention();
        } else if (item.lockScreen) {
            actions.lockWorkstation();
        } else {
            actions.chord(item.resolvedKey(hostPlatform), item.resolvedModifiers(hostPlatform));
        }
    }

    private void finishSwitch() {
        if (!switching) return;
        switching = false;
        if (computer != targetComputer) {
            computer = targetComputer;
            layoutWidth = layoutHeight = 0;
            render();
        }
        changed.run();
    }

    void updateInsets(int height, boolean visible, int navigation) {
        int key = sizeKey();
        if (lastInsetsHeight == height && lastInsetsKey == key && imeVisible == visible && navigationBottom == navigation) {
            return;
        }
        lastInsetsHeight = height;
        lastInsetsKey = key;
        navigationBottom = navigation;
        imeHeight = Build.VERSION.SDK_INT >= 30 ? Math.max(0, height) : 0;
        boolean wasVisible = imeVisible;
        imeVisible = visible;
        if (visible && height > ui.dp(80)) {
            heights.put(sizeKey(), height);
            if (measuring) {
                measuring = false;
                post(() -> {
                    if (opened && computer) changeIme(false, true, false);
                });
            }
        }
        if (switching && visible != targetComputer && (!visible || height > ui.dp(80))) finishSwitch();
        if (opened && !computer && !switching && wasVisible && !visible && transition == null) {
            close();
            return;
        }
        if (opened) {
            renderKeysIfNeeded(getMeasuredWidth());
            changed.run();
        }
    }

    private void cancelTransition() {
        revision++;
        if (transition != null) {
            CancellationSignal old = transition;
            transition = null;
            old.cancel();
        }
    }

    private void changeIme(boolean show, boolean immediate, boolean measureOnly) {
        cancelTransition();
        if (!opened) return;
        measuring = measureOnly;
        if (Build.VERSION.SDK_INT >= 30 && immediate && getWindowInsetsController() != null) {
            if (show) editor.start();
            else if (measureOnly) editor.requestFocus();
            int token = revision;
            transition = new CancellationSignal();
            getWindowInsetsController().controlWindowInsetsAnimation(
                    WindowInsets.Type.ime(), 0, null, transition, new WindowInsetsAnimationControlListener() {
                        public void onReady(WindowInsetsAnimationController controller, int types) {
                            if (token != revision || !opened) {
                                controller.finish(false);
                                return;
                            }
                            int height = controller.getShownStateInsets().bottom - navigationBottom;
                            if (height > ui.dp(80)) {
                                heights.put(sizeKey(), height);
                                measuring = false;
                                if (!measureOnly) {
                                    imeHeight = show ? height : 0;
                                    finishSwitch();
                                }
                                controller.finish(show && !measureOnly);
                            } else if (measureOnly) {
                                controller.finish(true);
                            } else {
                                controller.finish(show);
                            }
                            renderKeysIfNeeded(getMeasuredWidth());
                            changed.run();
                        }

                        public void onFinished(WindowInsetsAnimationController controller) {
                            if (token == revision) transition = null;
                        }

                        public void onCancelled(WindowInsetsAnimationController controller) {
                            if (token != revision) return;
                            transition = null;
                            if (opened) normalIme(show || measureOnly);
                        }
                    });
        } else {
            normalIme(show || measureOnly);
        }
    }

    private void normalIme(boolean show) {
        InputMethodManager manager = getContext().getSystemService(InputMethodManager.class);
        if (show) {
            if (computer) editor.requestFocus();
            else editor.start();
            manager.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT);
        } else {
            manager.hideSoftInputFromWindow(editor.getWindowToken(), 0);
        }
    }

    private boolean wideHeader() {
        int w = getMeasuredWidth();
        if (w <= 0) w = ui.dp(getResources().getConfiguration().screenWidthDp);
        return w >= ui.dp(540);
    }

    private void render() {
        boolean isDark = dark();
        setBackgroundColor(isDark ? 0xFF1E2025 : 0xFFE8EAED);
        hideIcon.setColor(foreground());

        View tabStrip = (View) mainTabs[0].getParent();
        tabStrip.setBackground(ui.background(isDark ? 0xFF2B2E36 : 0xFFD9DCE2, 9));
        for (int i = 0; i < 3; i++) {
            boolean active = activeTab == i;
            mainTabs[i].setSelected(active);
            mainTabs[i].setTypeface(Typeface.create("sans-serif-medium", active ? Typeface.BOLD : Typeface.NORMAL));
            mainTabs[i].setTextColor(active ? Color.WHITE : foreground());
            mainTabs[i].setBackground(ui.background(active ? MobileUi.BLUE : Color.TRANSPARENT, 7));
        }

        // Shortcut tab header actions
        boolean onShortcuts = computer && activeTab == TAB_SHORTCUTS;
        shortcutActionsBox.setVisibility(onShortcuts ? VISIBLE : GONE);
        if (onShortcuts) {
            boolean ed = shortcutPanel.isEditing();
            addShortcutBtn.setVisibility(ed ? GONE : VISIBLE);
            editShortcutBtn.setVisibility(ed ? GONE : VISIBLE);
            resetShortcutBtn.setVisibility(ed ? VISIBLE : GONE);
            doneShortcutBtn.setVisibility(ed ? VISIBLE : GONE);

            int chipBg = isDark ? 0xFF2B2E36 : 0xFFD9DCE2;
            addShortcutBtn.setBackground(ui.background(chipBg, 7));
            addShortcutBtn.setTextColor(foreground());
            editShortcutBtn.setBackground(ui.background(chipBg, 7));
            editShortcutBtn.setTextColor(foreground());
            resetShortcutBtn.setBackground(ui.background(chipBg, 7));
            resetShortcutBtn.setTextColor(foreground());
            doneShortcutBtn.setBackground(ui.background(MobileUi.BLUE, 7));
            doneShortcutBtn.setTextColor(Color.WHITE);
        }

        // PC Keyboard inline sub-pages on wide screens
        boolean onPc = computer && activeTab == TAB_PC_KEYBOARD;
        boolean showInlineSub = onPc && wideHeader();
        inlineSubPagesBox.setVisibility(showInlineSub ? VISIBLE : GONE);
        if (showInlineSub) {
            styleSubPageButtons(inlineSubPageBtns, isDark);
        }

        // IME quick strip
        quickScroll.setVisibility(!computer ? VISIBLE : GONE);
        if (!computer) {
            quickKeys.removeAllViews();
            int pillBg = isDark ? 0xFF2C3038 : Color.WHITE;
            for (RemoteShortcuts.Item item : shortcutPanel.store.pinnedQuickBar(hostPlatform)) {
                TextView pill = smallButton(item.title(), () -> sendShortcut(item));
                pill.setBackground(ui.background(pillBg, 7));
                pill.setPadding(ui.dp(10), 0, ui.dp(10), 0);
                LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, ui.dp(30));
                lp.rightMargin = ui.dp(5);
                quickKeys.addView(pill, lp);
            }
        }

        keys.setVisibility(computer ? VISIBLE : GONE);
        layoutWidth = layoutHeight = 0;
        renderKeysIfNeeded(getMeasuredWidth());
    }

    private void styleSubPageButtons(TextView[] btns, boolean isDark) {
        for (int i = 0; i < btns.length; i++) {
            boolean active = pcSubPage == i;
            btns[i].setTextColor(active ? Color.WHITE : foreground());
            btns[i].setBackground(ui.background(active ? MobileUi.BLUE : (isDark ? 0xFF2B2E36 : 0xFFD9DCE2), 7));
        }
    }

    private void renderKeysIfNeeded(int width) {
        if (!computer) return;
        int height = keyAreaHeight();
        if (width <= 0) width = ui.dp(getResources().getConfiguration().screenWidthDp);
        if (layoutWidth == width && layoutHeight == height) return;
        layoutWidth = width;
        layoutHeight = height;
        keys.removeAllViews();

        // Sync inline vs narrow sub-page visibility when width becomes known
        boolean showInline = activeTab == TAB_PC_KEYBOARD && width >= ui.dp(540);
        if ((inlineSubPagesBox.getVisibility() == VISIBLE) != showInline) {
            inlineSubPagesBox.setVisibility(showInline ? VISIBLE : GONE);
            if (showInline) styleSubPageButtons(inlineSubPageBtns, dark());
        }

        if (activeTab == TAB_SHORTCUTS) {
            keys.addView(shortcutPanel.create(width), new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            return;
        }

        boolean isDark = dark();
        // On narrow portrait (< 540dp), place sub-page selector cleanly at top of keybed
        if (width < ui.dp(540)) {
            LinearLayout subBar = ui.row();
            subBar.setPadding(ui.dp(3), 0, ui.dp(3), ui.dp(3));
            String[] subTitles = {L10n.tr("主键盘"), L10n.tr("功能符号"), L10n.tr("小键盘")};
            TextView[] localBtns = new TextView[3];
            for (int i = 0; i < 3; i++) {
                final int subIdx = i;
                TextView btn = smallButton(subTitles[i], () -> selectSubPage(subIdx));
                localBtns[i] = btn;
                LayoutParams lp = new LayoutParams(0, ui.dp(28), 1f);
                if (i > 0) lp.leftMargin = ui.dp(4);
                subBar.addView(btn, lp);
            }
            styleSubPageButtons(localBtns, isDark);
            keys.addView(subBar, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        }

        // Persistent Modifier Row: [组合键模式] | [Ctrl] | [Shift] | [Alt] | [Win]
        keys.addView(buildPersistentModifierRow(isDark),
                new LayoutParams(LayoutParams.MATCH_PARENT, ui.dp(34)));

        LinearLayout bed = ui.column();
        bed.setMotionEventSplittingEnabled(true);
        keys.addView(bed, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        if (pcSubPage == SUBPAGE_MAIN) {
            buildMainQwertyPage(bed);
        } else if (pcSubPage == SUBPAGE_FUNC_SYM) {
            buildFunctionSymbolPage(bed, width >= ui.dp(520));
        } else {
            buildNumpadPage(bed);
        }
    }

    private LinearLayout buildPersistentModifierRow(boolean isDark) {
        LinearLayout row = ui.row();
        row.setPadding(ui.dp(2), ui.dp(1), ui.dp(2), ui.dp(2));

        TextView stickyBtn = smallButton(L10n.tr("组合键模式"), () -> {
            stickyComboMode = !stickyComboMode;
            if (!stickyComboMode) {
                for (int mod : lockedModifiers) actions.keyHold(mod, false);
                lockedModifiers.clear();
            } else if (!oneShotModifiers.isEmpty()) {
                for (int mod : oneShotModifiers) {
                    if (lockedModifiers.add(mod)) actions.keyHold(mod, true);
                }
                oneShotModifiers.clear();
            }
            layoutWidth = layoutHeight = 0;
            render();
        });
        stickyBtn.setTextSize(11);
        stickyBtn.setPadding(ui.dp(8), 0, ui.dp(8), 0);
        GradientDrawable stickyBg = ui.background(
                stickyComboMode ? MobileUi.BLUE : (isDark ? 0xFF2B2E36 : 0xFFD2D6DE), 7);
        stickyBtn.setBackground(stickyBg);
        stickyBtn.setTextColor(stickyComboMode ? Color.WHITE : foreground());
        LayoutParams stickyLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT);
        stickyLp.setMargins(ui.dp(2), ui.dp(1), ui.dp(2), ui.dp(1));
        row.addView(stickyBtn, stickyLp);

        int[] modCodes = {
                KeyCatalog.VK_LCONTROL,
                KeyCatalog.VK_LSHIFT,
                KeyCatalog.VK_LMENU,
                KeyCatalog.VK_LWIN
        };
        for (int modCode : modCodes) {
            boolean active = isModifierActive(modCode);
            TextView modBtn = smallButton(KeyCatalog.modifierName(modCode, hostPlatform), () -> toggleModifier(modCode));
            modBtn.setTextSize(12);
            modBtn.setBackground(ui.background(active ? MobileUi.BLUE : (isDark ? 0xFF30343D : 0xFFD5D9E0), 7));
            modBtn.setTextColor(active ? Color.WHITE : foreground());
            LayoutParams lp = new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f);
            lp.setMargins(ui.dp(2), ui.dp(1), ui.dp(2), ui.dp(1));
            row.addView(modBtn, lp);
        }
        return row;
    }

    private boolean isModifierActive(int modCode) {
        return stickyComboMode ? lockedModifiers.contains(modCode) : oneShotModifiers.contains(modCode);
    }

    private void toggleModifier(int modCode) {
        if (stickyComboMode) {
            if (lockedModifiers.remove(modCode)) {
                actions.keyHold(modCode, false);
            } else {
                lockedModifiers.add(modCode);
                actions.keyHold(modCode, true);
            }
        } else {
            if (!oneShotModifiers.add(modCode)) {
                oneShotModifiers.remove(modCode);
            }
        }
        layoutWidth = layoutHeight = 0;
        render();
    }

    private void fireKey(int vkCode) {
        if (stickyComboMode) {
            // Locked modifiers are already held down on the remote host; tap the key while keeping modifiers locked.
            actions.key(vkCode);
        } else if (!oneShotModifiers.isEmpty()) {
            int[] mods = oneShotModifiers.stream().mapToInt(Integer::intValue).toArray();
            oneShotModifiers.clear();
            actions.chord(vkCode, mods);
            layoutWidth = layoutHeight = 0;
            render();
        } else {
            actions.key(vkCode);
        }
    }

    private static final class KeySpec {
        final String label;
        final String shiftedLabel;
        final int vk;
        final float weight;
        final boolean special;

        KeySpec(String label, String shiftedLabel, int vk, float weight, boolean special) {
            this.label = label;
            this.shiftedLabel = shiftedLabel;
            this.vk = vk;
            this.weight = weight;
            this.special = special;
        }

        static KeySpec ch(String plain, String shifted, int vk) {
            return new KeySpec(plain, shifted, vk, 1f, false);
        }

        static KeySpec fn(String label, int vk, float weight) {
            return new KeySpec(label, label, vk, weight, true);
        }
    }

    private void buildMainQwertyPage(LinearLayout bed) {
        String digits = "1234567890";
        String digitsShift = "!@#$%^&*()";
        KeySpec[] r1 = new KeySpec[11];
        for (int i = 0; i < 10; i++) {
            r1[i] = KeySpec.ch(digits.substring(i, i + 1), digitsShift.substring(i, i + 1), '0' + (i == 9 ? 0 : i + 1));
        }
        r1[10] = KeySpec.fn("⌫", KeyCatalog.VK_BACK, 1.3f);

        KeySpec[] r2 = rowLetters("qwertyuiop");

        String rowA = "asdfghjkl";
        KeySpec[] r3 = new KeySpec[10];
        for (int i = 0; i < 9; i++) {
            String s = rowA.substring(i, i + 1);
            r3[i] = KeySpec.ch(s, s.toUpperCase(java.util.Locale.ROOT), Character.toUpperCase(rowA.charAt(i)));
        }
        r3[9] = KeySpec.fn("Enter", KeyCatalog.VK_RETURN, 1.5f);

        KeySpec[] r4 = new KeySpec[]{
                KeySpec.ch("z", "Z", 'Z'),
                KeySpec.ch("x", "X", 'X'),
                KeySpec.ch("c", "C", 'C'),
                KeySpec.ch("v", "V", 'V'),
                KeySpec.ch("b", "B", 'B'),
                KeySpec.ch("n", "N", 'N'),
                KeySpec.ch("m", "M", 'M'),
                KeySpec.ch(",", "<", KeyCatalog.VK_OEM_COMMA),
                KeySpec.ch(".", ">", KeyCatalog.VK_OEM_PERIOD),
                KeySpec.ch("/", "?", KeyCatalog.VK_OEM_2)
        };

        KeySpec[] r5 = new KeySpec[]{
                KeySpec.fn("Esc", KeyCatalog.VK_ESCAPE, 1.1f),
                KeySpec.fn("Tab", KeyCatalog.VK_TAB, 1.1f),
                KeySpec.fn(L10n.tr("空格"), KeyCatalog.VK_SPACE, 3.4f),
                KeySpec.fn("←", KeyCatalog.VK_LEFT, 1f),
                KeySpec.fn("↑", KeyCatalog.VK_UP, 1f),
                KeySpec.fn("↓", KeyCatalog.VK_DOWN, 1f),
                KeySpec.fn("→", KeyCatalog.VK_RIGHT, 1f)
        };

        for (KeySpec[] r : new KeySpec[][]{r1, r2, r3, r4, r5}) {
            bed.addView(buildSpecRow(r), new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
        }
    }

    private KeySpec[] rowLetters(String chars) {
        KeySpec[] out = new KeySpec[chars.length()];
        for (int i = 0; i < chars.length(); i++) {
            String s = chars.substring(i, i + 1);
            out[i] = KeySpec.ch(s, s.toUpperCase(java.util.Locale.ROOT), Character.toUpperCase(chars.charAt(i)));
        }
        return out;
    }

    private void buildFunctionSymbolPage(LinearLayout bed, boolean wideFKeys) {
        if (wideFKeys) {
            KeySpec[] fRow = new KeySpec[12];
            for (int i = 0; i < 12; i++) fRow[i] = KeySpec.fn("F" + (i + 1), KeyCatalog.VK_F1 + i, 1f);
            bed.addView(buildSpecRow(fRow), new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
        } else {
            KeySpec[] f1 = new KeySpec[6];
            KeySpec[] f2 = new KeySpec[6];
            for (int i = 0; i < 6; i++) {
                f1[i] = KeySpec.fn("F" + (i + 1), KeyCatalog.VK_F1 + i, 1f);
                f2[i] = KeySpec.fn("F" + (i + 7), KeyCatalog.VK_F1 + i + 6, 1f);
            }
            bed.addView(buildSpecRow(f1), new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
            bed.addView(buildSpecRow(f2), new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
        }

        // Full symbol row: ` - = [ ] \ ; ' , . /
        KeySpec[] symRow = new KeySpec[]{
                KeySpec.ch("`", "~", KeyCatalog.VK_OEM_3),
                KeySpec.ch("-", "_", KeyCatalog.VK_OEM_MINUS),
                KeySpec.ch("=", "+", KeyCatalog.VK_OEM_PLUS),
                KeySpec.ch("[", "{", KeyCatalog.VK_OEM_4),
                KeySpec.ch("]", "}", KeyCatalog.VK_OEM_6),
                KeySpec.ch("\\", "|", KeyCatalog.VK_OEM_5),
                KeySpec.ch(";", ":", KeyCatalog.VK_OEM_1),
                KeySpec.ch("'", "\"", KeyCatalog.VK_OEM_7),
                KeySpec.ch(",", "<", KeyCatalog.VK_OEM_COMMA),
                KeySpec.ch(".", ">", KeyCatalog.VK_OEM_PERIOD),
                KeySpec.ch("/", "?", KeyCatalog.VK_OEM_2)
        };
        bed.addView(buildSpecRow(symRow), new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        KeySpec[] navRow1 = new KeySpec[]{
                KeySpec.fn("Esc", KeyCatalog.VK_ESCAPE, 1f),
                KeySpec.fn("Tab", KeyCatalog.VK_TAB, 1f),
                KeySpec.fn("PrtScr", KeyCatalog.VK_SNAPSHOT, 1.15f),
                KeySpec.fn("ScrLk", KeyCatalog.VK_SCROLL, 1.1f),
                KeySpec.fn("Pause", KeyCatalog.VK_PAUSE, 1.1f),
                KeySpec.fn("Ins", KeyCatalog.VK_INSERT, 1f),
                KeySpec.fn("Home", KeyCatalog.VK_HOME, 1.05f),
                KeySpec.fn("PgUp", KeyCatalog.VK_PRIOR, 1.1f)
        };
        bed.addView(buildSpecRow(navRow1), new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        KeySpec[] navRow2 = new KeySpec[]{
                KeySpec.fn("Caps", KeyCatalog.VK_CAPITAL, 1.1f),
                KeySpec.fn("Del", KeyCatalog.VK_DELETE, 1f),
                KeySpec.fn("End", KeyCatalog.VK_END, 1f),
                KeySpec.fn("PgDn", KeyCatalog.VK_NEXT, 1.1f),
                KeySpec.fn("←", KeyCatalog.VK_LEFT, 1f),
                KeySpec.fn("↑", KeyCatalog.VK_UP, 1f),
                KeySpec.fn("↓", KeyCatalog.VK_DOWN, 1f),
                KeySpec.fn("→", KeyCatalog.VK_RIGHT, 1f),
                KeySpec.fn("⌫", KeyCatalog.VK_BACK, 1.05f),
                KeySpec.fn("Enter", KeyCatalog.VK_RETURN, 1.2f)
        };
        bed.addView(buildSpecRow(navRow2), new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private void buildNumpadPage(LinearLayout bed) {
        KeySpec[][] rows = new KeySpec[][]{
                {
                        KeySpec.fn("NumLk", KeyCatalog.VK_NUMLOCK, 1f),
                        KeySpec.fn("/", KeyCatalog.VK_DIVIDE, 1f),
                        KeySpec.fn("*", KeyCatalog.VK_MULTIPLY, 1f),
                        KeySpec.fn("-", KeyCatalog.VK_SUBTRACT, 1f)
                },
                {
                        KeySpec.ch("7", "7", KeyCatalog.VK_NUMPAD0 + 7),
                        KeySpec.ch("8", "8", KeyCatalog.VK_NUMPAD0 + 8),
                        KeySpec.ch("9", "9", KeyCatalog.VK_NUMPAD0 + 9),
                        KeySpec.fn("+", KeyCatalog.VK_ADD, 1f)
                },
                {
                        KeySpec.ch("4", "4", KeyCatalog.VK_NUMPAD0 + 4),
                        KeySpec.ch("5", "5", KeyCatalog.VK_NUMPAD0 + 5),
                        KeySpec.ch("6", "6", KeyCatalog.VK_NUMPAD0 + 6),
                        KeySpec.fn("⌫", KeyCatalog.VK_BACK, 1f)
                },
                {
                        KeySpec.ch("1", "1", KeyCatalog.VK_NUMPAD0 + 1),
                        KeySpec.ch("2", "2", KeyCatalog.VK_NUMPAD0 + 2),
                        KeySpec.ch("3", "3", KeyCatalog.VK_NUMPAD0 + 3),
                        KeySpec.fn("Enter", KeyCatalog.VK_RETURN, 1f)
                },
                {
                        KeySpec.ch("0", "0", KeyCatalog.VK_NUMPAD0),
                        KeySpec.ch("00", "00", -1), // special double-zero convenience or wide 0
                        KeySpec.fn(".", KeyCatalog.VK_DECIMAL, 1f),
                        KeySpec.fn("Tab", KeyCatalog.VK_TAB, 1f)
                }
        };
        // Make Numpad 0 double-wide cleanly
        rows[4] = new KeySpec[]{
                new KeySpec("0", "0", KeyCatalog.VK_NUMPAD0, 2f, false),
                KeySpec.fn(".", KeyCatalog.VK_DECIMAL, 1f),
                KeySpec.fn(L10n.tr("空格"), KeyCatalog.VK_SPACE, 1f)
        };
        for (KeySpec[] r : rows) {
            bed.addView(buildSpecRow(r), new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
        }
    }

    private LinearLayout buildSpecRow(KeySpec[] specs) {
        LinearLayout line = ui.row();
        line.setGravity(Gravity.CENTER);
        boolean isDark = dark();
        boolean shiftActive = isModifierActive(KeyCatalog.VK_LSHIFT);

        for (KeySpec spec : specs) {
            String shown = shiftActive ? spec.shiftedLabel : spec.label;
            TextView key = smallButton(shown, () -> fireKey(spec.vk));
            key.setPadding(ui.dp(2), 0, ui.dp(2), 0);
            key.setSingleLine(true);
            key.setGravity(Gravity.CENTER);
            if (shown.length() > 1) {
                key.setTextSize(11);
                key.setAutoSizeTextTypeUniformWithConfiguration(8, 12, 1, TypedValue.COMPLEX_UNIT_SP);
            } else {
                key.setTextSize(16);
            }
            if ("⌫".equals(shown)) key.setContentDescription(L10n.tr("退格"));

            int normalColor = spec.special
                    ? (isDark ? 0xFF2D313A : 0xFFD2D7DF)
                    : (isDark ? 0xFF3A3F48 : Color.WHITE);
            int pressedColor = isDark ? 0xFF5A6372 : 0xFFB8C4D6;
            StateListDrawable bg = new StateListDrawable();
            bg.addState(new int[]{android.R.attr.state_pressed}, ui.background(pressedColor, 7));
            bg.addState(new int[]{}, ui.background(normalColor, 7));
            key.setBackground(bg);

            LayoutParams lp = new LayoutParams(0, LayoutParams.MATCH_PARENT, spec.weight);
            lp.setMargins(ui.dp(2), ui.dp(2), ui.dp(2), ui.dp(2));
            line.addView(key, lp);
        }
        return line;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        renderKeysIfNeeded(MeasureSpec.getSize(widthSpec));
        super.onMeasure(widthSpec, heightSpec);
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (opened && computer && (width != oldWidth || height != oldHeight) && heights.get(sizeKey(), 0) == 0) {
            post(() -> {
                if (opened && computer && !switching && !measuring && transition == null && heights.get(sizeKey(), 0) == 0) {
                    changeIme(false, true, true);
                }
            });
        }
    }

    @Override public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus) releaseLockedModifiers();
    }

    @Override protected void onDetachedFromWindow() {
        close();
        super.onDetachedFromWindow();
    }
}
