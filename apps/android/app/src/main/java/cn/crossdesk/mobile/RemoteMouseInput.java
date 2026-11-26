package cn.crossdesk.mobile;

import java.util.ArrayList;
import java.util.List;

/** Shared button ownership (left, right, middle) and cursor position for the video surface and floating controls. */
final class RemoteMouseInput {
    static final int BUTTON_LEFT = 0;
    static final int BUTTON_RIGHT = 1;
    static final int BUTTON_MIDDLE = 2;

    interface Sender { void send(float x, float y, int flag, int wheel); }
    interface RelativeMotion { void move(float dx, float dy); }
    RelativeMotion relativeMotion;
    private final Sender sender;
    private float x = .5f, y = .5f;
    private boolean gestureLeft, virtualLeft, virtualRight, virtualMiddle;
    private long revision;
    Runnable didCancel;
    private final List<Runnable> cancelListeners = new ArrayList<>();
    float viewportWidth, viewportHeight;

    RemoteMouseInput(Sender sender) { this.sender = sender; }

    void addCancelListener(Runnable listener) {
        if (listener != null && !cancelListeners.contains(listener)) cancelListeners.add(listener);
    }

    boolean hasVirtualButton() { return virtualLeft || virtualRight || virtualMiddle; }
    long revision() { return revision; }

    void move(float x, float y) {
        this.x = Math.max(0, Math.min(1, x)); this.y = Math.max(0, Math.min(1, y));
        send(0, 0);
    }

    void drag(float dx, float dy) {
        if (!hasVirtualButton() || viewportWidth <= 0 || viewportHeight <= 0) return;
        if (relativeMotion != null) relativeMotion.move(dx, dy);
        else move(x + dx / viewportWidth, y + dy / viewportHeight);
    }

    void gestureLeft(boolean down) {
        if (down && hasVirtualButton()) return;
        if (gestureLeft == down) return;
        gestureLeft = down;
        send(down ? 1 : 2, 0);
    }

    void click(int down, int up) {
        if (hasVirtualButton()) return;
        send(down, 0); send(up, 0);
    }

    void virtualButton(boolean left, boolean down) {
        virtualButton(left ? BUTTON_LEFT : BUTTON_RIGHT, down);
    }

    void virtualMiddle(boolean down) {
        virtualButton(BUTTON_MIDDLE, down);
    }

    void virtualButton(int button, boolean down) {
        boolean current = button == BUTTON_LEFT ? virtualLeft
                : button == BUTTON_RIGHT ? virtualRight : virtualMiddle;
        if (current == down) return;
        if (down) {
            gestureLeft(false);
            revision++;
        }
        int flag;
        if (button == BUTTON_LEFT) {
            virtualLeft = down;
            flag = down ? 1 : 2;
        } else if (button == BUTTON_RIGHT) {
            virtualRight = down;
            flag = down ? 3 : 4;
        } else {
            virtualMiddle = down;
            flag = down ? 5 : 6;
        }
        send(flag, 0);
    }

    void scroll(int direction) {
        if (direction != 0) send(7, direction > 0 ? 1 : -1);
    }

    void releaseAll() {
        gestureLeft(false);
        virtualButton(BUTTON_LEFT, false);
        virtualButton(BUTTON_RIGHT, false);
        virtualButton(BUTTON_MIDDLE, false);
        revision++;
        if (didCancel != null) didCancel.run();
        for (Runnable listener : cancelListeners) listener.run();
    }

    private void send(int flag, int wheel) { sender.send(x, y, flag, wheel); }
}
