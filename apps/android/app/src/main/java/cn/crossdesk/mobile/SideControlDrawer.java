package cn.crossdesk.mobile;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * Slide-out Side Control Drawer from the right edge over a dim scrim.
 * Houses quick session tiles, touch-mode switch, mouse tuning controls, floating keymap profile manager,
 * and complete CrossDesk display / video / file / privacy / network controls.
 */
@SuppressLint("ViewConstructor")
final class SideControlDrawer extends LinearLayout {
    interface Host {
        void closeDrawer();
        void openKeymapEditor();
        void onKeymapChanged();
        void toggleVirtualMouse();
        void setVirtualMouseVisible(boolean visible);
        boolean isVirtualMouseVisible();
    }

    private static final int SURFACE_BG = 0xF2151519;
    private static final int CARD_BG = 0xFF212127;
    private static final int CARD_BORDER = 0x1FFFFFFF;
    private static final int TEXT_PRIMARY = 0xFFF5F5F7;
    private static final int TEXT_SECONDARY = 0xFF98989F;

    private final MobileUi ui;
    private final FloatingKeymapStore keymapStore;
    private final SharedPreferences mousePrefs;
    private final SessionControls.Actions actions;
    private final Host host;

    private String page = "main";
    private String platform = "windows";
    private boolean muted;
    private boolean relative = true;
    private boolean secureAttentionVisible;
    private RemoteVideoSettings settings = new RemoteVideoSettings(1);
    private RemoteDisplayResolutions resolutions = new RemoteDisplayResolutions();
    private int resolutionDisplay;
    private AlertDialog resolutionDialog;
    private RemotePrivacyScreen privacy = new RemotePrivacyScreen();
    private RemoteNetworkStatistics.Snapshot network = new RemoteNetworkStatistics().snapshot(0);
    private String transferStatus = "";
    private boolean hasReceivedFile;

    private final LinearLayout headerRow;
    private final ScrollView scroll;
    private final LinearLayout body;

    // Live sub-page refs
    private TextView videoFeedback, resolutionButton, resolutionFeedback;
    private final List<VideoSegmentRow> videoRows = new ArrayList<>();
    private TextView privacyFeedbackLabel, privacyValueLabel;
    private View privacyRowView;
    private TextView transferStatusLabel, saveReceivedBtn;
    private TextView[][] trafficCells;
    private LinearLayout[] trafficRows;
    private TextView[] networkDetails;
    private boolean endpointsExpanded;
    private View encryptionLock, encryptionOpen;
    private static final int CONNECTION_MODE = 4, LOCAL_ENDPOINT = 5, REMOTE_ENDPOINT = 6, ENCRYPTION = 7;

    SideControlDrawer(Context context, MobileUi ui, FloatingKeymapStore keymapStore,
                      SharedPreferences mousePrefs, SessionControls.Actions actions, Host host) {
        super(context);
        this.ui = ui;
        this.keymapStore = keymapStore;
        this.mousePrefs = mousePrefs;
        this.actions = actions;
        this.host = host;

        setOrientation(VERTICAL);
        GradientDrawable shell = new GradientDrawable();
        shell.setColor(SURFACE_BG);
        float r = ui.dp(20);
        shell.setCornerRadii(new float[]{r, r, 0, 0, 0, 0, r, r});
        shell.setStroke(Math.max(1, ui.dp(0.8f)), 0x26FFFFFF);
        setBackground(shell);
        setClickable(true);
        setElevation(ui.dp(18));

        headerRow = ui.row();
        headerRow.setPadding(ui.dp(14), ui.dp(10), ui.dp(10), ui.dp(8));
        addView(headerRow, new LayoutParams(LayoutParams.MATCH_PARENT, ui.dp(48)));

        scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        body = ui.column();
        body.setPadding(ui.dp(14), ui.dp(4), ui.dp(14), ui.dp(18));
        scroll.addView(body, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        rebuild();
    }

    boolean handleBack() {
        if (!"main".equals(page)) {
            openSubPage("main");
            return true;
        }
        return false;
    }

    void resetToMain() {
        if (!"main".equals(page)) openSubPage("main");
        else rebuild();
    }

    void closeDialogs() {
        if (resolutionDialog != null) {
            resolutionDialog.dismiss();
            resolutionDialog = null;
        }
    }

    void setPlatform(String value) {
        platform = value == null ? "" : value;
        boolean nextSas = "windows".equals(platform);
        if (secureAttentionVisible != nextSas) {
            secureAttentionVisible = nextSas;
            if ("main".equals(page)) rebuild();
        }
    }

    void setMuted(boolean value) {
        muted = value;
        if ("main".equals(page)) rebuild();
    }

    void setRelative(boolean value) {
        relative = value;
        if ("main".equals(page)) rebuild();
    }

    void setVideoSettings(RemoteVideoSettings value) {
        settings = value;
        renderVideoSubPage();
    }

    void setDisplayResolutions(RemoteDisplayResolutions value, int display) {
        resolutions = value;
        resolutionDisplay = display;
        renderResolutions();
    }

    void setPrivacy(RemotePrivacyScreen value) {
        boolean wasAvail = privacy.desktopAvailable;
        privacy = value;
        if ("main".equals(page) && wasAvail != privacy.desktopAvailable) {
            rebuild();
        } else {
            renderPrivacy();
        }
    }

    void setFileStatus(String status, boolean received) {
        transferStatus = status == null ? "" : status;
        hasReceivedFile = received;
        renderFileStatus();
    }

    void setNetwork(RemoteNetworkStatistics.Snapshot value) {
        network = value;
        renderNetworkSubPage();
    }

    void refreshState() {
        if ("main".equals(page) || "other".equals(page)) rebuild();
    }

    private void openSubPage(String next) {
        closeDialogs();
        page = next;
        rebuild();
        scroll.scrollTo(0, 0);
    }

    private void clearRefs() {
        videoFeedback = resolutionButton = resolutionFeedback = null;
        videoRows.clear();
        privacyFeedbackLabel = privacyValueLabel = null;
        privacyRowView = null;
        transferStatusLabel = saveReceivedBtn = null;
        trafficCells = null;
        trafficRows = null;
        networkDetails = null;
        encryptionLock = encryptionOpen = null;
    }

    private void rebuild() {
        clearRefs();
        headerRow.removeAllViews();
        body.removeAllViews();

        if (!"main".equals(page)) {
            FrameLayout backBtn = iconCircle("back", L10n.tr("返回"), () -> openSubPage("main"));
            headerRow.addView(backBtn, ui.size(32, 32));
            View sp = new View(getContext());
            headerRow.addView(sp, ui.size(8, 1));
        } else {
            View dot = new View(getContext());
            dot.setBackground(ui.background(0xFF30D158, 3));
            LinearLayout.LayoutParams dotLp = ui.size(6, 6);
            dotLp.rightMargin = ui.dp(8);
            headerRow.addView(dot, dotLp);
        }

        String headerTitle = "main".equals(page) ? L10n.tr("远控操作")
                : "video".equals(page) ? L10n.tr("画面设置")
                : "other".equals(page) ? L10n.tr("其他设置") : L10n.tr("网络状态");
        TextView titleTv = darkText(headerTitle, 15, true, TEXT_PRIMARY);
        headerRow.addView(titleTv, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        FrameLayout closeBtn = iconCircle("close", L10n.tr("关闭"), host::closeDrawer);
        headerRow.addView(closeBtn, ui.size(32, 32));

        if ("main".equals(page)) {
            buildMainPage();
        } else if ("video".equals(page)) {
            buildVideoPage();
        } else if ("other".equals(page)) {
            buildOtherSettingsPage();
        } else {
            buildNetworkPage();
        }
    }

    private void buildMainPage() {
        // 1. Top 3x2 Quick Action Tiles
        LinearLayout r1 = ui.row();
        r1.addView(quickTile("exit", L10n.tr("退出远控"), false, true, () -> {
            host.closeDrawer();
            actions.disconnect();
        }), tileLp(0));
        r1.addView(quickTile("rotate", L10n.tr("旋转") + "·" + actions.orientationLabel(),
                actions.isOrientationLocked(), false, () -> {
                    actions.cycleOrientation();
                    rebuild();
                }), tileLp(6));
        r1.addView(quickTile(muted ? "audio_off" : "audio", muted ? L10n.tr("已静音") : L10n.tr("声音"),
                !muted, false, actions::mute), tileLp(6));
        body.addView(r1);
        ui.gap(body, 6);

        LinearLayout r2 = ui.row();
        r2.addView(quickTile("eye", L10n.tr("仅观看"), actions.isViewOnly(), false, () -> {
            actions.toggleViewOnly();
            rebuild();
        }), tileLp(0));
        r2.addView(quickTile("mouse", L10n.tr("虚拟鼠标"), host.isVirtualMouseVisible(), false, () -> {
            host.toggleVirtualMouse();
            rebuild();
        }), tileLp(6));
        r2.addView(quickTile("lock", L10n.tr("锁定屏幕"), false, false, () -> {
            host.closeDrawer();
            actions.lockWorkstation();
        }), tileLp(6));
        body.addView(r2);

        ui.gap(body, 12);

        // 2. Touch Mode Segmented Switch: 屏幕触控 vs 鼠标指针 + Gesture Hint & Mouse Controls
        sectionTitle(L10n.tr("触控模式"));
        LinearLayout touchCard = cardBox();
        LinearLayout segWrap = ui.column();
        segWrap.setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8));
        segWrap.addView(darkSegments(
                new String[]{L10n.tr("屏幕触控"), L10n.tr("鼠标指针")},
                relative ? 1 : 0,
                true,
                idx -> actions.setRelativeMouse(idx == 1)));
        TextView gestureHint = darkText(
                L10n.tr(relative
                        ? "单指滑动移指针 · 轻触左键 · 双击不松手拖拽 · 双指轻触右键 · 双指滑动滚轮"
                        : "点按直达点击 · 单指滑动拖拽 · 长按/双指轻触右键 · 双指捏合缩放"),
                11, false, TEXT_SECONDARY);
        gestureHint.setLineSpacing(ui.dp(2), 1f);
        gestureHint.setPadding(ui.dp(4), ui.dp(7), ui.dp(4), ui.dp(1));
        segWrap.addView(gestureHint);
        touchCard.addView(segWrap);

        appendMouseControlsToCard(touchCard, relative);
        body.addView(touchCard);

        ui.gap(body, 12);

        // 3. 按键映射 Card
        sectionTitle(L10n.tr("按键映射"));
        LinearLayout keymapCard = cardBox();
        LinearLayout enableRow = ui.row();
        enableRow.setPadding(ui.dp(14), ui.dp(10), ui.dp(12), ui.dp(10));
        enableRow.addView(darkText(L10n.tr("启用按键映射"), 14, false, TEXT_PRIMARY),
                new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        Switch enableSw = new Switch(getContext());
        enableSw.setChecked(keymapStore.isEnabled());
        enableSw.setContentDescription(L10n.tr("启用按键映射"));
        enableSw.setOnCheckedChangeListener((b, checked) -> {
            keymapStore.setEnabled(checked);
            host.onKeymapChanged();
        });
        enableRow.addView(enableSw);
        keymapCard.addView(enableRow);
        cardDivider(keymapCard);

        drawerRow(keymapCard, "keymap", L10n.tr("切换操控方案"),
                keymapStore.activeProfile().name, this::showProfileManagerDialog);
        cardDivider(keymapCard);
        drawerRow(keymapCard, "edit", L10n.tr("编辑当前方案"), "", () -> {
            host.closeDrawer();
            host.openKeymapEditor();
        });
        cardDivider(keymapCard);
        drawerRow(keymapCard, "settings", L10n.tr("其他设置"), "", () -> openSubPage("other"));
        body.addView(keymapCard);

        ui.gap(body, 12);

        // 4. Session & Display Settings Card
        sectionTitle(L10n.tr("会话与画面"));
        LinearLayout sessionCard = cardBox();
        drawerRow(sessionCard, "display", L10n.tr("切换显示器"), "", () -> {
            host.closeDrawer();
            actions.display();
        });
        cardDivider(sessionCard);
        drawerRow(sessionCard, "sliders", L10n.tr("画面设置"), "", () -> openSubPage("video"));
        cardDivider(sessionCard);
        drawerRow(sessionCard, "clipboard", L10n.tr("剪贴板"), "", () -> {
            host.closeDrawer();
            actions.clipboard();
        });
        cardDivider(sessionCard);
        drawerRow(sessionCard, "folder", L10n.tr("发送文件"), "", () -> {
            host.closeDrawer();
            actions.sendFile();
        });

        if (secureAttentionVisible) {
            cardDivider(sessionCard);
            drawerRow(sessionCard, "lock", "Ctrl+Alt+Del", "", () -> {
                host.closeDrawer();
                actions.secureAttention();
            });
        }

        if (privacy.desktopAvailable) {
            cardDivider(sessionCard);
            privacyRowView = drawerRowWithValueRef(sessionCard, "eye", L10n.tr("隐私屏"), () -> actions.togglePrivacy());
        }

        cardDivider(sessionCard);
        drawerRow(sessionCard, "chart", L10n.tr("网络状态"), "", () -> openSubPage("network"));
        body.addView(sessionCard);

        // Live privacy & file transfer feedback
        privacyFeedbackLabel = darkText("", 11, false, TEXT_SECONDARY);
        privacyFeedbackLabel.setPadding(ui.dp(4), ui.dp(6), ui.dp(4), 0);
        body.addView(privacyFeedbackLabel);
        renderPrivacy();

        transferStatusLabel = darkText("", 11, false, TEXT_SECONDARY);
        transferStatusLabel.setPadding(ui.dp(4), ui.dp(6), ui.dp(4), 0);
        body.addView(transferStatusLabel);

        saveReceivedBtn = ui.action(L10n.tr("保存收到的文件"), Color.WHITE, () -> {
            host.closeDrawer();
            actions.saveFile();
        });
        saveReceivedBtn.setTextSize(13);
        saveReceivedBtn.setMinHeight(ui.dp(38));
        saveReceivedBtn.setBackground(ui.background(MobileUi.BLUE, 10));
        LayoutParams saveLp = new LayoutParams(LayoutParams.MATCH_PARENT, ui.dp(38));
        saveLp.topMargin = ui.dp(6);
        body.addView(saveReceivedBtn, saveLp);
        renderFileStatus();
    }

    private void buildOtherSettingsPage() {
        sectionTitle(L10n.tr("触控模式"));
        LinearLayout modeCard = cardBox();
        LinearLayout segWrap = ui.column();
        segWrap.setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8));
        segWrap.addView(darkSegments(
                new String[]{L10n.tr("屏幕触控"), L10n.tr("鼠标指针")},
                relative ? 1 : 0,
                true,
                idx -> {
                    actions.setRelativeMouse(idx == 1);
                    rebuild();
                }));
        TextView hint = darkText(
                L10n.tr(relative
                        ? "单指滑动移指针 · 轻触左键 · 双击不松手拖拽 · 双指轻触右键 · 双指滑动滚轮"
                        : "点按直达点击 · 单指滑动拖拽 · 长按/双指轻触右键 · 双指捏合缩放"),
                11, false, TEXT_SECONDARY);
        hint.setLineSpacing(ui.dp(2), 1f);
        hint.setPadding(ui.dp(4), ui.dp(7), ui.dp(4), ui.dp(2));
        segWrap.addView(hint);
        modeCard.addView(segWrap);
        body.addView(modeCard);

        ui.gap(body, 12);
        sectionTitle(L10n.tr("鼠标控制"));
        LinearLayout mouseCard = cardBox();
        appendMouseControlsToCard(mouseCard, true);
        body.addView(mouseCard);
    }

    private void appendMouseControlsToCard(LinearLayout card, boolean includePointerSpeedAndAccel) {
        if (includePointerSpeedAndAccel) {
            if (card.getChildCount() > 0) cardDivider(card);
            LinearLayout speedBox = ui.column();
            speedBox.setPadding(ui.dp(14), ui.dp(10), ui.dp(12), ui.dp(8));

            LinearLayout speedHeader = ui.row();
            speedHeader.addView(darkText(L10n.tr("指针速度"), 13, false, TEXT_PRIMARY),
                    new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

            float initialSensitivity = PointerBallistics.loadSensitivity(mousePrefs);
            int defaultPct = PointerBallistics.sensitivityPercent(PointerBallistics.DEFAULT_SENSITIVITY);

            TextView speedPct = darkText(PointerBallistics.sensitivityPercent(initialSensitivity) + "%",
                    12, true, 0xFF70B2FF);
            speedPct.setFontFeatureSettings("tnum");
            speedHeader.addView(speedPct);

            TextView resetBtn = darkText(L10n.tr("重置"), 11, true, TEXT_SECONDARY);
            resetBtn.setPadding(ui.dp(8), ui.dp(3), ui.dp(8), ui.dp(3));
            resetBtn.setBackground(ui.background(0xFF2C2C34, 6));
            LayoutParams resetLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            resetLp.leftMargin = ui.dp(8);
            speedHeader.addView(resetBtn, resetLp);
            speedBox.addView(speedHeader);

            // Steps of 5% from 25% to 200% -> 0..35 (default 100% -> 15)
            SeekBar slider = new SeekBar(getContext());
            slider.setMax(PointerBallistics.SLIDER_MAX_PROGRESS);
            int initialProgress = Math.max(0, Math.min(PointerBallistics.SLIDER_MAX_PROGRESS,
                    Math.round((PointerBallistics.sensitivityPercent(initialSensitivity) - PointerBallistics.SLIDER_MIN_PCT)
                            / (float) PointerBallistics.SLIDER_STEP_PCT)));
            slider.setProgress(initialProgress);
            slider.setProgressTintList(ColorStateList.valueOf(MobileUi.BLUE));
            slider.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
            slider.setProgressBackgroundTintList(ColorStateList.valueOf(0x38FFFFFF));
            slider.setContentDescription(L10n.tr("指针速度"));

            Runnable syncResetState = () -> {
                int pct = PointerBallistics.SLIDER_MIN_PCT + slider.getProgress() * PointerBallistics.SLIDER_STEP_PCT;
                boolean changed = pct != defaultPct;
                resetBtn.setEnabled(changed);
                resetBtn.setAlpha(changed ? 1f : 0.45f);
                resetBtn.setTextColor(changed ? TEXT_PRIMARY : TEXT_SECONDARY);
            };
            syncResetState.run();

            slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    int pct = PointerBallistics.SLIDER_MIN_PCT + progress * PointerBallistics.SLIDER_STEP_PCT;
                    speedPct.setText(pct + "%");
                    syncResetState.run();
                    if (fromUser) {
                        PointerBallistics.saveSensitivity(mousePrefs, pct / 100f);
                        actions.onMousePreferencesChanged();
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });

            resetBtn.setClickable(true);
            resetBtn.setFocusable(true);
            resetBtn.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                slider.setProgress(PointerBallistics.SLIDER_DEFAULT_PROGRESS);
                PointerBallistics.saveSensitivity(mousePrefs, PointerBallistics.DEFAULT_SENSITIVITY);
                actions.onMousePreferencesChanged();
            });

            LayoutParams sliderLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            sliderLp.topMargin = ui.dp(4);
            speedBox.addView(slider, sliderLp);
            card.addView(speedBox);

            cardDivider(card);
            drawerSwitchRow(card, L10n.tr("指针动态加速"), PointerBallistics.loadAcceleration(mousePrefs), checked -> {
                PointerBallistics.saveAcceleration(mousePrefs, checked);
                actions.onMousePreferencesChanged();
            });
        }

        if (card.getChildCount() > 0) cardDivider(card);
        drawerSwitchRow(card, L10n.tr("悬浮虚拟鼠标"), host.isVirtualMouseVisible(),
                host::setVirtualMouseVisible);

        cardDivider(card);
        drawerSwitchRow(card, L10n.tr("双指滚轮反向（自然滚动）"), PointerBallistics.loadNaturalScroll(mousePrefs), checked -> {
            PointerBallistics.saveNaturalScroll(mousePrefs, checked);
            actions.onMousePreferencesChanged();
        });
    }

    private void drawerSwitchRow(LinearLayout parent, String title, boolean checked,
                                 java.util.function.Consumer<Boolean> changed) {
        LinearLayout row = ui.row();
        row.setPadding(ui.dp(14), ui.dp(9), ui.dp(12), ui.dp(9));
        row.addView(darkText(title, 13, false, TEXT_PRIMARY),
                new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        Switch sw = new Switch(getContext());
        sw.setChecked(checked);
        sw.setContentDescription(title);
        sw.setOnCheckedChangeListener((b, isChecked) -> {
            b.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            changed.accept(isChecked);
        });
        row.addView(sw);
        parent.addView(row);
    }

    private void buildVideoPage() {
        videoFeedback = darkText("", 12, false, TEXT_SECONDARY);
        videoFeedback.setPadding(0, 0, 0, ui.dp(8));
        body.addView(videoFeedback);

        LinearLayout card = cardBox();
        card.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));

        card.addView(darkText(L10n.tr("被控端分辨率"), 12, false, TEXT_SECONDARY));
        ui.gap(card, 6);
        resolutionButton = ui.action("", TEXT_PRIMARY, this::chooseResolution);
        resolutionButton.setTextSize(13);
        resolutionButton.setMinHeight(ui.dp(36));
        resolutionButton.setBackground(ui.background(0xFF2C2C34, 8));
        card.addView(resolutionButton, ui.size(-1, 36));
        resolutionFeedback = darkText("", 11, false, TEXT_SECONDARY);
        resolutionFeedback.setPadding(0, ui.dp(4), 0, 0);
        card.addView(resolutionFeedback);
        renderResolutions();

        ui.gap(card, 12);
        videoRows.add(new VideoSegmentRow(card, 0, L10n.tr("画面质量"),
                new String[]{L10n.tr("低"), L10n.tr("中"), L10n.tr("高")}, new int[]{0, 1, 2}));
        ui.gap(card, 12);
        videoRows.add(new VideoSegmentRow(card, 1, L10n.tr("画面采集帧率"),
                new String[]{"30 fps", "60 fps"}, new int[]{30, 60}));
        ui.gap(card, 12);
        videoRows.add(new VideoSegmentRow(card, 2, L10n.tr("画面偏好"),
                RemoteVideoSettings.preferences(), new int[]{0, 1, 2}));
        ui.gap(card, 12);
        card.addView(darkText(L10n.tr("全面屏比例"), 12, false, TEXT_SECONDARY));
        ui.gap(card, 6);
        int fillIdx = mousePrefs.getBoolean("videoFillScreen", false) ? 1 : 0;
        card.addView(darkSegments(new String[]{L10n.tr("完整适应"), L10n.tr("铺满全屏")}, fillIdx, true, index -> {
            boolean fill = index == 1;
            mousePrefs.edit().putBoolean("videoFillScreen", fill).apply();
            actions.setVideoFillScreen(fill);
        }));
        body.addView(card);
        renderVideoSubPage();
    }

    private void buildNetworkPage() {
        endpointsExpanded = false;
        LinearLayout card = cardBox();
        card.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));

        trafficCells = new TextView[4][3];
        trafficRows = new LinearLayout[4];
        String[] names = {L10n.tr("视频"), L10n.tr("音频"), L10n.tr("数据"), L10n.tr("合计")};
        LinearLayout heading = trafficRow(card, "", new TextView[3], false);
        ((TextView) heading.getChildAt(1)).setText(L10n.tr("接收"));
        ((TextView) heading.getChildAt(2)).setText(L10n.tr("发送"));
        ((TextView) heading.getChildAt(3)).setText(L10n.tr("丢包率"));
        for (int i = 0; i < heading.getChildCount(); i++) {
            ((TextView) heading.getChildAt(i)).setTextColor(TEXT_SECONDARY);
        }
        for (int i = 0; i < 4; i++) {
            trafficRows[i] = trafficRow(card, names[i], trafficCells[i], i == 3);
        }

        ui.gap(card, 10);
        cardDivider(card);
        ui.gap(card, 10);

        String[] titles = {
                L10n.tr("帧率"), L10n.tr("分辨率"), L10n.tr("画面延时"), L10n.tr("连接延时（RTT）"),
                L10n.tr("连接方式"), L10n.tr("本端地址"), L10n.tr("对端地址"),
                L10n.tr("媒体加密"), L10n.tr("被控端编码器"), L10n.tr("控制端解码器")
        };
        networkDetails = new TextView[titles.length];
        for (int i = 0; i < titles.length; i++) {
            LinearLayout row = ui.row();
            boolean longVal = i >= LOCAL_ENDPOINT && i != ENCRYPTION;
            TextView title = darkText(titles[i], 12, false, TEXT_SECONDARY);
            row.addView(title, longVal ? new LayoutParams(-2, -2) : new LayoutParams(0, -2, 1f));
            if (i == ENCRYPTION) {
                encryptionLock = ui.icon("lock", 0xFF30D158);
                encryptionOpen = ui.icon("unlock", TEXT_SECONDARY);
                for (View icon : new View[]{encryptionLock, encryptionOpen}) {
                    LayoutParams p = ui.size(14, 14);
                    p.rightMargin = ui.dp(4);
                    row.addView(icon, p);
                }
            }
            TextView value = darkText("—", 12, false, TEXT_PRIMARY);
            value.setFontFeatureSettings("tnum");
            if (longVal) {
                value.setGravity(Gravity.END);
                value.setSingleLine(true);
                value.setEllipsize(TextUtils.TruncateAt.MARQUEE);
                value.setMarqueeRepeatLimit(-1);
                value.setSelected(true);
                LayoutParams p = new LayoutParams(0, -2, 1f);
                p.leftMargin = ui.dp(8);
                row.addView(value, p);
            } else {
                row.addView(value);
            }
            if (i == CONNECTION_MODE) {
                row.setOnClickListener(v -> {
                    endpointsExpanded = !endpointsExpanded;
                    renderNetworkSubPage();
                });
            }
            networkDetails[i] = value;
            LayoutParams layout = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            if (i > 0) layout.topMargin = ui.dp(7);
            card.addView(row, layout);
        }
        body.addView(card);
        renderNetworkSubPage();
    }

    private LinearLayout trafficRow(LinearLayout parent, String title, TextView[] cells, boolean total) {
        LinearLayout row = ui.row();
        row.setPadding(ui.dp(4), ui.dp(5), ui.dp(4), ui.dp(5));
        if (total) row.setBackground(ui.background(0x222E7ADB, 6));
        parent.addView(row);
        TextView label = darkText(title, 11, total, TEXT_PRIMARY);
        row.addView(label, ui.size(30, -2));
        for (int i = 0; i < 3; i++) {
            TextView cell = darkText("—", 11, total, TEXT_PRIMARY);
            cell.setGravity(Gravity.END);
            cell.setSingleLine(true);
            cell.setFontFeatureSettings("tnum");
            cell.setAutoSizeTextTypeUniformWithConfiguration(8, 11, 1, TypedValue.COMPLEX_UNIT_SP);
            LayoutParams p = i == 2 ? ui.size(46, 16) : new LayoutParams(0, ui.dp(16), 1f);
            p.leftMargin = ui.dp(4);
            row.addView(cell, p);
            cells[i] = cell;
        }
        return row;
    }

    private void renderNetworkSubPage() {
        if (trafficCells == null) return;
        String[] names = {L10n.tr("视频"), L10n.tr("音频"), L10n.tr("数据"), L10n.tr("合计")};
        for (int i = 0; i < 4; i++) {
            RemoteNetworkStatistics.Traffic traffic = network.report == null ? null : network.report.traffic[i];
            String in = RemoteNetworkStatistics.bitrate(traffic == null ? -1 : traffic.inbound);
            String out = RemoteNetworkStatistics.bitrate(traffic == null ? -1 : traffic.outbound);
            String loss = RemoteNetworkStatistics.loss(traffic == null ? Double.NaN : traffic.loss);
            trafficCells[i][0].setText(in);
            trafficCells[i][1].setText(out);
            trafficCells[i][2].setText(loss);
            trafficRows[i].setContentDescription(L10n.format("{0}，接收 {1}，发送 {2}，丢包率 {3}", names[i], in, out, loss));
        }
        boolean direct = network.report != null && network.report.mode == 0
                && !network.report.localEndpoint.isEmpty() && !network.report.remoteEndpoint.isEmpty();
        if (!direct) endpointsExpanded = false;
        View modeRow = (View) networkDetails[CONNECTION_MODE].getParent();
        modeRow.setClickable(direct);
        modeRow.setFocusable(direct);
        String mode = network.mode();
        if (direct) mode += endpointsExpanded ? "  ▾" : "  ▸";
        String[] values = {
                network.fps + " FPS",
                network.resolution(),
                RemoteNetworkStatistics.latency(network.latency),
                RemoteNetworkStatistics.latency(network.rtt),
                mode,
                direct ? network.report.localEndpoint : "",
                direct ? network.report.remoteEndpoint : "",
                network.report == null ? "—" : network.report.srtp ? L10n.tr("SRTP 已启用") : L10n.tr("未启用"),
                network.remoteEncoder.isEmpty() ? "—" : network.remoteEncoder,
                network.localDecoder.isEmpty() ? "—" : network.localDecoder
        };
        for (int i = LOCAL_ENDPOINT; i <= REMOTE_ENDPOINT; i++) {
            ((View) networkDetails[i].getParent()).setVisibility(endpointsExpanded ? VISIBLE : GONE);
        }
        for (int i = 0; i < values.length; i++) {
            if (!TextUtils.equals(networkDetails[i].getText(), values[i])) {
                networkDetails[i].setText(values[i]);
            }
        }
        networkDetails[CONNECTION_MODE].setTextColor(direct ? MobileUi.BLUE : TEXT_PRIMARY);
        networkDetails[ENCRYPTION].setTextColor(network.report != null && network.report.srtp ? 0xFF30D158 : TEXT_SECONDARY);
        encryptionLock.setVisibility(network.report != null && network.report.srtp ? VISIBLE : GONE);
        encryptionOpen.setVisibility(network.report != null && !network.report.srtp ? VISIBLE : GONE);
    }

    private void renderResolutions() {
        if (resolutionButton == null) return;
        RemoteDisplayResolutions.Display display = resolutions.display(resolutionDisplay);
        boolean enabled = display != null && !display.modes.isEmpty() && !resolutions.pending;
        resolutionButton.setText(display == null ? L10n.tr("设备不支持") : display.current.label() + " ▾");
        resolutionButton.setEnabled(enabled);
        resolutionButton.setAlpha(enabled ? 1f : 0.45f);
        String feedback = resolutions.feedback();
        resolutionFeedback.setText(feedback);
        resolutionFeedback.setVisibility(feedback.isEmpty() ? GONE : VISIBLE);
    }

    private void chooseResolution() {
        RemoteDisplayResolutions.Display display = resolutions.display(resolutionDisplay);
        if (display == null || resolutions.pending || display.modes.isEmpty()) return;
        String[] labels = new String[display.modes.size()];
        int selected = -1;
        for (int i = 0; i < labels.length; i++) {
            labels[i] = display.modes.get(i).label();
            if (display.current.same(display.modes.get(i))) selected = i;
        }
        resolutionDialog = new AlertDialog.Builder(getContext())
                .setTitle(L10n.tr("被控端分辨率"))
                .setSingleChoiceItems(labels, selected, (dialog, index) -> {
                    dialog.dismiss();
                    resolutionDialog = null;
                    RemoteDisplayResolutions.Display current = resolutions.display(resolutionDisplay);
                    if (current != null && current.id == display.id && current.key.equals(display.key)) {
                        actions.displayResolution(display.modes.get(index));
                    }
                })
                .setNegativeButton(L10n.tr("取消"), null)
                .show();
    }

    private void renderVideoSubPage() {
        if (videoFeedback != null) {
            String fb = settings.feedback();
            videoFeedback.setText(fb);
            videoFeedback.setVisibility(fb.isEmpty() ? GONE : VISIBLE);
        }
        for (VideoSegmentRow row : videoRows) row.render();
    }

    private void renderPrivacy() {
        long now = android.os.SystemClock.elapsedRealtime();
        privacy.tick(now);
        if (privacyFeedbackLabel != null) {
            String fb = privacy.feedback(now);
            privacyFeedbackLabel.setText(fb);
            privacyFeedbackLabel.setVisibility(fb.isEmpty() ? GONE : VISIBLE);
        }
        if (privacyRowView != null && privacyValueLabel != null) {
            boolean canToggle = privacy.canToggle(now);
            privacyValueLabel.setText(privacy.active() ? L10n.tr("已开启") : L10n.tr("已关闭"));
            privacyValueLabel.setTextColor(privacy.active() ? MobileUi.BLUE : TEXT_SECONDARY);
            privacyRowView.setEnabled(canToggle);
            privacyRowView.setAlpha(canToggle ? 1f : 0.45f);
        }
    }

    private void renderFileStatus() {
        if (transferStatusLabel != null) {
            transferStatusLabel.setText(transferStatus);
            transferStatusLabel.setVisibility(transferStatus.isEmpty() ? GONE : VISIBLE);
        }
        if (saveReceivedBtn != null) {
            saveReceivedBtn.setVisibility(hasReceivedFile ? VISIBLE : GONE);
        }
    }

    private void showProfileManagerDialog() {
        List<FloatingKeymapStore.Profile> list = keymapStore.profiles();
        FloatingKeymapStore.Profile current = keymapStore.activeProfile();
        String[] names = new String[list.size()];
        int checked = 0;
        for (int i = 0; i < list.size(); i++) {
            names[i] = list.get(i).name + " (" + list.get(i).widgets.size() + ")";
            if (list.get(i).id.equals(current.id)) checked = i;
        }

        LinearLayout footer = ui.row();
        footer.setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(10));
        AlertDialog[] ref = new AlertDialog[1];

        TextView newBtn = dialogChip("+ " + L10n.tr("新建"), MobileUi.BLUE, Color.WHITE, () -> {
            if (ref[0] != null) ref[0].dismiss();
            promptCreateOrRenameProfile(true, null);
        });
        TextView renameBtn = dialogChip(L10n.tr("改名"), 0xFFE9E9EF, MobileUi.INK, () -> {
            if (ref[0] != null) ref[0].dismiss();
            promptCreateOrRenameProfile(false, keymapStore.activeProfile());
        });
        TextView dupBtn = dialogChip(L10n.tr("复制"), 0xFFE9E9EF, MobileUi.INK, () -> {
            if (ref[0] != null) ref[0].dismiss();
            keymapStore.duplicateProfile(keymapStore.activeProfile().id);
            host.onKeymapChanged();
            rebuild();
        });
        TextView delBtn = dialogChip(L10n.tr("删除"), 0x20FF3B30, MobileUi.RED, () -> {
            if (ref[0] != null) ref[0].dismiss();
            confirmDeleteProfile(keymapStore.activeProfile());
        });

        for (int i = 0; i < 4; i++) {
            TextView b = i == 0 ? newBtn : i == 1 ? renameBtn : i == 2 ? dupBtn : delBtn;
            LayoutParams lp = new LayoutParams(0, ui.dp(36), 1f);
            if (i > 0) lp.leftMargin = ui.dp(6);
            footer.addView(b, lp);
        }

        AlertDialog dialog = new AlertDialog.Builder(getContext())
                .setTitle(L10n.tr("切换操控方案"))
                .setSingleChoiceItems(names, checked, (d, which) -> {
                    keymapStore.selectProfile(list.get(which).id);
                    host.onKeymapChanged();
                    d.dismiss();
                    rebuild();
                })
                .setView(footer)
                .setNegativeButton(L10n.tr("关闭"), null)
                .show();
        ref[0] = dialog;
    }

    private void promptCreateOrRenameProfile(boolean create, FloatingKeymapStore.Profile target) {
        EditText input = new EditText(getContext());
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        String initial = create ? keymapStore.suggestNewProfileName() : (target == null ? "" : target.name);
        input.setText(initial);
        input.setSelection(initial.length());
        input.setTextSize(15);
        input.setTextColor(MobileUi.INK);
        input.setPadding(ui.dp(12), 0, ui.dp(12), 0);
        input.setBackground(ui.background(MobileUi.BACKGROUND, 10));
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(32)});

        LinearLayout wrap = ui.column();
        wrap.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(4));
        wrap.addView(input, ui.size(-1, 46));

        new AlertDialog.Builder(getContext())
                .setTitle(L10n.tr(create ? "新建操控方案" : "修改方案名称"))
                .setView(wrap)
                .setNegativeButton(L10n.tr("取消"), null)
                .setPositiveButton(L10n.tr("保存"), (d, w) -> {
                    String val = input.getText().toString().trim();
                    if (create) {
                        keymapStore.createProfile(val);
                    } else if (target != null && !val.isEmpty()) {
                        keymapStore.renameProfile(target.id, val);
                    }
                    host.onKeymapChanged();
                    rebuild();
                })
                .show();
    }

    private void confirmDeleteProfile(FloatingKeymapStore.Profile target) {
        if (target == null) return;
        new AlertDialog.Builder(getContext())
                .setTitle(L10n.tr("删除操控方案？"))
                .setMessage(target.name)
                .setNegativeButton(L10n.tr("取消"), null)
                .setPositiveButton(L10n.tr("删除"), (d, w) -> {
                    keymapStore.deleteProfile(target.id);
                    host.onKeymapChanged();
                    rebuild();
                })
                .show();
    }

    private TextView dialogChip(String title, int bg, int fg, Runnable click) {
        TextView tv = ui.action(title, fg, click);
        tv.setTextSize(12);
        tv.setMinHeight(0);
        tv.setSingleLine(true);
        tv.setBackground(ui.background(bg, 8));
        return tv;
    }

    private LayoutParams tileLp(int leftMarginDp) {
        LayoutParams lp = new LayoutParams(0, ui.dp(56), 1f);
        if (leftMarginDp > 0) lp.leftMargin = ui.dp(leftMarginDp);
        return lp;
    }

    private View quickTile(String iconName, String title, boolean active, boolean destructive, Runnable click) {
        LinearLayout tile = ui.column();
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(6));
        int bgColor = destructive ? 0x22FF453A : active ? 0x332E7ADB : CARD_BG;
        int strokeColor = destructive ? 0x40FF453A : active ? MobileUi.BLUE : CARD_BORDER;
        int fgColor = destructive ? 0xFFFF5E57 : active ? 0xFF70B2FF : TEXT_PRIMARY;
        GradientDrawable bg = ui.background(bgColor, 12);
        bg.setStroke(Math.max(1, ui.dp(0.8f)), strokeColor);
        tile.setBackground(bg);

        tile.addView(ui.icon(iconName, fgColor), ui.size(19, 19));
        ui.gap(tile, 4);
        TextView cap = darkText(title, 10, active, fgColor);
        cap.setSingleLine(true);
        cap.setGravity(Gravity.CENTER);
        cap.setAutoSizeTextTypeUniformWithConfiguration(8, 11, 1, TypedValue.COMPLEX_UNIT_SP);
        tile.addView(cap, new LayoutParams(LayoutParams.MATCH_PARENT, ui.dp(15)));

        tile.setContentDescription(title);
        tile.setClickable(true);
        tile.setFocusable(true);
        tile.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            click.run();
        });
        return tile;
    }

    private FrameLayout iconCircle(String iconName, String desc, Runnable click) {
        FrameLayout box = new FrameLayout(getContext());
        box.setBackground(ui.background(0xFF282830, 16));
        box.addView(ui.icon(iconName, TEXT_PRIMARY), new FrameLayout.LayoutParams(ui.dp(16), ui.dp(16), Gravity.CENTER));
        box.setContentDescription(desc);
        box.setClickable(true);
        box.setFocusable(true);
        box.setOnClickListener(v -> click.run());
        return box;
    }

    private void sectionTitle(String title) {
        TextView tv = darkText(title, 11, true, TEXT_SECONDARY);
        tv.setPadding(ui.dp(4), 0, 0, ui.dp(5));
        body.addView(tv);
    }

    private LinearLayout cardBox() {
        LinearLayout card = ui.column();
        GradientDrawable bg = ui.background(CARD_BG, 14);
        bg.setStroke(Math.max(1, ui.dp(0.75f)), CARD_BORDER);
        card.setBackground(bg);
        card.setClipToOutline(true);
        return card;
    }

    private void cardDivider(LinearLayout parent) {
        View line = new View(getContext());
        line.setBackgroundColor(CARD_BORDER);
        parent.addView(line, new LayoutParams(LayoutParams.MATCH_PARENT, Math.max(1, ui.dp(0.5f))));
    }

    private View drawerRow(LinearLayout parent, String iconName, String title, String detail, Runnable click) {
        LinearLayout row = ui.row();
        row.setPadding(ui.dp(12), ui.dp(11), ui.dp(10), ui.dp(11));
        row.addView(ui.icon(iconName, 0xFFD1D1D6), ui.size(17, 17));
        TextView titleTv = darkText(title, 13, false, TEXT_PRIMARY);
        LayoutParams tlp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = ui.dp(10);
        row.addView(titleTv, tlp);
        if (!detail.isEmpty()) {
            TextView detailTv = darkText(detail, 12, false, TEXT_SECONDARY);
            detailTv.setMaxWidth(ui.dp(116));
            detailTv.setSingleLine(true);
            detailTv.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(detailTv);
        }
        LayoutParams clp = ui.size(14, 14);
        clp.leftMargin = ui.dp(4);
        row.addView(ui.icon("chevron_right", 0xFF63636A), clp);
        row.setContentDescription(title + (detail.isEmpty() ? "" : "，" + detail));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> click.run());
        parent.addView(row);
        return row;
    }

    private View drawerRowWithValueRef(LinearLayout parent, String iconName, String title, Runnable click) {
        LinearLayout row = ui.row();
        row.setPadding(ui.dp(12), ui.dp(11), ui.dp(10), ui.dp(11));
        row.addView(ui.icon(iconName, 0xFFD1D1D6), ui.size(17, 17));
        TextView titleTv = darkText(title, 13, false, TEXT_PRIMARY);
        LayoutParams tlp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = ui.dp(10);
        row.addView(titleTv, tlp);
        privacyValueLabel = darkText("", 12, false, TEXT_SECONDARY);
        row.addView(privacyValueLabel);
        LayoutParams clp = ui.size(14, 14);
        clp.leftMargin = ui.dp(4);
        row.addView(ui.icon("chevron_right", 0xFF63636A), clp);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> click.run());
        parent.addView(row);
        return row;
    }

    private LinearLayout darkSegments(String[] titles, int selectedIndex, boolean enabled,
                                      java.util.function.IntConsumer changed) {
        LinearLayout bar = ui.row();
        bar.setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3));
        bar.setBackground(ui.background(0xFF16161B, 10));
        TextView[] items = new TextView[titles.length];
        java.util.function.IntConsumer applyStyle = sel -> {
            for (int i = 0; i < items.length; i++) {
                boolean active = i == sel;
                items[i].setTextColor(active ? Color.WHITE : TEXT_SECONDARY);
                items[i].setTypeface(Typeface.create("sans-serif-medium", active ? Typeface.BOLD : Typeface.NORMAL));
                items[i].setBackground(ui.background(active ? MobileUi.BLUE : Color.TRANSPARENT, 8));
            }
        };
        final int[] current = {Math.max(0, Math.min(titles.length - 1, selectedIndex))};
        for (int i = 0; i < titles.length; i++) {
            final int idx = i;
            TextView tv = ui.action(titles[i], TEXT_PRIMARY, () -> {
                if (current[0] == idx) return;
                current[0] = idx;
                applyStyle.accept(idx);
                changed.accept(idx);
            });
            tv.setTextSize(12);
            tv.setMinHeight(0);
            tv.setSingleLine(true);
            tv.setEnabled(enabled);
            tv.setAlpha(enabled ? 1f : 0.45f);
            items[i] = tv;
            bar.addView(tv, new LayoutParams(0, ui.dp(30), 1f));
        }
        applyStyle.accept(current[0]);
        return bar;
    }

    private TextView darkText(String value, int sp, boolean bold, int color) {
        TextView tv = ui.text(value, sp, bold);
        tv.setTextColor(color);
        return tv;
    }

    private final class VideoSegmentRow {
        final int field;
        final int[] values;
        final LinearLayout container;
        final String[] labels;

        VideoSegmentRow(LinearLayout parent, int field, String title, String[] labels, int[] values) {
            this.field = field;
            this.labels = labels;
            this.values = values;
            parent.addView(darkText(title, 12, false, TEXT_SECONDARY));
            ui.gap(parent, 5);
            container = ui.column();
            parent.addView(container);
            render();
        }

        int selectionIndex() {
            int selected = field == 0 ? settings.selection.quality
                    : field == 1 ? settings.selection.frameRate : settings.selection.preference;
            for (int i = 0; i < values.length; i++) {
                if (values[i] == selected) return i;
            }
            return 0;
        }

        void render() {
            container.removeAllViews();
            container.addView(darkSegments(labels, selectionIndex(), settings.supported,
                    idx -> actions.videoSettings(field, values[idx])));
        }
    }
}
