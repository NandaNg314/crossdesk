package cn.crossdesk.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Multi-profile persistence for customizable floating keymap widgets.
 * Stored as JSON in SharedPreferences("floating_keymap_v1", MODE_PRIVATE).
 */
final class FloatingKeymapStore {
    static final int MIN_SIZE_DP = 36;
    static final int MAX_SIZE_DP = 88;
    static final int DEFAULT_SIZE_DP = 48;
    static final float MIN_OPACITY = 0.20f;
    static final float MAX_OPACITY = 1.00f;
    static final float DEFAULT_OPACITY = 0.78f;
    static final int MAX_PROFILES = 16;
    static final int MAX_WIDGETS_PER_PROFILE = 48;

    enum WidgetType {
        SINGLE_KEY,
        COMBO_KEY,
        MOUSE_LEFT,
        MOUSE_RIGHT,
        MOUSE_MIDDLE,
        SCROLL_WHEEL,
        ACTION_CLIPBOARD,
        ACTION_DRAWER,
        ACTION_KEYBOARD;

        static WidgetType fromName(String value) {
            if (value == null) return SINGLE_KEY;
            try {
                return WidgetType.valueOf(value);
            } catch (IllegalArgumentException ignored) {
                return SINGLE_KEY;
            }
        }
    }

    static final class Widget {
        String id;
        WidgetType type;
        String label;
        int key;
        int[] modifiers;
        float normX;
        float normY;
        int sizeDp;
        float opacity;

        Widget(String id, WidgetType type, String label, int key, int[] modifiers,
               float normX, float normY, int sizeDp, float opacity) {
            this.id = (id == null || id.isEmpty()) ? UUID.randomUUID().toString() : id;
            this.type = type == null ? WidgetType.SINGLE_KEY : type;
            this.label = label == null ? "" : label.trim();
            this.key = KeyCatalog.normalizeVk(key);
            this.modifiers = KeyCatalog.normalizeModifiers(modifiers);
            this.normX = clamp(normX, 0.04f, 0.96f);
            this.normY = clamp(normY, 0.06f, 0.94f);
            this.sizeDp = Math.max(MIN_SIZE_DP, Math.min(MAX_SIZE_DP, sizeDp));
            this.opacity = clamp(opacity, MIN_OPACITY, MAX_OPACITY);
        }

        Widget copy(boolean newId) {
            return new Widget(newId ? UUID.randomUUID().toString() : id,
                    type, label, key, modifiers.clone(), normX, normY, sizeDp, opacity);
        }

        String displayLabel(String platform) {
            if (!label.isEmpty()) return label;
            return switch (type) {
                case SINGLE_KEY -> KeyCatalog.keyLabel(key, platform);
                case COMBO_KEY -> KeyCatalog.chordLabel(key, modifiers, platform);
                case MOUSE_LEFT -> L10n.tr("左键");
                case MOUSE_RIGHT -> L10n.tr("右键");
                case MOUSE_MIDDLE -> L10n.tr("中键");
                case SCROLL_WHEEL -> L10n.tr("滚轮");
                case ACTION_CLIPBOARD -> L10n.tr("粘贴板");
                case ACTION_DRAWER -> L10n.tr("操作");
                case ACTION_KEYBOARD -> L10n.tr("键盘");
            };
        }

        JSONObject toJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("id", id);
                obj.put("type", type.name());
                obj.put("label", label);
                obj.put("key", key);
                JSONArray mods = new JSONArray();
                for (int m : modifiers) mods.put(m);
                obj.put("modifiers", mods);
                obj.put("normX", normX);
                obj.put("normY", normY);
                obj.put("sizeDp", sizeDp);
                obj.put("opacity", opacity);
            } catch (Exception ignored) { }
            return obj;
        }

        static Widget fromJson(JSONObject obj, float fallbackOpacity) {
            if (obj == null) return null;
            WidgetType type = WidgetType.fromName(obj.optString("type", "SINGLE_KEY"));
            String id = obj.optString("id", "");
            String label = obj.optString("label", "");
            if (label.length() > 24) label = label.substring(0, 24);
            int key = obj.optInt("key", 0);
            JSONArray modsJson = obj.optJSONArray("modifiers");
            int[] mods = new int[modsJson == null ? 0 : Math.min(4, modsJson.length())];
            for (int i = 0; i < mods.length; i++) mods[i] = modsJson.optInt(i, 0);
            float x = (float) obj.optDouble("normX", 0.5);
            float y = (float) obj.optDouble("normY", 0.5);
            int size = obj.optInt("sizeDp", DEFAULT_SIZE_DP);
            float opacity = (float) obj.optDouble("opacity", fallbackOpacity);
            if (!Float.isFinite(x)) x = 0.5f;
            if (!Float.isFinite(y)) y = 0.5f;
            if (!Float.isFinite(opacity)) opacity = fallbackOpacity;
            return new Widget(id, type, label, key, mods, x, y, size, opacity);
        }
    }

    static final class Profile {
        final String id;
        String name;
        final List<Widget> widgets = new ArrayList<>();

        Profile(String id, String name) {
            this.id = (id == null || id.isEmpty()) ? UUID.randomUUID().toString() : id;
            this.name = (name == null || name.trim().isEmpty()) ? L10n.tr("默认效率方案") : name.trim();
        }

        List<Widget> copyWidgets(boolean freshIds) {
            List<Widget> out = new ArrayList<>(widgets.size());
            for (Widget w : widgets) out.add(w.copy(freshIds));
            return out;
        }

        JSONObject toJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("id", id);
                obj.put("name", name);
                JSONArray arr = new JSONArray();
                for (Widget w : widgets) arr.put(w.toJson());
                obj.put("widgets", arr);
            } catch (Exception ignored) { }
            return obj;
        }

        static Profile fromJson(JSONObject obj, float fallbackOpacity) {
            if (obj == null) return null;
            String name = obj.optString("name", "").trim();
            if (name.length() > 32) name = name.substring(0, 32);
            Profile profile = new Profile(obj.optString("id", ""), name);
            JSONArray arr = obj.optJSONArray("widgets");
            if (arr != null) {
                for (int i = 0; i < arr.length() && profile.widgets.size() < MAX_WIDGETS_PER_PROFILE; i++) {
                    Widget w = Widget.fromJson(arr.optJSONObject(i), fallbackOpacity);
                    if (w != null) profile.widgets.add(w);
                }
            }
            return profile;
        }
    }

    private final SharedPreferences prefs;
    private boolean enabled;
    private float globalOpacity = DEFAULT_OPACITY;
    private float globalScale = 1.0f;
    private String activeProfileId = "";
    private final List<Profile> profiles = new ArrayList<>();

    FloatingKeymapStore(Context context) {
        prefs = context.getSharedPreferences("floating_keymap_v1", Context.MODE_PRIVATE);
        load();
    }

    boolean isEnabled() { return enabled; }

    void setEnabled(boolean value) {
        enabled = value;
        save();
    }

    float globalOpacity() { return globalOpacity; }
    float globalScale() { return globalScale; }

    List<Profile> profiles() { return profiles; }

    Profile activeProfile() {
        for (Profile p : profiles) {
            if (p.id.equals(activeProfileId)) return p;
        }
        if (profiles.isEmpty()) {
            Profile def = buildDefaultProfile();
            profiles.add(def);
            activeProfileId = def.id;
            save();
            return def;
        }
        activeProfileId = profiles.get(0).id;
        return profiles.get(0);
    }

    void selectProfile(String profileId) {
        for (Profile p : profiles) {
            if (p.id.equals(profileId)) {
                activeProfileId = p.id;
                save();
                return;
            }
        }
    }

    String suggestNewProfileName() {
        int index = profiles.size() + 1;
        while (true) {
            String candidate = L10n.format("新建方案{0}", index);
            boolean collision = false;
            for (Profile p : profiles) {
                if (p.name.equals(candidate)) {
                    collision = true;
                    break;
                }
            }
            if (!collision) return candidate;
            index++;
        }
    }

    Profile createProfile(String name) {
        if (profiles.size() >= MAX_PROFILES) return activeProfile();
        String clean = (name == null || name.trim().isEmpty()) ? suggestNewProfileName() : name.trim();
        if (clean.length() > 32) clean = clean.substring(0, 32);
        Profile created = new Profile(UUID.randomUUID().toString(), clean);
        profiles.add(created);
        activeProfileId = created.id;
        save();
        return created;
    }

    Profile duplicateProfile(String sourceId) {
        if (profiles.size() >= MAX_PROFILES) return activeProfile();
        Profile src = findProfile(sourceId);
        if (src == null) src = activeProfile();
        String baseName = src.name + " " + L10n.tr("副本");
        if (baseName.length() > 32) baseName = baseName.substring(0, 32);
        Profile copy = new Profile(UUID.randomUUID().toString(), baseName);
        copy.widgets.addAll(src.copyWidgets(true));
        profiles.add(copy);
        activeProfileId = copy.id;
        save();
        return copy;
    }

    void renameProfile(String profileId, String newName) {
        if (newName == null || newName.trim().isEmpty()) return;
        Profile p = findProfile(profileId);
        if (p == null) return;
        String clean = newName.trim();
        if (clean.length() > 32) clean = clean.substring(0, 32);
        p.name = clean;
        save();
    }

    void deleteProfile(String profileId) {
        profiles.removeIf(p -> p.id.equals(profileId));
        if (profiles.isEmpty()) {
            Profile def = buildDefaultProfile();
            profiles.add(def);
            activeProfileId = def.id;
        } else if (findProfile(activeProfileId) == null) {
            activeProfileId = profiles.get(0).id;
        }
        save();
    }

    void saveProfileWidgets(String profileId, String newName, List<Widget> updated) {
        Profile p = findProfile(profileId);
        if (p == null) p = activeProfile();
        if (newName != null && !newName.trim().isEmpty()) {
            String clean = newName.trim();
            p.name = clean.length() > 32 ? clean.substring(0, 32) : clean;
        }
        p.widgets.clear();
        for (int i = 0; i < updated.size() && p.widgets.size() < MAX_WIDGETS_PER_PROFILE; i++) {
            p.widgets.add(updated.get(i).copy(false));
        }
        save();
    }

    private Profile findProfile(String id) {
        for (Profile p : profiles) {
            if (p.id.equals(id)) return p;
        }
        return null;
    }

    private void load() {
        profiles.clear();
        String raw = prefs.getString("state", "");
        if (!raw.isEmpty()) {
            try {
                JSONObject root = new JSONObject(raw);
                enabled = root.optBoolean("enabled", false);
                globalOpacity = clamp((float) root.optDouble("globalOpacity", DEFAULT_OPACITY), MIN_OPACITY, MAX_OPACITY);
                globalScale = clamp((float) root.optDouble("globalScale", 1.0), 0.75f, 1.50f);
                activeProfileId = root.optString("activeProfileId", "");
                JSONArray arr = root.optJSONArray("profiles");
                if (arr != null) {
                    for (int i = 0; i < arr.length() && profiles.size() < MAX_PROFILES; i++) {
                        Profile p = Profile.fromJson(arr.optJSONObject(i), globalOpacity);
                        if (p != null) profiles.add(p);
                    }
                }
            } catch (Exception ignored) {
                profiles.clear();
            }
        }
        if (profiles.isEmpty()) {
            Profile def = buildDefaultProfile();
            profiles.add(def);
            activeProfileId = def.id;
            save();
        } else if (findProfile(activeProfileId) == null) {
            activeProfileId = profiles.get(0).id;
        }
    }

    void save() {
        JSONObject root = new JSONObject();
        try {
            root.put("enabled", enabled);
            root.put("globalOpacity", globalOpacity);
            root.put("globalScale", globalScale);
            root.put("activeProfileId", activeProfileId);
            JSONArray arr = new JSONArray();
            for (Profile p : profiles) arr.put(p.toJson());
            root.put("profiles", arr);
            prefs.edit().putString("state", root.toString()).apply();
        } catch (Exception ignored) { }
    }

    private static Profile buildDefaultProfile() {
        Profile p = new Profile("default-efficiency", L10n.tr("默认效率方案"));
        // Left edge non-overlapping efficiency buttons
        p.widgets.add(new Widget("w-clipboard", WidgetType.ACTION_CLIPBOARD, L10n.tr("粘贴板"),
                0, null, 0.07f, 0.22f, 48, DEFAULT_OPACITY));
        p.widgets.add(new Widget("w-gitbash-paste", WidgetType.COMBO_KEY, L10n.tr("GitBash粘贴"),
                KeyCatalog.VK_INSERT, new int[]{KeyCatalog.VK_LSHIFT}, 0.07f, 0.38f, 52, DEFAULT_OPACITY));
        p.widgets.add(new Widget("w-copy", WidgetType.COMBO_KEY, L10n.tr("复制"),
                'C', new int[]{KeyCatalog.VK_LCONTROL}, 0.07f, 0.54f, 46, DEFAULT_OPACITY));
        p.widgets.add(new Widget("w-paste", WidgetType.COMBO_KEY, L10n.tr("粘贴"),
                'V', new int[]{KeyCatalog.VK_LCONTROL}, 0.07f, 0.70f, 46, DEFAULT_OPACITY));
        // Lower-right edge buttons (below the upper-right Edge Quick Dock)
        p.widgets.add(new Widget("w-win-r", WidgetType.COMBO_KEY, "Win+R",
                'R', new int[]{KeyCatalog.VK_LWIN}, 0.92f, 0.56f, 48, DEFAULT_OPACITY));
        p.widgets.add(new Widget("w-prtscr", WidgetType.SINGLE_KEY, "PrtScr",
                KeyCatalog.VK_SNAPSHOT, null, 0.92f, 0.72f, 48, DEFAULT_OPACITY));
        p.widgets.add(new Widget("w-wheel", WidgetType.SCROLL_WHEEL, L10n.tr("滚轮条"),
                0, null, 0.83f, 0.64f, 46, DEFAULT_OPACITY));
        return p;
    }

    private static float clamp(float value, float min, float max) {
        if (!Float.isFinite(value)) return min;
        return Math.max(min, Math.min(max, value));
    }
}
