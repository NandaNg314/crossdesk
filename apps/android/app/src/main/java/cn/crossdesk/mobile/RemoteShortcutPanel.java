package cn.crossdesk.mobile;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Configuration;
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
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Multi-column responsive shortcut grid embedded inside the 3-tab bottom keyboard.
 * Supports +Add (up to 64 shortcuts) and inline Edit mode (delete badge, reorder, edit, reset).
 */
final class RemoteShortcutPanel {
    private final Context context;
    private final MobileUi ui;
    final RemoteShortcuts store;
    private final Consumer<RemoteShortcuts.Item> send;
    private final Runnable changed;
    private String platform = "windows";
    private boolean editing;
    private AlertDialog activeDialog;

    RemoteShortcutPanel(Context context, MobileUi ui, Consumer<RemoteShortcuts.Item> send, Runnable changed) {
        this.context = context;
        this.ui = ui;
        this.send = send;
        this.changed = changed;
        this.store = new RemoteShortcuts(context);
    }

    void platform(String value) {
        String next = RemoteShortcuts.platform(value);
        if (!platform.equals(next)) {
            platform = next;
            changed.run();
        }
    }

    boolean isEditing() { return editing; }

    void setEditing(boolean value) {
        if (editing == value) return;
        editing = value;
        changed.run();
    }

    void close() {
        editing = false;
        if (activeDialog != null) {
            activeDialog.dismiss();
            activeDialog = null;
        }
    }

    private boolean dark() {
        return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private int columnsForWidth(int widthPx) {
        if (widthPx >= ui.dp(860)) return 6;
        if (widthPx >= ui.dp(540)) return 5;
        if (widthPx >= ui.dp(370)) return 4;
        return 3;
    }

    View create(int containerWidthPx) {
        int cols = columnsForWidth(containerWidthPx > 0 ? containerWidthPx : ui.dp(390));
        List<RemoteShortcuts.Item> items = store.list(platform);

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout grid = ui.column();
        grid.setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(6));
        scroll.addView(grid, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        if (items.isEmpty()) {
            TextView empty = ui.text(L10n.tr("暂无快捷键，点击右上角“+ 添加”或“还原”"), 13, false);
            empty.setTextColor(dark() ? 0xFF98989F : MobileUi.SECONDARY);
            empty.setGravity(Gravity.CENTER);
            grid.addView(empty, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(120)));
            return scroll;
        }

        boolean isDark = dark();
        int cardBgColor = isDark ? 0xFF2B2E36 : Color.WHITE;
        int cardStrokeColor = isDark ? 0x22FFFFFF : 0xFFE2E4E9;
        int comboColor = isDark ? 0xFFF5F5F7 : MobileUi.INK;
        int labelColor = isDark ? 0xFF9EA3AE : MobileUi.SECONDARY;

        for (int i = 0; i < items.size(); i += cols) {
            LinearLayout row = ui.row();
            for (int c = 0; c < cols; c++) {
                int idx = i + c;
                LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(
                        0, ui.dp(editing ? 64 : 54), 1f);
                cellLp.setMargins(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3));
                if (idx >= items.size()) {
                    row.addView(new View(context), cellLp);
                    continue;
                }
                RemoteShortcuts.Item item = items.get(idx);
                FrameLayout cardWrap = new FrameLayout(context);
                GradientDrawable bg = ui.background(cardBgColor, 10);
                bg.setStroke(Math.max(1, ui.dp(0.75f)), editing ? 0x552E7ADB : cardStrokeColor);
                cardWrap.setBackground(bg);

                LinearLayout content = ui.column();
                content.setGravity(Gravity.CENTER);
                content.setPadding(ui.dp(5), ui.dp(4), ui.dp(5), ui.dp(editing ? 18 : 4));

                TextView comboView = new TextView(context);
                comboView.setText(item.chord(platform));
                comboView.setTextColor(comboColor);
                comboView.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
                comboView.setGravity(Gravity.CENTER);
                comboView.setSingleLine(true);
                comboView.setIncludeFontPadding(false);
                comboView.setAutoSizeTextTypeUniformWithConfiguration(8, 12, 1, TypedValue.COMPLEX_UNIT_SP);
                content.addView(comboView, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(18)));

                ui.gap(content, 2);

                TextView titleView = ui.text(item.title(), 11, false);
                titleView.setTextColor(labelColor);
                titleView.setGravity(Gravity.CENTER);
                titleView.setSingleLine(true);
                titleView.setEllipsize(TextUtils.TruncateAt.END);
                content.addView(titleView, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

                cardWrap.addView(content, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

                cardWrap.setContentDescription(item.title() + ", " + item.chord(platform));
                cardWrap.setClickable(true);
                cardWrap.setFocusable(true);

                if (editing) {
                    cardWrap.setOnClickListener(v -> {
                        if (!item.service && !item.lockScreen) {
                            openEditorDialog(item);
                        }
                    });
                    // Top-right red (-) delete badge
                    FrameLayout delBadge = new FrameLayout(context);
                    delBadge.setBackground(ui.background(MobileUi.RED, 9));
                    delBadge.setContentDescription(L10n.tr("删除") + " " + item.title());
                    delBadge.addView(ui.icon("minus", Color.WHITE),
                            new FrameLayout.LayoutParams(ui.dp(11), ui.dp(11), Gravity.CENTER));
                    delBadge.setClickable(true);
                    delBadge.setFocusable(true);
                    delBadge.setOnClickListener(v -> {
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        store.deleteItem(item.id);
                        changed.run();
                    });
                    FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(
                            ui.dp(18), ui.dp(18), Gravity.TOP | Gravity.END);
                    badgeLp.setMargins(0, ui.dp(2), ui.dp(2), 0);
                    cardWrap.addView(delBadge, badgeLp);

                    // Bottom left/right reorder controls
                    LinearLayout moveBar = ui.row();
                    moveBar.setGravity(Gravity.CENTER);
                    FrameLayout moveLeft = createMoveButton(true, idx > 0, () -> {
                        store.moveItem(item.id, -1, platform);
                        changed.run();
                    });
                    FrameLayout moveRight = createMoveButton(false, idx + 1 < items.size(), () -> {
                        store.moveItem(item.id, 1, platform);
                        changed.run();
                    });
                    moveBar.addView(moveLeft, new LinearLayout.LayoutParams(0, ui.dp(18), 1f));
                    moveBar.addView(moveRight, new LinearLayout.LayoutParams(0, ui.dp(18), 1f));
                    cardWrap.addView(moveBar, new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT, ui.dp(18), Gravity.BOTTOM));
                } else {
                    cardWrap.setOnClickListener(v -> {
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        send.accept(item);
                    });
                    cardWrap.setOnLongClickListener(v -> {
                        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        setEditing(true);
                        return true;
                    });
                }

                row.addView(cardWrap, cellLp);
            }
            grid.addView(row);
        }
        return scroll;
    }

    private FrameLayout createMoveButton(boolean left, boolean enabled, Runnable click) {
        FrameLayout box = new FrameLayout(context);
        box.setContentDescription(left ? L10n.tr("前移") : L10n.tr("后移"));
        box.setEnabled(enabled);
        box.setAlpha(enabled ? 0.9f : 0.25f);
        box.addView(ui.icon(left ? "chevron_left" : "chevron_right", dark() ? 0xFFD1D5DB : MobileUi.SECONDARY),
                new FrameLayout.LayoutParams(ui.dp(12), ui.dp(12), Gravity.CENTER));
        if (enabled) {
            box.setClickable(true);
            box.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                click.run();
            });
        }
        return box;
    }

    void openAddDialog() {
        openEditorDialog(null);
    }

    void promptResetDefaults() {
        activeDialog = new AlertDialog.Builder(context)
                .setTitle(L10n.tr("还原默认快捷键？"))
                .setMessage(L10n.tr("将恢复初始预设列表，自定义添加或排序更改将被重置。"))
                .setNegativeButton(L10n.tr("取消"), null)
                .setPositiveButton(L10n.tr("还原"), (d, w) -> {
                    store.resetDefaults();
                    editing = false;
                    changed.run();
                })
                .show();
    }

    private void openEditorDialog(RemoteShortcuts.Item existing) {
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
            modChecked[0] = true;
        }
        final int[] chosenKey = {existing != null ? existing.key : 'K'};

        EditText nameInput = new EditText(context);
        nameInput.setSingleLine(true);
        nameInput.setInputType(InputType.TYPE_CLASS_TEXT);
        nameInput.setText(existing == null ? "" : existing.title());
        nameInput.setHint(L10n.tr("快捷键名称（如：终端粘贴）"));
        nameInput.setTextSize(15);
        nameInput.setTextColor(MobileUi.INK);
        nameInput.setHintTextColor(MobileUi.SECONDARY);
        nameInput.setPadding(ui.dp(12), 0, ui.dp(12), 0);
        nameInput.setBackground(ui.background(MobileUi.BACKGROUND, 10));
        nameInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(32)});

        TextView preview = ui.text("", 14, true);
        preview.setTextColor(MobileUi.BLUE);

        TextView errorLabel = ui.text("", 12, false);
        errorLabel.setTextColor(MobileUi.RED);

        TextView keyPickerBtn = ui.action("", MobileUi.INK, null);
        keyPickerBtn.setTextSize(13);
        keyPickerBtn.setMinHeight(ui.dp(40));
        keyPickerBtn.setBackground(ui.background(0xFFF2F2F7, 10));

        Runnable updatePreview = () -> {
            List<Integer> mods = new ArrayList<>();
            for (int i = 0; i < 4; i++) if (modChecked[i]) mods.add(KeyCatalog.MODIFIERS[i]);
            int[] arr = mods.stream().mapToInt(Integer::intValue).toArray();
            String chord = KeyCatalog.chordLabel(chosenKey[0], arr, platform);
            preview.setText(chord.isEmpty() ? L10n.tr("请至少选择一个修饰键或主按键") : chord);
            keyPickerBtn.setText(chosenKey[0] == 0 ? L10n.tr("主按键：无（仅修饰键）")
                    : L10n.tr("主按键：") + KeyCatalog.fullKeyLabel(chosenKey[0], platform));
        };

        LinearLayout modRow = ui.row();
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            CheckBox cb = new CheckBox(context);
            cb.setText(KeyCatalog.modifierName(KeyCatalog.MODIFIERS[i], platform));
            cb.setTextSize(12);
            cb.setChecked(modChecked[i]);
            cb.setOnCheckedChangeListener((b, checked) -> {
                modChecked[idx] = checked;
                updatePreview.run();
            });
            modRow.addView(cb, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }

        LinearLayout keyRow = ui.row();
        keyPickerBtn.setOnClickListener(v -> showKeyCatalogPicker(chosenKey[0], entry -> {
            chosenKey[0] = entry.code;
            updatePreview.run();
        }));
        keyRow.addView(keyPickerBtn, new LinearLayout.LayoutParams(0, ui.dp(40), 1f));
        TextView modOnlyBtn = ui.action(L10n.tr("仅修饰键"), MobileUi.SECONDARY, () -> {
            chosenKey[0] = 0;
            updatePreview.run();
        });
        modOnlyBtn.setTextSize(12);
        modOnlyBtn.setMinHeight(ui.dp(40));
        modOnlyBtn.setPadding(ui.dp(10), 0, ui.dp(10), 0);
        keyRow.addView(modOnlyBtn);

        body.addView(nameInput, ui.size(-1, 46));
        ui.gap(body, 10);
        body.addView(ui.text(L10n.tr("修饰键"), 12, false));
        body.addView(modRow);
        ui.gap(body, 8);
        body.addView(keyRow);
        ui.gap(body, 8);
        body.addView(preview);
        body.addView(errorLabel);
        updatePreview.run();

        ScrollView scroll = new ScrollView(context);
        scroll.addView(body);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(L10n.tr(existing == null ? "添加快捷键" : "编辑快捷键"))
                .setView(scroll)
                .setNegativeButton(L10n.tr("取消"), null)
                .setPositiveButton(L10n.tr("保存"), null)
                .show();
        activeDialog = dialog;
        dialog.setOnDismissListener(d -> {
            if (activeDialog == dialog) activeDialog = null;
        });
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            List<Integer> mods = new ArrayList<>();
            for (int i = 0; i < 4; i++) if (modChecked[i]) mods.add(KeyCatalog.MODIFIERS[i]);
            int[] arr = mods.stream().mapToInt(Integer::intValue).toArray();
            String err = existing == null
                    ? store.addCustom(nameInput.getText().toString(), chosenKey[0], arr)
                    : store.updateItem(existing.id, nameInput.getText().toString(), chosenKey[0], arr);
            if (!err.isEmpty()) {
                errorLabel.setText(err);
                return;
            }
            dialog.dismiss();
            changed.run();
        });
    }

    private void showKeyCatalogPicker(int currentKey, Consumer<KeyCatalog.Entry> picked) {
        AlertDialog[] holder = new AlertDialog[1];
        LinearLayout root = ui.column();
        root.setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(8));

        HorizontalScrollView catScroll = new HorizontalScrollView(context);
        catScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout catRow = ui.row();
        catScroll.addView(catRow);
        root.addView(catScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(36)));
        ui.gap(root, 8);

        ScrollView keyScroll = new ScrollView(context);
        LinearLayout keyGrid = ui.column();
        keyScroll.addView(keyGrid);
        root.addView(keyScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(230)));

        String[] cats = KeyCatalog.categoryTitles();
        TextView[] catTabs = new TextView[cats.length];

        java.util.function.IntConsumer selectCat = catIndex -> {
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
                        row.addView(new View(context), cell);
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
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                if (i > 0) rlp.topMargin = ui.dp(6);
                keyGrid.addView(row, rlp);
            }
        };

        int initialCat = KeyCatalog.CATEGORY_LETTER;
        for (KeyCatalog.Entry e : KeyCatalog.entries()) {
            if (e.code == currentKey) {
                initialCat = e.category;
                break;
            }
        }
        for (int i = 0; i < cats.length; i++) {
            final int cat = i;
            TextView tab = ui.action(cats[i], MobileUi.INK, () -> selectCat.accept(cat));
            tab.setTextSize(12);
            tab.setMinHeight(0);
            tab.setPadding(ui.dp(10), 0, ui.dp(10), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, ui.dp(30));
            if (i > 0) lp.leftMargin = ui.dp(6);
            catTabs[i] = tab;
            catRow.addView(tab, lp);
        }
        selectCat.accept(initialCat);

        holder[0] = new AlertDialog.Builder(context)
                .setTitle(L10n.tr("选择按键"))
                .setView(root)
                .setNegativeButton(L10n.tr("取消"), null)
                .show();
    }
}
