package cn.crossdesk.mobile;

import android.content.Context;
import android.view.KeyEvent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ControllerTest {

    @Test public void presenceCallbacksRejectDisconnectedAndPreviousSignalingGeneration()throws Exception{
        var instrumentation=InstrumentationRegistry.getInstrumentation();
        var delivered=new java.util.concurrent.atomic.AtomicInteger();
        NativeSession.Listener listener=new NativeSession.Listener(){
            public void status(String value){}public void connected(){}public void ended(String reason){}
            public void data(JSONObject value){}public void videoSize(int width,int height){}public void clipboard(String value){}public void statistics(RemoteNetworkStatistics.Snapshot value){}
            public void presence(JSONObject value){delivered.incrementAndGet();}
        };
        NativeSession session=new NativeSession(instrumentation.getTargetContext(),"127.0.0.1",1,"","",listener);
        Field workerField=NativeSession.class.getDeclaredField("RTC"),requestedField=NativeSession.class.getDeclaredField("presenceRequested");workerField.setAccessible(true);requestedField.setAccessible(true);
        ExecutorService worker=(ExecutorService)workerField.get(null);
        var event=NativeSession.class.getDeclaredMethod("onNativeEvent",int.class,byte[].class);event.setAccessible(true);
        java.util.function.BiConsumer<Integer,String> send=(type,json)->{
            try{event.invoke(session,type,json.getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(Exception error){throw new RuntimeException(error);}
        };
        try{
            send.accept(1,"{\"controller\":false,\"status\":1,\"generation\":1}");
            worker.submit(()->{requestedField.setBoolean(session,true);return null;}).get(5,TimeUnit.SECONDS);
            send.accept(9,"{\"type\":\"presence\",\"devices\":[],\"generation\":1}");
            worker.submit(()->{}).get(5,TimeUnit.SECONDS);instrumentation.waitForIdleSync();assertEquals(1,delivered.get());
            send.accept(1,"{\"controller\":false,\"status\":4,\"generation\":2}");
            send.accept(9,"{\"type\":\"presence\",\"devices\":[],\"generation\":1}");
            send.accept(1,"{\"controller\":false,\"status\":1,\"generation\":3}");
            worker.submit(()->{requestedField.setBoolean(session,true);return null;}).get(5,TimeUnit.SECONDS);
            send.accept(9,"{\"type\":\"presence\",\"devices\":[],\"generation\":1}");
            worker.submit(()->{}).get(5,TimeUnit.SECONDS);instrumentation.waitForIdleSync();assertEquals(1,delivered.get());
            send.accept(1,"{\"controller\":false,\"status\":1,\"generation\":3}");
            send.accept(9,"{\"type\":\"presence_update\",\"id\":\"123456789\",\"online\":true,\"generation\":3}");
            worker.submit(()->{}).get(5,TimeUnit.SECONDS);instrumentation.waitForIdleSync();assertEquals("Repeated login readiness keeps the current subscription",2,delivered.get());
        }finally{session.close();worker.submit(()->{}).get(5,TimeUnit.SECONDS);}
    }

    @Test public void repeatedNativeConnectedEventsNotifyAndInitializeOnlyOnce()throws Exception{
        var instrumentation=InstrumentationRegistry.getInstrumentation();
        var notifications=new java.util.concurrent.atomic.AtomicInteger();
        NativeSession.Listener listener=new NativeSession.Listener(){
            public void status(String value){}public void connected(){notifications.incrementAndGet();}public void ended(String reason){}
            public void data(JSONObject value){}public void videoSize(int width,int height){}public void clipboard(String value){}public void statistics(RemoteNetworkStatistics.Snapshot value){}
        };
        NativeSession session=new NativeSession(instrumentation.getTargetContext(),"127.0.0.1",1,"123456789","test",listener);
        Field workerField=NativeSession.class.getDeclaredField("RTC"),audioField=NativeSession.class.getDeclaredField("audio");workerField.setAccessible(true);audioField.setAccessible(true);
        ExecutorService worker=(ExecutorService)workerField.get(null);
        var event=NativeSession.class.getDeclaredMethod("onNativeEvent",int.class,byte[].class);event.setAccessible(true);
        byte[] connected="{\"status\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try{
            // Exercise the Java native-callback path without starting a network peer.
            event.invoke(session,2,connected);Object audio=worker.submit(()->audioField.get(session)).get(5,TimeUnit.SECONDS);
            instrumentation.waitForIdleSync();assertEquals(1,notifications.get());assertNotNull(audio);
            event.invoke(session,2,connected);event.invoke(session,2,connected);
            assertSame("Duplicate readiness must not replace the audio player",audio,worker.submit(()->audioField.get(session)).get(5,TimeUnit.SECONDS));
            instrumentation.waitForIdleSync();assertEquals("One desktop connection must notify the UI only once",1,notifications.get());
        }finally{session.close();worker.submit(()->{}).get(5,TimeUnit.SECONDS);}
    }
    @Test public void keyboardSupportsDesktopVirtualKeys() {
        assertEquals(0x41, KeyMap.windowsCode(KeyEvent.KEYCODE_A));
        assertEquals(0x2E, KeyMap.windowsCode(KeyEvent.KEYCODE_FORWARD_DEL));
        assertEquals(0x11, KeyMap.windowsCode(KeyEvent.KEYCODE_CTRL_LEFT));
        assertEquals(0x5B, KeyMap.windowsCode(KeyEvent.KEYCODE_META_LEFT));
        assertEquals(0x60, KeyMap.windowsCode(KeyEvent.KEYCODE_NUMPAD_0));
        assertEquals(0x90, KeyMap.windowsCode(KeyEvent.KEYCODE_NUM_LOCK));
        assertEquals(KeyCatalog.VK_LCONTROL, KeyCatalog.normalizeVk(0x11));
        assertEquals(KeyCatalog.VK_LSHIFT, KeyCatalog.normalizeVk(0x10));
        assertEquals(KeyCatalog.VK_LMENU, KeyCatalog.normalizeVk(0x12));
        assertArrayEquals(new int[]{KeyCatalog.VK_LCONTROL, KeyCatalog.VK_LSHIFT},
                KeyCatalog.normalizeModifiers(new int[]{16, 17, 0xA2}));
        assertEquals("Cmd+Option+Esc",
                KeyCatalog.chordLabel(KeyCatalog.VK_ESCAPE, new int[]{18, 91}, "macos")
                        .equals("Option+Cmd+Esc") ? "Cmd+Option+Esc" : KeyCatalog.chordLabel(KeyCatalog.VK_ESCAPE, new int[]{18, 91}, "macos"));
        assertEquals(0xDB | 0x100, KeyMap.ascii('{'));
        assertEquals(0x31 | 0x100, KeyMap.ascii('!'));
        assertEquals(0, KeyMap.ascii('中'));
    }

    @Test public void mouseInputSupportsMiddleButtonAndCleanRelease() {
        java.util.List<Integer> flags = new java.util.ArrayList<>();
        RemoteMouseInput input = new RemoteMouseInput((x, y, flag, wheel) -> flags.add(flag));
        input.viewportWidth = 1000;
        input.viewportHeight = 800;
        input.virtualMiddle(true);
        assertTrue(input.hasVirtualButton());
        assertTrue(input.hasAnyButton());
        input.drag(50, -40);
        input.releaseAll();
        assertFalse(input.hasVirtualButton());
        assertFalse(input.hasAnyButton());
        assertTrue(flags.contains(5));
        assertTrue(flags.contains(6));
    }

    @Test public void pointerBallisticsAdaptsPrecisionAndFlickWithSafeDtClamping() {
        assertEquals(0.40f, PointerBallistics.clampSensitivity(0.10f), 0.0001f);
        assertEquals(2.50f, PointerBallistics.clampSensitivity(9.00f), 0.0001f);
        assertEquals(PointerBallistics.DEFAULT_SENSITIVITY, PointerBallistics.clampSensitivity(Float.NaN), 0.0001f);

        assertEquals(4f, PointerBallistics.clampDtMs(1L), 0.0001f);
        assertEquals(50f, PointerBallistics.clampDtMs(400L), 0.0001f);
        assertEquals(16f, PointerBallistics.clampDtMs(0L), 0.0001f);

        // Slow precision crawl (< 0.18 dp/ms) stays in [0.85, 0.96]
        float slowGain = PointerBallistics.accelerationGain(1.2f, 16L); // 0.075 dp/ms
        assertTrue("Slow finger speed should retain sub-1.0 precision gain", slowGain >= 0.85f && slowGain <= 0.96f);

        // Fast flick (> 1.40 dp/ms) ramps smoothly up to 2.20x cap
        float midGain = PointerBallistics.accelerationGain(10f, 16L); // 0.625 dp/ms
        float fastGain = PointerBallistics.accelerationGain(30f, 16L); // 1.875 dp/ms -> 2.20x
        assertTrue(midGain > slowGain && midGain < fastGain);
        assertEquals(2.20f, fastGain, 0.0001f);

        // Dropped frame (dt = 250ms clamped to 50ms) never exceeds max cap
        float stalledGain = PointerBallistics.accelerationGain(100f, 250L);
        assertTrue(stalledGain <= 2.20f);

        // Acceleration OFF uses exact linear sensitivity multiplier
        assertEquals(15f, PointerBallistics.scaleDeltaX(10f, 0f, 3f, 16L, 1.50f, false), 0.0001f);
        assertEquals(-9f, PointerBallistics.scaleDeltaY(0f, -6f, 3f, 16L, 1.50f, false), 0.0001f);
    }

    @Test public void scrollAccumulatorPreservesRemainderAndHonorsDirection() {
        PointerBallistics.ScrollAccumulator acc = new PointerBallistics.ScrollAccumulator();
        float step = 7.5f;

        // Sub-step movements preserve remainder until crossing threshold
        assertEquals(0, acc.consume(3.0f, step, true));
        assertEquals(0, acc.consume(3.0f, step, true));
        assertEquals(1, acc.consume(3.0f, step, true)); // 9.0 -> 1 tick (+1), remainder 1.5
        assertEquals(1.5f, acc.remainder(), 0.001f);
        assertEquals(1, acc.consume(6.2f, step, true)); // 1.5 + 6.2 = 7.7 -> 1 tick (+1), remainder 0.2

        acc.reset();
        // Natural scroll: fingers up (dy < 0) -> content up / wheel down (-1)
        assertEquals(-2, acc.consume(-16.0f, step, true));

        acc.reset();
        // Standard wheel: fingers down (dy > 0) -> wheel down (-1); fingers up -> wheel up (+1)
        assertEquals(-2, acc.consume(16.0f, step, false));
        acc.reset();
        assertEquals(2, acc.consume(-16.0f, step, false));
    }

    @Test public void virtualButtonMaintainsHoldAcrossSimultaneousRelativeMotion() {
        java.util.List<Integer> flags = new java.util.ArrayList<>();
        float[] pos = {0.5f, 0.5f};
        RemoteMouseInput input = new RemoteMouseInput((x, y, flag, wheel) -> {
            pos[0] = x;
            pos[1] = y;
            flags.add(flag);
        });
        input.viewportWidth = 1000f;
        input.viewportHeight = 500f;
        input.relativeMotion = (dx, dy, dtMs) -> input.move(pos[0] + dx / 1000f, pos[1] + dy / 500f);

        // Right thumb holds virtual left button (1)
        input.virtualButton(RemoteMouseInput.BUTTON_LEFT, true);
        assertTrue(input.hasVirtualButton());
        assertEquals(java.util.List.of(1), flags);

        // Left thumb moves cursor on video surface & lifts without releasing virtual button
        input.virtualMouseMove(100f, -50f, 16L);
        input.gestureLeft(true); // Must be ignored while virtual button is held
        input.click(1, 2);       // Spurious click must be ignored while virtual button is held
        assertTrue(input.hasVirtualButton());
        assertEquals(0.60f, input.x(), 0.0001f);
        assertEquals(0.40f, input.y(), 0.0001f);
        assertFalse("Virtual left button must not emit button-up (2) until released", flags.contains(2));

        // Right thumb finally lifts off VirtualMouseBar -> emits button-up (2)
        input.virtualButton(RemoteMouseInput.BUTTON_LEFT, false);
        assertFalse(input.hasVirtualButton());
        assertEquals(2, (int) flags.get(flags.size() - 1));
    }

    @Test public void coupledVirtualMouseClampingAndPerModeVisibility() {
        // Coupled mouse stays offset by +40px normally, and clamps cleanly at right/left edges
        assertEquals(540f, PointerBallistics.coupledMouseCoord(500f, 40f, 280f, 12f, 1908f), 0.001f);
        assertEquals(1628f, PointerBallistics.coupledMouseCoord(1890f, 40f, 280f, 12f, 1908f), 0.001f);
        assertEquals(12f, PointerBallistics.coupledMouseCoord(0f, -20f, 280f, 12f, 1908f), 0.001f);

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        var prefs = context.getSharedPreferences("mouse_test_prefs", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        try {
            assertTrue("Relative pointer mode defaults to showing floating virtual mouse",
                    PointerBallistics.loadVirtualMouseVisible(prefs, true));
            assertFalse("Direct touch mode defaults to hiding floating virtual mouse",
                    PointerBallistics.loadVirtualMouseVisible(prefs, false));

            PointerBallistics.saveVirtualMouseVisible(prefs, true, false);
            PointerBallistics.saveVirtualMouseVisible(prefs, false, true);
            assertFalse(PointerBallistics.loadVirtualMouseVisible(prefs, true));
            assertTrue(PointerBallistics.loadVirtualMouseVisible(prefs, false));
        } finally {
            prefs.edit().clear().commit();
        }
    }

    @Test public void floatingKeymapStorePersistsProfilesAndWidgets() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.getSharedPreferences("floating_keymap_v1", Context.MODE_PRIVATE).edit().clear().commit();
        try {
            FloatingKeymapStore store = new FloatingKeymapStore(context);
            assertFalse(store.isEnabled());
            assertFalse(store.activeProfile().widgets.isEmpty());
            FloatingKeymapStore.Profile created = store.createProfile("FPS方案");
            assertEquals("FPS方案", store.activeProfile().name);
            store.setEnabled(true);

            FloatingKeymapStore reloaded = new FloatingKeymapStore(context);
            assertTrue(reloaded.isEnabled());
            assertEquals(created.id, reloaded.activeProfile().id);
        } finally {
            context.getSharedPreferences("floating_keymap_v1", Context.MODE_PRIVATE).edit().clear().commit();
        }
    }

    @Test public void credentialsRemainEncryptedAndServerScoped() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SecretStore store = new SecretStore(context);
        String scope = "instrumentation.invalid:9099";
        try {
            store.put(scope, "test-id@temporary-test-password");
            assertEquals("test-id@temporary-test-password", store.get(scope));
            assertEquals("", store.get("instrumentation.invalid:9100"));
            String persisted = context.getSharedPreferences("identities", Context.MODE_PRIVATE).getString(scope, "");
            assertFalse(persisted.contains("temporary-test-password"));
        } finally {
            context.getSharedPreferences("identities", Context.MODE_PRIVATE).edit().remove(scope).commit();
        }
    }

    @Test public void nativePeerCanStartAndCancelRepeatedly() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Field workerField = NativeSession.class.getDeclaredField("RTC"); workerField.setAccessible(true);
        ExecutorService worker = (ExecutorService) workerField.get(null);
        Field handleField = NativeSession.class.getDeclaredField("handle"); handleField.setAccessible(true);
        NativeSession.Listener listener = new NativeSession.Listener() {
            public void status(String value) { }
            public void connected() { fail("Loopback port 1 must not establish a session"); }
            public void ended(String reason) { }
            public void data(JSONObject data) { }
            public void videoSize(int width, int height) { }
            public void clipboard(String value) { }
            public void statistics(RemoteNetworkStatistics.Snapshot value) { }
        };
        for (int i = 0; i < 3; i++) {
            NativeSession session = new NativeSession(context, "127.0.0.1", 1, "invalid", "test", listener);
            session.start();
            assertTrue(worker.submit(() -> handleField.getLong(session)).get(15, TimeUnit.SECONDS) != 0);
            session.surface(null);
            session.close(); session.close();
            assertEquals(0L, (long) worker.submit(() -> handleField.getLong(session)).get(15, TimeUnit.SECONDS));
        }
    }

}
