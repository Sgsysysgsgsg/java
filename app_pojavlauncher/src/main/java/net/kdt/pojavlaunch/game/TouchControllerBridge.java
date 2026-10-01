package net.kdt.pojavlaunch.game;

import android.util.SparseIntArray;
import android.view.MotionEvent;

import java.util.HashSet;
import java.util.Set;

import top.fifthlight.touchcontroller.proxy.client.LauncherProxyClient;
import top.fifthlight.touchcontroller.proxy.client.PlatformCapability;
import top.fifthlight.touchcontroller.proxy.client.android.transport.UnixSocketTransportKt;

/** Official GoLauncher bridge for TouchController Android input. */
public final class TouchControllerBridge implements AutoCloseable {
    public static final String SOCKET_NAME = "GoLauncherTouchController";

    private final GameActivity activity;
    private final LauncherProxyClient client;
    private final SparseIntArray pointerIds = new SparseIntArray();
    private int nextPointerId = 1;
    private boolean started;

    public TouchControllerBridge(GameActivity activity) {
        this.activity = activity;
        Set<PlatformCapability> capabilities = new HashSet<>();
        capabilities.add(PlatformCapability.KEYBOARD_SHOW);

        client = new LauncherProxyClient(
                UnixSocketTransportKt.UnixSocketTransport(SOCKET_NAME),
                capabilities,
                false
        );

        client.setKeyboardShowHandler(new LauncherProxyClient.KeyboardShowHandler() {
            @Override public void showKeyboard() {
                activity.runOnUiThread(() -> activity.updateTouchControllerKeyboard(true));
            }
            @Override public void hideKeyboard() {
                activity.runOnUiThread(() -> activity.updateTouchControllerKeyboard(false));
            }
        });
    }

    public void start() {
        if (started) return;
        started = true;
        client.run();
    }

    public void dispatchTouchEvent(MotionEvent event, int width, int height) {
        if (!started || width <= 0 || height <= 0) return;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                int id = nextPointerId++;
                pointerIds.put(event.getPointerId(0), id);
                client.addPointer(id, x(event.getX(0), width), y(event.getY(0), height));
                break;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                int index = event.getActionIndex();
                int id = nextPointerId++;
                pointerIds.put(event.getPointerId(index), id);
                client.addPointer(id, x(event.getX(index), width), y(event.getY(index), height));
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < event.getPointerCount(); i++) {
                    int id = pointerIds.get(event.getPointerId(i), 0);
                    if (id != 0) client.addPointer(id, x(event.getX(i), width), y(event.getY(i), height));
                }
                break;
            case MotionEvent.ACTION_POINTER_UP: {
                int index = event.getActionIndex();
                int rawId = event.getPointerId(index);
                int id = pointerIds.get(rawId, 0);
                if (id != 0) {
                    pointerIds.delete(rawId);
                    client.removePointer(id);
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                pointerIds.clear();
                client.clearPointer();
                break;
            default:
                break;
        }
    }

    private static float x(float value, int width) {
        return Math.max(0f, Math.min(1f, value / width));
    }

    private static float y(float value, int height) {
        return Math.max(0f, Math.min(1f, value / height));
    }

    @Override public void close() {
        pointerIds.clear();
        try { client.clearPointer(); } catch (Throwable ignored) {}
        client.close();
    }
}
