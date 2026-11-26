package cn.crossdesk.mobile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Unified PC virtual-key catalog matching CrossDesk desktop's Windows/macOS/Linux
 * keyboard_converter.h (0xA0/0xA2/0xA4/0x5B left modifiers, full symbols, nav and numpad).
 */
final class KeyCatalog {
    static final int VK_BACK = 0x08;
    static final int VK_TAB = 0x09;
    static final int VK_RETURN = 0x0D;
    static final int VK_PAUSE = 0x13;
    static final int VK_CAPITAL = 0x14;
    static final int VK_ESCAPE = 0x1B;
    static final int VK_SPACE = 0x20;
    static final int VK_PRIOR = 0x21;
    static final int VK_NEXT = 0x22;
    static final int VK_END = 0x23;
    static final int VK_HOME = 0x24;
    static final int VK_LEFT = 0x25;
    static final int VK_UP = 0x26;
    static final int VK_RIGHT = 0x27;
    static final int VK_DOWN = 0x28;
    static final int VK_SNAPSHOT = 0x2C;
    static final int VK_INSERT = 0x2D;
    static final int VK_DELETE = 0x2E;

    static final int VK_LWIN = 0x5B;
    static final int VK_RWIN = 0x5C;
    static final int VK_NUMPAD0 = 0x60;
    static final int VK_MULTIPLY = 0x6A;
    static final int VK_ADD = 0x6B;
    static final int VK_SUBTRACT = 0x6D;
    static final int VK_DECIMAL = 0x6E;
    static final int VK_DIVIDE = 0x6F;
    static final int VK_F1 = 0x70;
    static final int VK_F12 = 0x7B;
    static final int VK_NUMLOCK = 0x90;
    static final int VK_SCROLL = 0x91;

    static final int VK_LSHIFT = 0xA0;
    static final int VK_RSHIFT = 0xA1;
    static final int VK_LCONTROL = 0xA2;
    static final int VK_RCONTROL = 0xA3;
    static final int VK_LMENU = 0xA4;
    static final int VK_RMENU = 0xA5;

    static final int VK_OEM_1 = 0xBA;      // ;:
    static final int VK_OEM_PLUS = 0xBB;   // =+
    static final int VK_OEM_COMMA = 0xBC;  // ,<
    static final int VK_OEM_MINUS = 0xBD;  // -_
    static final int VK_OEM_PERIOD = 0xBE; // .>
    static final int VK_OEM_2 = 0xBF;      // /?
    static final int VK_OEM_3 = 0xC0;      // `~
    static final int VK_OEM_4 = 0xDB;      // [{
    static final int VK_OEM_5 = 0xDC;      // \|
    static final int VK_OEM_6 = 0xDD;      // ]}
    static final int VK_OEM_7 = 0xDE;      // '"

    static final int[] MODIFIERS = {VK_LCONTROL, VK_LSHIFT, VK_LMENU, VK_LWIN};

    static final int CATEGORY_CONTROL = 0;
    static final int CATEGORY_LETTER = 1;
    static final int CATEGORY_SYMBOL = 2;
    static final int CATEGORY_FUNCTION = 3;
    static final int CATEGORY_NUMPAD = 4;
    static final int CATEGORY_MODIFIER = 5;

    static final class Entry {
        final int code;
        final String label;
        final String shortLabel;
        final int category;

        Entry(int code, String label, String shortLabel, int category) {
            this.code = code;
            this.label = label;
            this.shortLabel = shortLabel;
            this.category = category;
        }
    }

    private static final List<Entry> ALL_ENTRIES;
    static {
        List<Entry> list = new ArrayList<>();
        // Control & Navigation
        list.add(new Entry(VK_ESCAPE, "Esc", "Esc", CATEGORY_CONTROL));
        list.add(new Entry(VK_TAB, "Tab", "Tab", CATEGORY_CONTROL));
        list.add(new Entry(VK_CAPITAL, "Caps Lock", "Caps", CATEGORY_CONTROL));
        list.add(new Entry(VK_SPACE, "Space", "Space", CATEGORY_CONTROL));
        list.add(new Entry(VK_RETURN, "Enter", "Enter", CATEGORY_CONTROL));
        list.add(new Entry(VK_BACK, "Backspace", "Bksp", CATEGORY_CONTROL));
        list.add(new Entry(VK_INSERT, "Insert", "Ins", CATEGORY_CONTROL));
        list.add(new Entry(VK_DELETE, "Delete", "Del", CATEGORY_CONTROL));
        list.add(new Entry(VK_HOME, "Home", "Home", CATEGORY_CONTROL));
        list.add(new Entry(VK_END, "End", "End", CATEGORY_CONTROL));
        list.add(new Entry(VK_PRIOR, "PageUp", "PgUp", CATEGORY_CONTROL));
        list.add(new Entry(VK_NEXT, "PageDown", "PgDn", CATEGORY_CONTROL));
        list.add(new Entry(VK_SNAPSHOT, "Print Screen", "PrtScr", CATEGORY_CONTROL));
        list.add(new Entry(VK_SCROLL, "Scroll Lock", "ScrLk", CATEGORY_CONTROL));
        list.add(new Entry(VK_PAUSE, "Pause", "Pause", CATEGORY_CONTROL));
        list.add(new Entry(VK_UP, "↑", "↑", CATEGORY_CONTROL));
        list.add(new Entry(VK_DOWN, "↓", "↓", CATEGORY_CONTROL));
        list.add(new Entry(VK_LEFT, "←", "←", CATEGORY_CONTROL));
        list.add(new Entry(VK_RIGHT, "→", "→", CATEGORY_CONTROL));

        // Letters A-Z
        for (int c = 'A'; c <= 'Z'; c++) {
            String s = Character.toString((char) c);
            list.add(new Entry(c, s, s, CATEGORY_LETTER));
        }

        // Digits 0-9 & Symbols
        for (int c = '0'; c <= '9'; c++) {
            String s = Character.toString((char) c);
            list.add(new Entry(c, s, s, CATEGORY_SYMBOL));
        }
        list.add(new Entry(VK_OEM_3, "` ~", "`", CATEGORY_SYMBOL));
        list.add(new Entry(VK_OEM_MINUS, "- _", "-", CATEGORY_SYMBOL));
        list.add(new Entry(VK_OEM_PLUS, "= +", "=", CATEGORY_SYMBOL));
        list.add(new Entry(VK_OEM_4, "[ {", "[", CATEGORY_SYMBOL));
        list.add(new Entry(VK_OEM_6, "] }", "]", CATEGORY_SYMBOL));
        list.add(new Entry(VK_OEM_5, "\\ |", "\\", CATEGORY_SYMBOL));
        list.add(new Entry(VK_OEM_1, "; :", ";", CATEGORY_SYMBOL));
        list.add(new Entry(VK_OEM_7, "' \"", "'", CATEGORY_SYMBOL));
        list.add(new Entry(VK_OEM_COMMA, ", <", ",", CATEGORY_SYMBOL));
        list.add(new Entry(VK_OEM_PERIOD, ". >", ".", CATEGORY_SYMBOL));
        list.add(new Entry(VK_OEM_2, "/ ?", "/", CATEGORY_SYMBOL));

        // F1-F12
        for (int i = 1; i <= 12; i++) {
            String s = "F" + i;
            list.add(new Entry(VK_F1 + i - 1, s, s, CATEGORY_FUNCTION));
        }

        // Numpad
        list.add(new Entry(VK_NUMLOCK, "Num Lock", "NumLk", CATEGORY_NUMPAD));
        for (int i = 0; i <= 9; i++) {
            list.add(new Entry(VK_NUMPAD0 + i, "Num " + i, "N" + i, CATEGORY_NUMPAD));
        }
        list.add(new Entry(VK_DIVIDE, "Num /", "N/", CATEGORY_NUMPAD));
        list.add(new Entry(VK_MULTIPLY, "Num *", "N*", CATEGORY_NUMPAD));
        list.add(new Entry(VK_SUBTRACT, "Num -", "N-", CATEGORY_NUMPAD));
        list.add(new Entry(VK_ADD, "Num +", "N+", CATEGORY_NUMPAD));
        list.add(new Entry(VK_DECIMAL, "Num .", "N.", CATEGORY_NUMPAD));

        // Modifiers
        list.add(new Entry(VK_LCONTROL, "Ctrl", "Ctrl", CATEGORY_MODIFIER));
        list.add(new Entry(VK_LSHIFT, "Shift", "Shift", CATEGORY_MODIFIER));
        list.add(new Entry(VK_LMENU, "Alt", "Alt", CATEGORY_MODIFIER));
        list.add(new Entry(VK_LWIN, "Win", "Win", CATEGORY_MODIFIER));

        ALL_ENTRIES = Collections.unmodifiableList(list);
    }

    private KeyCatalog() {}

    /** Normalize generic Win32 modifier codes (0x10/0x11/0x12) to left-sided VKs expected by macOS/Linux hosts. */
    static int normalizeVk(int code) {
        return switch (code) {
            case 0x10 -> VK_LSHIFT;
            case 0x11 -> VK_LCONTROL;
            case 0x12 -> VK_LMENU;
            case 91 -> VK_LWIN;
            default -> code;
        };
    }

    static int[] normalizeModifiers(int[] modifiers) {
        if (modifiers == null || modifiers.length == 0) return new int[0];
        boolean ctrl = false, shift = false, alt = false, win = false;
        for (int raw : modifiers) {
            int m = normalizeVk(raw);
            if (m == VK_LCONTROL || m == VK_RCONTROL) ctrl = true;
            else if (m == VK_LSHIFT || m == VK_RSHIFT) shift = true;
            else if (m == VK_LMENU || m == VK_RMENU) alt = true;
            else if (m == VK_LWIN || m == VK_RWIN) win = true;
        }
        int count = (ctrl ? 1 : 0) + (shift ? 1 : 0) + (alt ? 1 : 0) + (win ? 1 : 0);
        int[] out = new int[count];
        int idx = 0;
        if (ctrl) out[idx++] = VK_LCONTROL;
        if (shift) out[idx++] = VK_LSHIFT;
        if (alt) out[idx++] = VK_LMENU;
        if (win) out[idx] = VK_LWIN;
        return out;
    }

    static boolean isModifier(int code) {
        int n = normalizeVk(code);
        return n == VK_LCONTROL || n == VK_RCONTROL
                || n == VK_LSHIFT || n == VK_RSHIFT
                || n == VK_LMENU || n == VK_RMENU
                || n == VK_LWIN || n == VK_RWIN;
    }

    static boolean isValidKey(int code) {
        int n = normalizeVk(code);
        for (Entry entry : ALL_ENTRIES) {
            if (entry.code == n) return true;
        }
        return n == VK_RSHIFT || n == VK_RCONTROL || n == VK_RMENU || n == VK_RWIN;
    }

    static List<Entry> entries() {
        return ALL_ENTRIES;
    }

    static List<Entry> byCategory(int category) {
        List<Entry> filtered = new ArrayList<>();
        for (Entry entry : ALL_ENTRIES) {
            if (entry.category == category) filtered.add(entry);
        }
        return filtered;
    }

    static String[] categoryTitles() {
        return new String[]{
                L10n.tr("控制导航"),
                L10n.tr("字母"),
                L10n.tr("数字符号"),
                L10n.tr("F功能键"),
                L10n.tr("小键盘"),
                L10n.tr("修饰键")
        };
    }

    static String modifierName(int code, String platform) {
        int n = normalizeVk(code);
        String os = platform == null ? "" : platform;
        return switch (n) {
            case VK_LCONTROL, VK_RCONTROL -> "Ctrl";
            case VK_LSHIFT, VK_RSHIFT -> "Shift";
            case VK_LMENU, VK_RMENU -> "macos".equals(os) ? "Option" : "Alt";
            case VK_LWIN, VK_RWIN -> "macos".equals(os) ? "Cmd" : "linux".equals(os) ? "Super" : "Win";
            default -> "";
        };
    }

    static String keyLabel(int code, String platform) {
        int n = normalizeVk(code);
        if (n == 0) return "";
        if (isModifier(n)) return modifierName(n, platform);
        String os = platform == null ? "" : platform;
        if (n == VK_BACK && "macos".equals(os)) return "Delete";
        for (Entry entry : ALL_ENTRIES) {
            if (entry.code == n) return entry.shortLabel;
        }
        return "0x" + Integer.toHexString(n).toUpperCase(java.util.Locale.ROOT);
    }

    static String fullKeyLabel(int code, String platform) {
        int n = normalizeVk(code);
        if (n == 0) return "";
        if (isModifier(n)) return modifierName(n, platform);
        for (Entry entry : ALL_ENTRIES) {
            if (entry.code == n) return entry.label;
        }
        return keyLabel(n, platform);
    }

    static String chordLabel(int key, int[] modifiers, String platform) {
        int[] normMods = normalizeModifiers(modifiers);
        int normKey = normalizeVk(key);
        List<String> parts = new ArrayList<>();
        for (int m : normMods) {
            String name = modifierName(m, platform);
            if (!name.isEmpty()) parts.add(name);
        }
        if (normKey != 0) {
            boolean duplicateMod = false;
            for (int m : normMods) {
                if (m == normKey) {
                    duplicateMod = true;
                    break;
                }
            }
            if (!duplicateMod) {
                String kName = keyLabel(normKey, platform);
                if (!kName.isEmpty()) parts.add(kName);
            }
        }
        return String.join("+", parts);
    }
}
