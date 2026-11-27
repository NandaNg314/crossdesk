package cn.crossdesk.mobile;

import android.content.Context;
import android.graphics.Color;
import android.icu.text.BreakIterator;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import java.util.ArrayList;
import java.util.List;

/**
 * Live visible IME input bar that keeps Chinese Pinyin/9-key/voice candidates, paired punctuation,
 * and auto-corrections local until committed, then streams UTF-8 text deltas to the remote host.
 */
@android.annotation.SuppressLint("ViewConstructor")
final class RemoteKeyboardInput extends EditText {
    interface Sender {
        boolean edit(int deletions, String text);
        void key(int code);
    }

    private static final long STUCK_BATCH_TIMEOUT_MS = 65L;

    private final Sender sender;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private String sent = "";
    private String committed = "";
    private boolean accepting, resetting, enterPending;
    private int batches, generation;
    private final Runnable synchronize = this::flush;
    private final Runnable batchWatchdog = () -> {
        if (batches > 0) {
            // Recover from OEM Chinese IMEs that omit matching endBatchEdit() calls.
            batches = 0;
            flush();
        }
    };

    private void flush() {
        if (!accepting || batches > 0 || !finishEdit()) return;
        if (enterPending || sent.length() > 128 || sent.indexOf('\n') >= 0) resetContext();
    }

    RemoteKeyboardInput(Context context, Sender sender) {
        super(context);
        this.sender = sender;
        setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_FULLSCREEN
                | EditorInfo.IME_ACTION_SEND);
        setSingleLine(true);
        setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        setTextSize(13.5f);
        setCursorVisible(true);
        setHint(L10n.tr("点击此处唤起输入法，支持中文词组、标点与语音直接上屏…"));
        setImportantForAutofill(IMPORTANT_FOR_AUTOFILL_NO);
        addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { }
            public void afterTextChanged(Editable value) { schedule(); }
        });
    }

    void applyTheme(boolean dark, MobileUi ui) {
        setTextColor(dark ? 0xFFF5F5F7 : 0xFF1D1D1F);
        setHintTextColor(dark ? 0xFF8E939E : 0xFF7A7F89);
        android.graphics.drawable.GradientDrawable bg = ui.background(dark ? 0xFF14161B : 0xFFFFFFFF, 8);
        bg.setStroke(Math.max(1, ui.dp(1f)), dark ? 0x33FFFFFF : 0x1F000000);
        setBackground(bg);
        setPadding(ui.dp(10), 0, ui.dp(10), 0);
    }

    void start() {
        boolean wasAccepting = accepting;
        accepting = true;
        setFocusable(true);
        setFocusableInTouchMode(true);
        boolean focused = hasFocus() || requestFocus();
        if (focused && !wasAccepting) {
            InputMethodManager imm = getContext().getSystemService(InputMethodManager.class);
            if (imm != null) imm.restartInput(this);
        }
    }

    void focusAndShowIme() {
        boolean wasAccepting = accepting;
        accepting = true;
        batches = 0;
        main.removeCallbacks(batchWatchdog);
        setFocusable(true);
        setFocusableInTouchMode(true);
        requestFocus();
        InputMethodManager imm = getContext().getSystemService(InputMethodManager.class);
        if (imm != null) {
            if (!wasAccepting) imm.restartInput(this);
            imm.showSoftInput(this, 0);
        }
    }

    // Keep focus while replacing the IME, but reject its late edits without restarting it.
    void pause() {
        main.removeCallbacks(batchWatchdog);
        batches = 0;
        if (accepting) {
            accepting = false;
            finishEdit();
        }
        resetContext();
    }

    void stop() {
        pause();
        clearFocus();
    }

    boolean isAcceptingInput() { return accepting; }

    boolean hasPendingComposition() { return composing(); }

    boolean prepareShortcut() {
        if (!accepting) return true;
        batches = 0;
        main.removeCallbacks(batchWatchdog);
        if (composing()) {
            BaseInputConnection.removeComposingSpans(getText());
        }
        if (!finishEdit()) return false;
        resetContext();
        return true;
    }

    private boolean finishEdit() {
        if (!synchronize()) return false;
        if (enterPending) sender.key(13);
        return true;
    }

    void finishInputContext() {
        batches = 0;
        main.removeCallbacks(batchWatchdog);
        if (accepting) {
            finishEdit();
            if (!hasFocus()) requestFocus();
        }
        resetContext();
    }

    void clearPendingInput() {
        batches = 0;
        main.removeCallbacks(batchWatchdog);
        resetContext();
    }

    void triggerEnter() {
        if (!accepting) start();
        batches = 0;
        main.removeCallbacks(batchWatchdog);
        if (composing()) {
            // Commit any active composition first, then emit Return.
            BaseInputConnection.removeComposingSpans(getText());
            finishEdit();
            resetContext();
        } else {
            finishEdit();
            resetContext();
            sender.key(13);
        }
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(MotionEvent event) {
        boolean handled = super.onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            focusAndShowIme();
        }
        return handled;
    }

    private void resetContext() {
        generation++;
        batches = 0;
        main.removeCallbacks(synchronize);
        main.removeCallbacks(batchWatchdog);
        resetting = true;
        enterPending = false;
        sent = committed = "";
        BaseInputConnection.removeComposingSpans(getText());
        getText().clear();
        setSelection(0);
        resetting = false;
        if (accepting && hasFocus()) {
            InputMethodManager imm = getContext().getSystemService(InputMethodManager.class);
            if (imm != null) imm.restartInput(this);
        }
    }

    private void schedule() {
        if (!resetting && accepting) {
            main.removeCallbacks(synchronize);
            main.post(synchronize);
        }
    }

    private boolean composing() { return BaseInputConnection.getComposingSpanStart(getText()) >= 0; }

    private boolean afterImeEdit(boolean applied) {
        if (applied) {
            if (!composing()) committed = getText().toString();
            schedule();
        }
        return applied;
    }

    private static List<String> characters(String text) {
        BreakIterator iterator = BreakIterator.getCharacterInstance();
        iterator.setText(text);
        List<String> result = new ArrayList<>();
        int start = iterator.first();
        for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            result.add(text.substring(start, end));
        }
        return result;
    }

    private boolean synchronize() {
        if (resetting) return false;
        Editable value = getText();
        int markedStart = BaseInputConnection.getComposingSpanStart(value);
        boolean complete = batches == 0 && markedStart < 0;
        if (complete) {
            committed = value.toString();
        } else if (batches == 0 && markedStart > committed.length()) {
            String prefix = value.subSequence(0, markedStart).toString();
            if (prefix.startsWith(committed)) committed = prefix;
        }
        String current = committed;
        if (current.equals(sent)) return complete;
        List<String> old = characters(sent), next = characters(current);
        int prefix = 0, offset = 0;
        while (prefix < old.size() && prefix < next.size() && old.get(prefix).equals(next.get(prefix))) {
            offset += next.get(prefix).length();
            prefix++;
        }
        if (!sender.edit(old.size() - prefix, current.substring(offset))) {
            resetContext();
            return false;
        }
        sent = current;
        return complete;
    }

    private void enter() {
        if (!accepting) return;
        batches = 0;
        main.removeCallbacks(batchWatchdog);
        BaseInputConnection.removeComposingSpans(getText());
        enterPending = true;
        schedule();
    }

    @Override public boolean onKeyPreIme(int keyCode, KeyEvent event) {
        return super.onKeyPreIme(keyCode, event);
    }

    @Override public boolean onKeyDown(int code, KeyEvent event) {
        if (accepting && code == KeyEvent.KEYCODE_ENTER || accepting && code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            enter();
            return true;
        }
        if (accepting && code == KeyEvent.KEYCODE_DEL && length() == 0) {
            sender.key(8);
            return true;
        }
        if (accepting && event != null && !composing() && length() == 0) {
            int uchar = event.getUnicodeChar();
            if (uchar >= 0x80) {
                sender.edit(0, new String(Character.toChars(uchar)));
                return true;
            }
        }
        return super.onKeyDown(code, event);
    }

    @Override public boolean onKeyMultiple(int keyCode, int repeatCount, KeyEvent event) {
        if (accepting && event != null && event.getCharacters() != null && !event.getCharacters().isEmpty()) {
            sender.edit(0, event.getCharacters());
            return true;
        }
        return super.onKeyMultiple(keyCode, repeatCount, event);
    }

    @Override public boolean onKeyUp(int code, KeyEvent event) {
        if (accepting && (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_DEL)) {
            return true;
        }
        return super.onKeyUp(code, event);
    }

    @Override public InputConnection onCreateInputConnection(EditorInfo info) {
        InputConnection base = super.onCreateInputConnection(info);
        if (base == null) return null;
        int revision = ++generation;
        batches = 0;
        main.removeCallbacks(batchWatchdog);
        return new InputConnectionWrapper(base, false) {
            private boolean active() { return accepting && generation == revision; }

            @Override public boolean beginBatchEdit() {
                if (!active()) return false;
                batches++;
                main.removeCallbacks(batchWatchdog);
                main.postDelayed(batchWatchdog, STUCK_BATCH_TIMEOUT_MS);
                return super.beginBatchEdit();
            }

            @Override public boolean endBatchEdit() {
                if (!active()) return false;
                boolean result = super.endBatchEdit();
                batches = Math.max(0, batches - 1);
                if (batches == 0) main.removeCallbacks(batchWatchdog);
                schedule();
                return result;
            }

            @Override public boolean setComposingText(CharSequence text, int cursor) {
                return active() && afterImeEdit(super.setComposingText(text, cursor));
            }

            @Override public boolean setComposingRegion(int start, int end) {
                return active() && afterImeEdit(super.setComposingRegion(start, end));
            }

            @Override public boolean commitText(CharSequence text, int cursor) {
                return active() && afterImeEdit(super.commitText(text, cursor));
            }

            @Override public boolean finishComposingText() {
                return active() && afterImeEdit(super.finishComposingText());
            }

            @Override public boolean deleteSurroundingText(int before, int after) {
                if (!active()) return false;
                if (length() == 0 && before > 0 && after == 0) {
                    for (int i = 0; i < Math.min(before, 2048); i++) sender.key(8);
                    return true;
                }
                return afterImeEdit(super.deleteSurroundingText(before, after));
            }

            @Override public boolean deleteSurroundingTextInCodePoints(int before, int after) {
                if (!active()) return false;
                if (length() == 0 && before > 0 && after == 0) {
                    for (int i = 0; i < Math.min(before, 2048); i++) sender.key(8);
                    return true;
                }
                return afterImeEdit(super.deleteSurroundingTextInCodePoints(before, after));
            }

            @Override public boolean performEditorAction(int action) {
                if (!active()) return false;
                enter();
                return true;
            }

            @Override public boolean sendKeyEvent(KeyEvent event) {
                if (!active()) return false;
                if (event != null && event.getAction() == KeyEvent.ACTION_MULTIPLE
                        && event.getCharacters() != null && !event.getCharacters().isEmpty()) {
                    sender.edit(0, event.getCharacters());
                    return true;
                }
                if (event != null && event.getAction() == KeyEvent.ACTION_DOWN
                        && !composing() && length() == 0 && event.getUnicodeChar() >= 0x80) {
                    sender.edit(0, new String(Character.toChars(event.getUnicodeChar())));
                    return true;
                }
                return super.sendKeyEvent(event);
            }

            @Override public boolean setSelection(int start, int end) {
                return active() && super.setSelection(start, end);
            }

            @Override public void closeConnection() {
                main.removeCallbacks(batchWatchdog);
                batches = 0;
                if (active()) super.closeConnection();
            }

            @android.annotation.SuppressLint("NewApi")
            @Override public boolean commitText(CharSequence text, int cursor, android.view.inputmethod.TextAttribute attributes) {
                return active() && afterImeEdit(super.commitText(text, cursor, attributes));
            }

            @android.annotation.SuppressLint("NewApi")
            @Override public boolean setComposingText(CharSequence text, int cursor, android.view.inputmethod.TextAttribute attributes) {
                return active() && afterImeEdit(super.setComposingText(text, cursor, attributes));
            }

            @android.annotation.SuppressLint("NewApi")
            @Override public boolean setComposingRegion(int start, int end, android.view.inputmethod.TextAttribute attributes) {
                return active() && afterImeEdit(super.setComposingRegion(start, end, attributes));
            }

            @android.annotation.SuppressLint("NewApi")
            @Override public boolean replaceText(int start, int end, CharSequence text, int cursor, android.view.inputmethod.TextAttribute attributes) {
                return active() && afterImeEdit(super.replaceText(start, end, text, cursor, attributes));
            }
        };
    }
}
