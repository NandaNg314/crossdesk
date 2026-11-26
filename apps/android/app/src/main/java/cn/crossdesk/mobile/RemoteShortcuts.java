package cn.crossdesk.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Shortcut catalog & persistence supporting editable/reorderable multi-column shortcut cards
 * (up to 64 shortcuts) and the IME quick-action strip.
 */
final class RemoteShortcuts {
    static final int MAX_SHORTCUTS = 64;
    static final String[] PLATFORMS = {"windows", "macos", "linux"};
    static final int[] MODIFIERS = KeyCatalog.MODIFIERS;

    static final class Item {
        final String id;
        String name;
        int key;
        int[] modifiers;
        final boolean service;
        final boolean lockScreen;
        final boolean macAdaptPrimary;
        final boolean custom;

        Item(String id, String name, int key, int[] modifiers,
             boolean service, boolean lockScreen, boolean macAdaptPrimary, boolean custom) {
            this.id = (id == null || id.isEmpty()) ? "sc-" + UUID.randomUUID() : id;
            this.name = name == null ? "" : name.trim();
            this.key = KeyCatalog.normalizeVk(key);
            this.modifiers = KeyCatalog.normalizeModifiers(modifiers);
            this.service = service;
            this.lockScreen = lockScreen;
            this.macAdaptPrimary = macAdaptPrimary;
            this.custom = custom;
        }

        String title() {
            return custom ? name : L10n.tr(name);
        }

        int resolvedKey(String platform) {
            boolean mac = "macos".equals(platform);
            if ("preset-desktop".equals(id) && mac) return KeyCatalog.VK_F1 + 2; // F3
            if ("preset-switch-win".equals(id)) return mac ? KeyCatalog.VK_UP : KeyCatalog.VK_TAB;
            if ("preset-close-win".equals(id) && mac) return 'Q';
            return key;
        }

        int[] resolvedModifiers(String platform) {
            boolean mac = "macos".equals(platform);
            boolean linux = "linux".equals(platform);
            if (service) return new int[]{KeyCatalog.VK_LCONTROL, KeyCatalog.VK_LMENU};
            if (lockScreen) {
                return mac ? new int[]{KeyCatalog.VK_LCONTROL, KeyCatalog.VK_LWIN}
                        : new int[]{KeyCatalog.VK_LWIN};
            }
            if ("preset-switch-win".equals(id)) {
                if (mac) return new int[]{KeyCatalog.VK_LCONTROL};
                if (linux) return new int[]{KeyCatalog.VK_LMENU};
                return new int[]{KeyCatalog.VK_LWIN};
            }
            if ("preset-taskmgr".equals(id) && mac) {
                return new int[]{KeyCatalog.VK_LMENU, KeyCatalog.VK_LWIN};
            }
            if ("preset-close-win".equals(id) && mac) {
                return new int[]{KeyCatalog.VK_LWIN};
            }
            if (macAdaptPrimary && mac) {
                int[] adapted = modifiers.clone();
                for (int i = 0; i < adapted.length; i++) {
                    if (adapted[i] == KeyCatalog.VK_LCONTROL) adapted[i] = KeyCatalog.VK_LWIN;
                }
                return KeyCatalog.normalizeModifiers(adapted);
            }
            return modifiers;
        }

        String chord(String platform) {
            if (service) return "Ctrl+Alt+Del";
            if (lockScreen) {
                return "macos".equals(platform) ? "Ctrl+Cmd+Q"
                        : KeyCatalog.modifierName(KeyCatalog.VK_LWIN, platform) + "+L";
            }
            int rk = resolvedKey(platform);
            int[] rmods = resolvedModifiers(platform);
            if (rk == KeyCatalog.VK_CAPITAL && rmods.length == 0) return "Caps Lock";
            return KeyCatalog.chordLabel(rk, rmods, platform);
        }

        JSONObject toJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("id", id);
                obj.put("name", name);
                obj.put("key", key);
                JSONArray arr = new JSONArray();
                for (int m : modifiers) arr.put(m);
                obj.put("modifiers", arr);
                obj.put("service", service);
                obj.put("lockScreen", lockScreen);
                obj.put("macAdaptPrimary", macAdaptPrimary);
                obj.put("custom", custom);
            } catch (Exception ignored) { }
            return obj;
        }

        static Item fromJson(JSONObject obj) {
            if (obj == null) return null;
            String id = obj.optString("id", "");
            String name = obj.optString("name", "").trim();
            if (name.isEmpty() || name.length() > 64) return null;
            int key = obj.optInt("key", 0);
            JSONArray arr = obj.optJSONArray("modifiers");
            int[] mods = new int[arr == null ? 0 : Math.min(4, arr.length())];
            for (int i = 0; i < mods.length; i++) mods[i] = arr.optInt(i, 0);
            boolean service = obj.optBoolean("service", false);
            boolean lockScreen = obj.optBoolean("lockScreen", false);
            boolean macAdapt = obj.optBoolean("macAdaptPrimary", false);
            boolean custom = obj.optBoolean("custom", true);
            if (!service && !lockScreen && key == 0 && KeyCatalog.normalizeModifiers(mods).length == 0) {
                return null;
            }
            return new Item(id, name, key, mods, service, lockScreen, macAdapt, custom);
        }
    }

    private final SharedPreferences preferences;
    private final List<Item> cached = new ArrayList<>();

    RemoteShortcuts(Context context) {
        preferences = context.getSharedPreferences("remote_shortcuts_v2", Context.MODE_PRIVATE);
        load();
    }

    static String platform(String value) {
        return Arrays.asList(PLATFORMS).contains(value) ? value : "windows";
    }

    static String platformName(String value) {
        return switch (value == null ? "" : value) {
            case "windows" -> "Windows";
            case "macos" -> "macOS";
            case "linux" -> "Linux";
            default -> L10n.tr("未知");
        };
    }

    static String modifierName(int code, String platform) {
        return KeyCatalog.modifierName(code, platform);
    }

    static String keyName(int code, String platform) {
        return KeyCatalog.keyLabel(code, platform);
    }

    static int[] normalize(int[] modifiers) {
        return KeyCatalog.normalizeModifiers(modifiers);
    }

    List<Item> list(String hostPlatform) {
        boolean win = !"macos".equals(hostPlatform) && !"linux".equals(hostPlatform);
        List<Item> visible = new ArrayList<>(cached.size());
        for (Item item : cached) {
            if (item.service && !win) continue;
            visible.add(item);
        }
        return visible;
    }

    List<Item> pinnedQuickBar(String hostPlatform) {
        List<Item> quick = new ArrayList<>();
        quick.add(new Item("q-esc", "Esc", KeyCatalog.VK_ESCAPE, null, false, false, false, false));
        quick.add(new Item("q-tab", "Tab", KeyCatalog.VK_TAB, null, false, false, false, false));
        quick.add(new Item("q-copy", "复制", 'C', new int[]{KeyCatalog.VK_LCONTROL}, false, false, true, false));
        quick.add(new Item("q-paste", "粘贴", 'V', new int[]{KeyCatalog.VK_LCONTROL}, false, false, true, false));
        quick.add(new Item("q-shift-ins", "Shift+Ins", KeyCatalog.VK_INSERT, new int[]{KeyCatalog.VK_LSHIFT}, false, false, false, true));
        quick.add(new Item("q-all", "全选", 'A', new int[]{KeyCatalog.VK_LCONTROL}, false, false, true, false));
        quick.add(new Item("q-undo", "撤销", 'Z', new int[]{KeyCatalog.VK_LCONTROL}, false, false, true, false));
        quick.add(new Item("q-enter", "Enter", KeyCatalog.VK_RETURN, null, false, false, false, false));
        quick.add(new Item("q-left", "←", KeyCatalog.VK_LEFT, null, false, false, false, true));
        quick.add(new Item("q-up", "↑", KeyCatalog.VK_UP, null, false, false, false, true));
        quick.add(new Item("q-down", "↓", KeyCatalog.VK_DOWN, null, false, false, false, true));
        quick.add(new Item("q-right", "→", KeyCatalog.VK_RIGHT, null, false, false, false, true));
        quick.add(new Item("q-del", "Del", KeyCatalog.VK_DELETE, null, false, false, false, true));
        return quick;
    }

    String addCustom(String label, int key, int[] modifiers) {
        String clean = label == null ? "" : label.trim();
        int normKey = KeyCatalog.normalizeVk(key);
        int[] normMods = KeyCatalog.normalizeModifiers(modifiers);
        if (normKey == 0 && normMods.length == 0) return L10n.tr("请至少选择一个修饰键或主按键");
        if (normKey != 0 && !KeyCatalog.isValidKey(normKey)) return L10n.tr("请选择有效的按键");
        if (clean.isEmpty()) clean = KeyCatalog.chordLabel(normKey, normMods, "windows");
        if (clean.length() > 32) clean = clean.substring(0, 32);
        if (cached.size() >= MAX_SHORTCUTS) return L10n.tr("最多保存 64 个快捷键");
        cached.add(new Item("custom-" + UUID.randomUUID(), clean, normKey, normMods, false, false, false, true));
        persist();
        return "";
    }

    String updateItem(String id, String label, int key, int[] modifiers) {
        String clean = label == null ? "" : label.trim();
        int normKey = KeyCatalog.normalizeVk(key);
        int[] normMods = KeyCatalog.normalizeModifiers(modifiers);
        if (normKey == 0 && normMods.length == 0) return L10n.tr("请至少选择一个修饰键或主按键");
        if (clean.isEmpty()) clean = KeyCatalog.chordLabel(normKey, normMods, "windows");
        if (clean.length() > 32) clean = clean.substring(0, 32);
        for (int i = 0; i < cached.size(); i++) {
            Item item = cached.get(i);
            if (item.id.equals(id)) {
                cached.set(i, new Item(item.id, clean, normKey, normMods, false, false, false, true));
                persist();
                return "";
            }
        }
        return "";
    }

    void deleteItem(String id) {
        if (cached.removeIf(item -> item.id.equals(id))) {
            persist();
        }
    }

    void moveItem(String id, int delta, String hostPlatform) {
        List<Item> visible = list(hostPlatform);
        int vIdx = -1;
        for (int i = 0; i < visible.size(); i++) {
            if (visible.get(i).id.equals(id)) {
                vIdx = i;
                break;
            }
        }
        int targetVIdx = vIdx + delta;
        if (vIdx < 0 || targetVIdx < 0 || targetVIdx >= visible.size()) return;
        String otherId = visible.get(targetVIdx).id;
        int idxA = indexOfId(id);
        int idxB = indexOfId(otherId);
        if (idxA >= 0 && idxB >= 0) {
            Collections.swap(cached, idxA, idxB);
            persist();
        }
    }

    void resetDefaults() {
        cached.clear();
        cached.addAll(defaultPresets());
        persist();
    }

    private int indexOfId(String id) {
        for (int i = 0; i < cached.size(); i++) {
            if (cached.get(i).id.equals(id)) return i;
        }
        return -1;
    }

    private void load() {
        cached.clear();
        String raw = preferences.getString("items", "");
        if (!raw.isEmpty()) {
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length() && cached.size() < MAX_SHORTCUTS; i++) {
                    Item item = Item.fromJson(arr.optJSONObject(i));
                    if (item != null) cached.add(item);
                }
            } catch (Exception ignored) {
                cached.clear();
            }
        }
        if (cached.isEmpty() && !preferences.getBoolean("initialized", false)) {
            cached.addAll(defaultPresets());
            persist();
        }
    }

    private void persist() {
        JSONArray arr = new JSONArray();
        for (Item item : cached) arr.put(item.toJson());
        preferences.edit()
                .putBoolean("initialized", true)
                .putString("items", arr.toString())
                .apply();
    }

    private static List<Item> defaultPresets() {
        List<Item> list = new ArrayList<>();
        list.add(new Item("preset-caps", "大小写", KeyCatalog.VK_CAPITAL, null, false, false, false, false));
        list.add(new Item("preset-copy", "复制", 'C', new int[]{KeyCatalog.VK_LCONTROL}, false, false, true, false));
        list.add(new Item("preset-paste", "粘贴", 'V', new int[]{KeyCatalog.VK_LCONTROL}, false, false, true, false));
        list.add(new Item("preset-cut", "剪切", 'X', new int[]{KeyCatalog.VK_LCONTROL}, false, false, true, false));
        list.add(new Item("preset-all", "全选", 'A', new int[]{KeyCatalog.VK_LCONTROL}, false, false, true, false));
        list.add(new Item("preset-undo", "撤销", 'Z', new int[]{KeyCatalog.VK_LCONTROL}, false, false, true, false));
        list.add(new Item("preset-save", "保存", 'S', new int[]{KeyCatalog.VK_LCONTROL}, false, false, true, false));
        list.add(new Item("preset-term-paste", "终端粘贴", KeyCatalog.VK_INSERT, new int[]{KeyCatalog.VK_LSHIFT}, false, false, false, false));
        list.add(new Item("preset-start", "开始", 0, new int[]{KeyCatalog.VK_LWIN}, false, false, false, false));
        list.add(new Item("preset-desktop", "显示桌面", 'D', new int[]{KeyCatalog.VK_LWIN}, false, false, false, false));
        list.add(new Item("preset-switch-win", "切换窗口", KeyCatalog.VK_TAB, new int[]{KeyCatalog.VK_LWIN}, false, false, false, false));
        list.add(new Item("preset-lock", "锁定屏幕", 'L', new int[]{KeyCatalog.VK_LWIN}, false, true, false, false));
        list.add(new Item("preset-taskmgr", "任务管理", KeyCatalog.VK_ESCAPE, new int[]{KeyCatalog.VK_LCONTROL, KeyCatalog.VK_LSHIFT}, false, false, false, false));
        list.add(new Item("preset-close-win", "关闭窗口", KeyCatalog.VK_F1 + 3, new int[]{KeyCatalog.VK_LMENU}, false, false, false, false));
        list.add(new Item("preset-sas", "安全选项", KeyCatalog.VK_DELETE, new int[]{KeyCatalog.VK_LCONTROL, KeyCatalog.VK_LMENU}, true, false, false, false));
        return list;
    }
}
