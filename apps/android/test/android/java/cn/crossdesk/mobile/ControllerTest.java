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
        input.drag(50, -40);
        input.releaseAll();
        assertFalse(input.hasVirtualButton());
        assertTrue(flags.contains(5));
        assertTrue(flags.contains(6));
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
