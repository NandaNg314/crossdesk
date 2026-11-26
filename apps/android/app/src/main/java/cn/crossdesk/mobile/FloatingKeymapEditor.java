package cn.crossdesk.mobile;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Full-screen visual drag-and-drop floating keymap editor over the live desktop video.
 */
@SuppressLint("ViewConstructor")
final class FloatingKeymapEditor extends FrameLayout {
    interface Listener {
        void onSaved();
        void onCancelled();
    }

    private final MobileUi ui;
    private final FloatingKeymapStore store;
    private final String profileId;
    private String profileName;
    private String platform;
    private final Listener listener;

    private final List<FloatingKeymapStore.Widget> workingWidgets = new ArrayList<>();
    private final FrameLayout stage;
    private final LinearLayout topBar;
    private final TextView profileNameLabel;
    private final LinearLayout propertyCard;
    private final TextView selectedTitleLabel;
    private final TextView editLabelBtn;
    private final TextView editBindingBtn;
    private final TextView sizeValueLabel;
    private final SeekBar sizeSeek;
    private final TextView opacityValueLabel;
    private final SeekBar opacitySeek;

    private String selectedWidgetId;
    private boolean updatingSliders;
    private boolean showCenterGuidesX, showCenterGuidesY;
    private int addSpawnIndex;
    private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    FloatingKeymapEditor(Context context, MobileUi ui, FloatingKeymapStore store,
                         String platform, Listener listener) {
        super(context);
        this.ui = ui;
        this.store = store;
        this.platform = platform == null ? "windows" : platform;
        this.listener = listener;

        FloatingKeymapStore.Profile current = store.activeProfile();
        this.profileId = current.id;
        this.profileName = current.name;
        this.workingWidgets.addAll(current.copyWidgets(false));

        setBackgroundColor(0x4D000000);
        setClickable(true);
        setFocusable(true);
        setElevation(ui.dp(24));

        guidePaint.setColor(0x442E7ADB);
        guidePaint.setStrokeWidth(Math.max(1f, ui.dp(1f)));

        stage = new FrameLayout(context) {
            @Override protected void dispatchDraw(Canvas canvas) {
                if (showCenterGuidesX) {
                    float cx = getWidth() / 2f;
                    canvas.drawLine(cx, 0, cx, getHeight(), guidePaint);
                }
                if (showCenterGuidesY) {
                    float cy = getHeight() / 2f;
                    canvas.drawLine(0, cy, getWidth(), cy, guidePaint);
                }
                super.dispatchDraw(canvas);
            }
        };
        stage.setClipChildren(false);
        stage.setOnClickListener(v -> selectWidget(null));
        addView(stage, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // Top Frosted Bar
        FrameLayout topShell = new FrameLayout(context);
        GradientDrawable topBg = ui.background(0xE816161A, 14);
        topBg.setStroke(Math.max(1, ui.dp(0.75f)), 0x26FFFFFF);
        topShell.setBackground(topBg);
        topShell.setPadding(ui.dp(8), ui.dp(5), ui.dp(8), ui.dp(5));

        HorizontalScrollView topScroll = new HorizontalScrollView(context);
        topScroll.setHorizontalScrollBarEnabled(false);
        topBar = ui.row();
        topScroll.addView(topBar, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        topShell.addView(topScroll, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        LinearLayout namePill = ui.row();
        namePill.setBackground(pillBg(0xFF26262C));
        namePill.setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6));
        profileNameLabel = label(profileName, 12, true, Color.WHITE);
        profileNameLabel.setMaxWidth(ui.dp(132));
        profileNameLabel.setSingleLine(true);
        profileNameLabel.setEllipsize(TextUtils.TruncateAt.END);
        namePill.addView(profileNameLabel);
        MobileUi.Icon editIcon = ui.icon("edit", 0xFFB0B0B8);
        LinearLayout.LayoutParams editLp = ui.size(13, 13);
        editLp.leftMargin = ui.dp(5);
        namePill.addView(editIcon, editLp);
        namePill.setClickable(true);
        namePill.setFocusable(true);
        namePill.setOnClickListener(v -> promptRenameProfile());
        topBar.addView(namePill);

        addBarButton(topBar, "+ " + L10n.tr("单个按键"), 0xFF26262C, Color.WHITE, this::promptAddSingleKey);
        addBarButton(topBar, "+ " + L10n.tr("组合键"), 0xFF26262C, Color.WHITE, this::promptAddComboKey);
        addBarButton(topBar, "+ " + L10n.tr("鼠标/功能"), 0xFF26262C, Color.WHITE, this::promptAddMouseOrAction);
        addBarButton(topBar, L10n.tr("清空"), 0x24FF453A, 0xFFFF6961, this::confirmClearAll);
        addBarButton(topBar, L10n.tr("取消"), 0xFF26262C, 0xFFD1D1D6, listener::onCancelled);
        addBarButton(topBar, L10n.tr("保存"), MobileUi.BLUE, Color.WHITE, this::commitSave);

        LayoutParams topParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        topParams.setMargins(ui.dp(8), ui.dp(8), ui.dp(8), 0);
        addView(topShell, topParams);

        // Compact Selected Widget Property Bar
        propertyCard = ui.column();
        GradientDrawable propBg = ui.background(0xEE18181D, 16);
        propBg.setStroke(Math.max(1, ui.dp(0.75f)), 0x28FFFFFF);
        propertyCard.setBackground(propBg);
        propertyCard.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        propertyCard.setClickable(true);
        propertyCard.setVisibility(GONE);

        LinearLayout propHeader = ui.row();
        selectedTitleLabel = label("", 12, true, Color.WHITE);
        selectedTitleLabel.setSingleLine(true);
        selectedTitleLabel.setEllipsize(TextUtils.TruncateAt.END);
        propHeader.addView(selectedTitleLabel, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        editLabelBtn = smallActionChip(L10n.tr("改标签"), 0xFF2A2A32, Color.WHITE, this::promptEditSelectedLabel);
        propHeader.addView(editLabelBtn);

        editBindingBtn = smallActionChip(L10n.tr("改按键"), 0xFF2A2A32, Color.WHITE, this::promptEditSelectedBinding);
        LinearLayout.LayoutParams bindLp = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, ui.dp(28));
        bindLp.leftMargin = ui.dp(6);
        propHeader.addView(editBindingBtn, bindLp);

        TextView deleteBtn = smallActionChip(L10n.tr("删除"), 0x28FF453A, 0xFFFF5E57, this::deleteSelectedWidget);
        LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, ui.dp(28));
        delLp.leftMargin = ui.dp(6);
        propHeader.addView(deleteBtn, delLp);
        propertyCard.addView(propHeader);

        ui.gap(propertyCard, 8);

        LinearLayout slidersWrap = ui.row();
        // Size slider
        TextView sizeCap = label(L10n.tr("大小"), 11, false, 0xFFA1A1AA);
        slidersWrap.addView(sizeCap);
        sizeSeek = new SeekBar(context);
        sizeSeek.setMax(FloatingKeymapStore.MAX_SIZE_DP - FloatingKeymapStore.MIN_SIZE_DP);
        slidersWrap.addView(sizeSeek, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        sizeValueLabel = label("48", 11, true, Color.WHITE);
        sizeValueLabel.setFontFeatureSettings("tnum");
        slidersWrap.addView(sizeValueLabel, ui.size(30, -2));

        View sep = new View(context);
        slidersWrap.addView(sep, ui.size(10, 1));

        // Opacity slider
        TextView opCap = label(L10n.tr("透明"), 11, false, 0xFFA1A1AA);
        slidersWrap.addView(opCap);
        opacitySeek = new SeekBar(context);
        opacitySeek.setMax(80); // 20%..100%
        slidersWrap.addView(opacitySeek, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        opacityValueLabel = label("78%", 11, true, Color.WHITE);
        opacityValueLabel.setFontFeatureSettings("tnum");
        slidersWrap.addView(opacityValueLabel, ui.size(34, -2));
        propertyCard.addView(slidersWrap);

        SeekBar.OnSeekBarChangeListener seekListener = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (updatingSliders) return;
                FloatingKeymapStore.Widget sel = findSelected();
                if (sel == null) return;
                if (bar == sizeSeek) {
                    sel.sizeDp = FloatingKeymapStore.MIN_SIZE_DP + progress;
                    sizeValueLabel.setText(String.valueOf(sel.sizeDp));
                } else {
                    int pct = 20 + progress;
                    sel.opacity = pct / 100f;
                    opacityValueLabel.setText(String.format(java.util.Locale.ROOT, "%d%%", pct));
                }
                renderStage();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        };
        sizeSeek.setOnSeekBarChangeListener(seekListener);
        opacitySeek.setOnSeekBarChangeListener(seekListener);

        LayoutParams propParams = new LayoutParams(ui.dp(420), LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        propParams.setMargins(ui.dp(10), ui.dp(56), ui.dp(10), ui.dp(10));
        addView(propertyCard, propParams);

        renderStage();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int maxW = Math.max(ui.dp(260), MeasureSpec.getSize(widthSpec) - ui.dp(20));
        LayoutParams lp = (LayoutParams) propertyCard.getLayoutParams();
        lp.width = Math.min(ui.dp(460), maxW);
        super.onMeasure(widthSpec, heightSpec);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        post(this::renderStage);
    }

    private GradientDrawable pillBg(int color) {
        GradientDrawable d = ui.background(color, 9);
        d.setStroke(Math.max(1, ui.dp(0.5f)), 0x22FFFFFF);
        return d;
    }

    private TextView label(String text, int sp, boolean bold, int color) {
        TextView tv = ui.text(text, sp, bold);
        tv.setTextColor(color);
        return tv;
    }

    private void addBarButton(LinearLayout parent, String title, int bgColor, int textColor, Runnable click) {
        TextView btn = smallActionChip(title, bgColor, textColor, click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, ui.dp(30));
        lp.leftMargin = ui.dp(6);
        parent.addView(btn, lp);
    }

    private TextView smallActionChip(String title, int bgColor, int textColor, Runnable click) {
        TextView btn = ui.action(title, textColor, click);
        btn.setTextSize(12);
        btn.setMinHeight(0);
        btn.setSingleLine(true);
        btn.setPadding(ui.dp(10), 0, ui.dp(10), 0);
        btn.setBackground(pillBg(bgColor));
        return btn;
    }

    private FloatingKeymapStore.Widget findSelected() {
        if (selectedWidgetId == null) return null;
        for (FloatingKeymapStore.Widget w : workingWidgets) {
            if (w.id.equals(selectedWidgetId)) return w;
        }
        return null;
    }

    private void selectWidget(String id) {
        selectedWidgetId = id;
        FloatingKeymapStore.Widget sel = findSelected();
        if (sel == null) {
            propertyCard.setVisibility(GONE);
        } else {
            propertyCard.setVisibility(VISIBLE);
            updatePropertyBar(sel);
        }
        renderStage();
    }

    private void updatePropertyBar(FloatingKeymapStore.Widget sel) {
        updatingSliders = true;
        selectedTitleLabel.setText(sel.displayLabel(platform));
        boolean rebindable = sel.type == FloatingKeymapStore.WidgetType.SINGLE_KEY
                || sel.type == FloatingKeymapStore.WidgetType.COMBO_KEY;
        editBindingBtn.setVisibility(rebindable ? VISIBLE : GONE);
        sizeSeek.setProgress(Math.max(0, Math.min(sizeSeek.getMax(), sel.sizeDp - FloatingKeymapStore.MIN_SIZE_DP)));
        sizeValueLabel.setText(String.valueOf(sel.sizeDp));
        int pct = Math.max(20, Math.min(100, Math.round(sel.opacity * 100f)));
        opacitySeek.setProgress(pct - 20);
        opacityValueLabel.setText(String.format(java.util.Locale.ROOT, "%d%%", pct));
        // Dock property card near top if selected button is in bottom 36% of canvas so it never obscures it.
        LayoutParams lp = (LayoutParams) propertyCard.getLayoutParams();
        int targetGravity = (sel.normY > 0.64f ? Gravity.TOP : Gravity.BOTTOM) | Gravity.CENTER_HORIZONTAL;
        if (lp.gravity != targetGravity) {
            lp.gravity = targetGravity;
            propertyCard.setLayoutParams(lp);
        }
        updatingSliders = false;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void renderStage() {
        stage.removeAllViews();
        int sw = stage.getWidth();
        int sh = stage.getHeight();
        if (sw <= 0 || sh <= 0) return;
        int margin = ui.dp(6);

        for (FloatingKeymapStore.Widget widget : workingWidgets) {
            boolean selected = widget.id.equals(selectedWidgetId);
            boolean isWheel = widget.type == FloatingKeymapStore.WidgetType.SCROLL_WHEEL;
            int wPx = ui.dp(isWheel ? Math.max(36, Math.round(widget.sizeDp * 0.84f)) : widget.sizeDp);
            int hPx = ui.dp(isWheel ? Math.round(widget.sizeDp * 1.95f) : widget.sizeDp);

            View node;
            if (isWheel) {
                node = new FloatingKeymapOverlay.FloatingWheelPill(getContext(), ui, widget.displayLabel(platform));
            } else {
                TextView tv = new TextView(getContext());
                tv.setGravity(Gravity.CENTER);
                tv.setTextColor(0xFFF5F5F7);
                tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
                tv.setIncludeFontPadding(false);
                tv.setMaxLines(2);
                tv.setEllipsize(TextUtils.TruncateAt.END);
                tv.setPadding(ui.dp(4), ui.dp(3), ui.dp(4), ui.dp(3));
                tv.setText(widget.displayLabel(platform));
                tv.setAutoSizeTextTypeUniformWithConfiguration(8, 13, 1, TypedValue.COMPLEX_UNIT_SP);
                node = tv;
            }
            int radiusDp = Math.round(Math.min(wPx, hPx) / (2f * getResources().getDisplayMetrics().density));
            GradientDrawable bg = ui.background(selected ? 0xE6223554 : 0xCC1C1C22, radiusDp);
            bg.setStroke(ui.dp(selected ? 2f : 1f), selected ? MobileUi.BLUE : 0x55FFFFFF);
            node.setBackground(bg);
            node.setAlpha(widget.opacity);

            float x = clamp(widget.normX * sw - wPx / 2f, margin, Math.max(margin, sw - wPx - margin));
            float y = clamp(widget.normY * sh - hPx / 2f, margin, Math.max(margin, sh - hPx - margin));
            node.setTranslationX(x);
            node.setTranslationY(y);

            node.setOnTouchListener(new OnTouchListener() {
                int pointer = -1;
                float startRawX, startRawY, startTransX, startTransY;
                boolean moved;

                @Override public boolean onTouch(View v, MotionEvent event) {
                    int action = event.getActionMasked();
                    if (action == MotionEvent.ACTION_DOWN) {
                        pointer = event.getPointerId(0);
                        startRawX = event.getRawX();
                        startRawY = event.getRawY();
                        startTransX = v.getTranslationX();
                        startTransY = v.getTranslationY();
                        moved = false;
                        v.bringToFront();
                        return true;
                    }
                    if (pointer == -1) return true;
                    if (action == MotionEvent.ACTION_MOVE) {
                        int p = event.findPointerIndex(pointer);
                        if (p < 0) return true;
                        float rx = event.getRawX() + (event.getX(p) - event.getX());
                        float ry = event.getRawY() + (event.getY(p) - event.getY());
                        float dx = rx - startRawX;
                        float dy = ry - startRawY;
                        if (!moved && Math.hypot(dx, dy) <= ui.dp(4)) return true;
                        moved = true;
                        float nx = clamp(startTransX + dx, margin, Math.max(margin, sw - wPx - margin));
                        float ny = clamp(startTransY + dy, margin, Math.max(margin, sh - hPx - margin));
                        v.setTranslationX(nx);
                        v.setTranslationY(ny);
                        widget.normX = clamp((nx + wPx / 2f) / sw, 0.04f, 0.96f);
                        widget.normY = clamp((ny + hPx / 2f) / sh, 0.06f, 0.94f);
                        showCenterGuidesX = Math.abs(widget.normX - 0.5f) < 0.015f;
                        showCenterGuidesY = Math.abs(widget.normY - 0.5f) < 0.015f;
                        if (showCenterGuidesX) widget.normX = 0.5f;
                        if (showCenterGuidesY) widget.normY = 0.5f;
                        stage.invalidate();
                        if (!widget.id.equals(selectedWidgetId)) {
                            selectedWidgetId = widget.id;
                            propertyCard.setVisibility(VISIBLE);
                        }
                        updatePropertyBar(widget);
                        return true;
                    }
                    if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                            || (action == MotionEvent.ACTION_POINTER_UP
                            && event.getPointerId(event.getActionIndex()) == pointer)) {
                        pointer = -1;
                        showCenterGuidesX = false;
                        showCenterGuidesY = false;
                        stage.invalidate();
                        selectWidget(widget.id);
                    }
                    return true;
                }
            });

            stage.addView(node, new FrameLayout.LayoutParams(wPx, hPx, Gravity.TOP | Gravity.LEFT));
        }
    }

    private float[] nextSpawnPoint() {
        float offset = ((addSpawnIndex++ % 5) - 2) * 0.05f;
        return new float[]{clamp(0.50f + offset, 0.20f, 0.80f), clamp(0.46f + offset * 0.6f, 0.24f, 0.72f)};
    }

    private void addAndSelect(FloatingKeymapStore.Widget widget) {
        if (workingWidgets.size() >= FloatingKeymapStore.MAX_WIDGETS_PER_PROFILE) return;
        workingWidgets.add(widget);
        selectWidget(widget.id);
    }

    private void promptRenameProfile() {
        EditText input = dialogInput(profileName, L10n.tr("输入方案名称"));
        LinearLayout box = dialogPadding(input);
        new AlertDialog.Builder(getContext())
                .setTitle(L10n.tr("修改方案名称"))
                .setView(box)
                .setNegativeButton(L10n.tr("取消"), null)
                .setPositiveButton(L10n.tr("确定"), (d, w) -> {
                    String val = input.getText().toString().trim();
                    if (!val.isEmpty()) {
                        profileName = val.length() > 32 ? val.substring(0, 32) : val;
                        profileNameLabel.setText(profileName);
                    }
                }).show();
    }

    private void promptEditSelectedLabel() {
        FloatingKeymapStore.Widget sel = findSelected();
        if (sel == null) return;
        EditText input = dialogInput(sel.label, sel.displayLabel(platform));
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(24)});
        LinearLayout box = dialogPadding(input);
        new AlertDialog.Builder(getContext())
                .setTitle(L10n.tr("自定义按钮文字"))
                .setView(box)
                .setNegativeButton(L10n.tr("取消"), null)
                .setNeutralButton(L10n.tr("恢复默认"), (d, w) -> {
                    sel.label = "";
                    updatePropertyBar(sel);
                    renderStage();
                })
                .setPositiveButton(L10n.tr("确定"), (d, w) -> {
                    sel.label = input.getText().toString().trim();
                    updatePropertyBar(sel);
                    renderStage();
                }).show();
    }

    private void promptEditSelectedBinding() {
        FloatingKeymapStore.Widget sel = findSelected();
        if (sel == null) return;
        if (sel.type == FloatingKeymapStore.WidgetType.SINGLE_KEY) {
            showSingleKeyPicker(sel.key, entry -> {
                sel.key = entry.code;
                updatePropertyBar(sel);
                renderStage();
            });
        } else if (sel.type == FloatingKeymapStore.WidgetType.COMBO_KEY) {
            showComboBuilderDialog(sel);
        }
    }

    private void deleteSelectedWidget() {
        if (selectedWidgetId == null) return;
        workingWidgets.removeIf(w -> w.id.equals(selectedWidgetId));
        selectWidget(null);
    }

    private void confirmClearAll() {
        if (workingWidgets.isEmpty()) return;
        new AlertDialog.Builder(getContext())
                .setTitle(L10n.tr("清空当前画布？"))
                .setMessage(L10n.tr("清空后点击“保存”才会覆盖当前方案，点击“取消”可放弃更改。"))
                .setNegativeButton(L10n.tr("取消"), null)
                .setPositiveButton(L10n.tr("清空"), (d, w) -> {
                    workingWidgets.clear();
                    selectWidget(null);
                }).show();
    }

    private void commitSave() {
        store.saveProfileWidgets(profileId, profileName, workingWidgets);
        store.setEnabled(true);
        listener.onSaved();
    }

    private void promptAddSingleKey() {
        showSingleKeyPicker(0, entry -> {
            float[] pos = nextSpawnPoint();
            addAndSelect(new FloatingKeymapStore.Widget(
                    UUID.randomUUID().toString(),
                    FloatingKeymapStore.WidgetType.SINGLE_KEY,
                    "",
                    entry.code,
                    null,
                    pos[0], pos[1],
                    FloatingKeymapStore.DEFAULT_SIZE_DP,
                    store.globalOpacity()));
        });
    }

    private void showSingleKeyPicker(int currentKey, java.util.function.Consumer<KeyCatalog.Entry> picked) {
        AlertDialog[] holder = new AlertDialog[1];
        LinearLayout root = ui.column();
        root.setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(8));

        HorizontalScrollView catScroll = new HorizontalScrollView(getContext());
        catScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout catRow = ui.row();
        catScroll.addView(catRow);
        root.addView(catScroll, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, ui.dp(36)));
        ui.gap(root, 8);

        ScrollView keyScroll = new ScrollView(getContext());
        LinearLayout keyGrid = ui.column();
        keyScroll.addView(keyGrid);
        root.addView(keyScroll, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, ui.dp(240)));

        String[] cats = KeyCatalog.categoryTitles();
        TextView[] catTabs = new TextView[cats.length];

        java.util.function.IntConsumer selectCategory = catIndex -> {
            for (int i = 0; i < catTabs.length; i++) {
                boolean active = i == catIndex;
                catTabs[i].setTextColor(active ? Color.WHITE : MobileUi.INK);
                catTabs[i].setBackground(ui.background(active ? MobileUi.BLUE : 0xFFE9E9EF, 8));
            }
            keyGrid.removeAllViews();
            List<KeyCatalog.Entry> items = KeyCatalog.byCategory(catIndex);
            int cols = 4;
            for (int i = 0; i < items.size(); i += cols) {
                LinearLayout row = ui.row();
                for (int j = 0; j < cols; j++) {
                    int idx = i + j;
                    LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, ui.dp(38), 1f);
                    if (j > 0) cell.leftMargin = ui.dp(6);
                    if (idx >= items.size()) {
                        row.addView(new View(getContext()), cell);
                        continue;
                    }
                    KeyCatalog.Entry entry = items.get(idx);
                    boolean active = entry.code == currentKey;
                    TextView btn = ui.action(KeyCatalog.isModifier(entry.code)
                                    ? KeyCatalog.modifierName(entry.code, platform) : entry.shortLabel,
                            active ? Color.WHITE : MobileUi.INK,
                            () -> {
                                if (holder[0] != null) holder[0].dismiss();
                                picked.accept(entry);
                            });
                    btn.setTextSize(12);
                    btn.setMinHeight(0);
                    btn.setSingleLine(true);
                    btn.setBackground(ui.background(active ? MobileUi.BLUE : 0xFFF2F2F7, 8));
                    row.addView(btn, cell);
                }
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
                if (i > 0) rlp.topMargin = ui.dp(6);
                keyGrid.addView(row, rlp);
            }
        };

        int initialCat = KeyCatalog.CATEGORY_CONTROL;
        for (KeyCatalog.Entry e : KeyCatalog.entries()) {
            if (e.code == currentKey) {
                initialCat = e.category;
                break;
            }
        }
        for (int i = 0; i < cats.length; i++) {
            final int cat = i;
            TextView tab = ui.action(cats[i], MobileUi.INK, () -> selectCategory.accept(cat));
            tab.setTextSize(12);
            tab.setMinHeight(0);
            tab.setPadding(ui.dp(10), 0, ui.dp(10), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, ui.dp(30));
            if (i > 0) lp.leftMargin = ui.dp(6);
            catTabs[i] = tab;
            catRow.addView(tab, lp);
        }
        selectCategory.accept(initialCat);

        holder[0] = new AlertDialog.Builder(getContext())
                .setTitle(L10n.tr("选择按键"))
                .setView(root)
                .setNegativeButton(L10n.tr("取消"), null)
                .show();
    }

    private void promptAddComboKey() {
        showComboBuilderDialog(null);
    }

    private void showComboBuilderDialog(FloatingKeymapStore.Widget existing) {
        LinearLayout body = ui.column();
        body.setPadding(ui.dp(18), ui.dp(10), ui.dp(18), ui.dp(6));

        final boolean[] modChecked = new boolean[4];
        if (existing != null) {
            for (int m : existing.modifiers) {
                for (int i = 0; i < 4; i++) {
                    if (KeyCatalog.MODIFIERS[i] == m) modChecked[i] = true;
                }
            }
        } else {
            modChecked[0] = true; // default Ctrl
        }
        final int[] chosenKey = {existing != null ? existing.key : 'C'};

        TextView preview = ui.text("", 14, true);
        preview.setTextColor(MobileUi.BLUE);

        EditText customLabel = dialogInput(existing == null ? "" : existing.label, L10n.tr("自定义按钮文字（留空自动生成）"));
        customLabel.setFilters(new InputFilter[]{new InputFilter.LengthFilter(24)});

        TextView keyButton = ui.action("", MobileUi.INK, null);
        keyButton.setTextSize(13);
        keyButton.setMinHeight(ui.dp(40));
        keyButton.setBackground(ui.background(0xFFF2F2F7, 10));

        Runnable refreshPreview = () -> {
            List<Integer> mods = new ArrayList<>();
            for (int i = 0; i < 4; i++) if (modChecked[i]) mods.add(KeyCatalog.MODIFIERS[i]);
            int[] arr = mods.stream().mapToInt(Integer::intValue).toArray();
            String chord = KeyCatalog.chordLabel(chosenKey[0], arr, platform);
            preview.setText(chord.isEmpty() ? L10n.tr("请至少选择一个修饰键或主按键") : chord);
            keyButton.setText(chosenKey[0] == 0 ? L10n.tr("主按键：无（仅修饰键）")
                    : L10n.tr("主按键：") + KeyCatalog.fullKeyLabel(chosenKey[0], platform));
        };

        LinearLayout modRow = ui.row();
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            CheckBox cb = new CheckBox(getContext());
            cb.setText(KeyCatalog.modifierName(KeyCatalog.MODIFIERS[i], platform));
            cb.setTextSize(12);
            cb.setChecked(modChecked[i]);
            cb.setOnCheckedChangeListener((b, checked) -> {
                modChecked[idx] = checked;
                refreshPreview.run();
            });
            modRow.addView(cb, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        }

        LinearLayout keyRow = ui.row();
        keyButton.setOnClickListener(v -> showSingleKeyPicker(chosenKey[0], entry -> {
            chosenKey[0] = entry.code;
            refreshPreview.run();
        }));
        keyRow.addView(keyButton, new LinearLayout.LayoutParams(0, ui.dp(40), 1f));
        TextView clearKeyBtn = ui.action(L10n.tr("仅修饰键"), MobileUi.SECONDARY, () -> {
            chosenKey[0] = 0;
            refreshPreview.run();
        });
        clearKeyBtn.setTextSize(12);
        clearKeyBtn.setMinHeight(ui.dp(40));
        clearKeyBtn.setPadding(ui.dp(10), 0, ui.dp(10), 0);
        keyRow.addView(clearKeyBtn);

        body.addView(ui.text(L10n.tr("修饰键"), 12, false));
        body.addView(modRow);
        ui.gap(body, 8);
        body.addView(keyRow);
        ui.gap(body, 10);
        body.addView(customLabel, ui.size(-1, 44));
        ui.gap(body, 8);
        body.addView(preview);
        refreshPreview.run();

        ScrollView scroll = new ScrollView(getContext());
        scroll.addView(body);

        AlertDialog dialog = new AlertDialog.Builder(getContext())
                .setTitle(L10n.tr(existing == null ? "添加组合键" : "编辑组合键"))
                .setView(scroll)
                .setNegativeButton(L10n.tr("取消"), null)
                .setPositiveButton(L10n.tr("确定"), null)
                .show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            List<Integer> mods = new ArrayList<>();
            for (int i = 0; i < 4; i++) if (modChecked[i]) mods.add(KeyCatalog.MODIFIERS[i]);
            int[] arr = KeyCatalog.normalizeModifiers(mods.stream().mapToInt(Integer::intValue).toArray());
            if (arr.length == 0 && chosenKey[0] == 0) return;
            String lbl = customLabel.getText().toString().trim();
            if (existing != null) {
                existing.modifiers = arr;
                existing.key = chosenKey[0];
                existing.label = lbl;
                updatePropertyBar(existing);
                renderStage();
            } else {
                float[] pos = nextSpawnPoint();
                addAndSelect(new FloatingKeymapStore.Widget(
                        UUID.randomUUID().toString(),
                        FloatingKeymapStore.WidgetType.COMBO_KEY,
                        lbl,
                        chosenKey[0],
                        arr,
                        pos[0], pos[1],
                        50,
                        store.globalOpacity()));
            }
            dialog.dismiss();
        });
    }

    private void promptAddMouseOrAction() {
        String[] titles = {
                L10n.tr("鼠标左键（支持按住拖拽）"),
                L10n.tr("鼠标右键（支持按住拖拽）"),
                L10n.tr("鼠标中键（支持按住拖拽）"),
                L10n.tr("滚轮条（上下滑动滚动 / 点按微调）"),
                L10n.tr("粘贴板（单击同步粘贴 / 长按编辑）"),
                L10n.tr("打开操作侧边栏"),
                L10n.tr("展开/收起底部键盘")
        };
        FloatingKeymapStore.WidgetType[] types = {
                FloatingKeymapStore.WidgetType.MOUSE_LEFT,
                FloatingKeymapStore.WidgetType.MOUSE_RIGHT,
                FloatingKeymapStore.WidgetType.MOUSE_MIDDLE,
                FloatingKeymapStore.WidgetType.SCROLL_WHEEL,
                FloatingKeymapStore.WidgetType.ACTION_CLIPBOARD,
                FloatingKeymapStore.WidgetType.ACTION_DRAWER,
                FloatingKeymapStore.WidgetType.ACTION_KEYBOARD
        };
        new AlertDialog.Builder(getContext())
                .setTitle(L10n.tr("添加鼠标或快捷功能"))
                .setItems(titles, (d, which) -> {
                    float[] pos = nextSpawnPoint();
                    addAndSelect(new FloatingKeymapStore.Widget(
                            UUID.randomUUID().toString(),
                            types[which],
                            "",
                            0,
                            null,
                            pos[0], pos[1],
                            FloatingKeymapStore.DEFAULT_SIZE_DP,
                            store.globalOpacity()));
                })
                .setNegativeButton(L10n.tr("取消"), null)
                .show();
    }

    private EditText dialogInput(String initial, String hint) {
        EditText v = new EditText(getContext());
        v.setSingleLine(true);
        v.setInputType(InputType.TYPE_CLASS_TEXT);
        v.setText(initial);
        v.setHint(hint);
        v.setTextSize(15);
        v.setTextColor(MobileUi.INK);
        v.setHintTextColor(MobileUi.SECONDARY);
        v.setPadding(ui.dp(12), 0, ui.dp(12), 0);
        v.setBackground(ui.background(MobileUi.BACKGROUND, 10));
        v.setFilters(new InputFilter[]{new InputFilter.LengthFilter(32)});
        if (!initial.isEmpty()) v.setSelection(initial.length());
        return v;
    }

    private LinearLayout dialogPadding(EditText input) {
        LinearLayout box = ui.column();
        box.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(4));
        box.addView(input, ui.size(-1, 46));
        return box;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
