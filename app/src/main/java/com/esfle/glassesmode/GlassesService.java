package com.esfle.glassesmode;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

/**
 * Glasses mode: while AR glasses are connected, double-tapping either volume button
 * 1. forces landscape, and
 * 2. covers the phone's own screen with a black layer that is flagged "built-in screen only",
 *    so the glasses (an external display) keep showing the normal picture, and
 * 3. ignores the phone's touchscreen so nothing gets pressed by accident.
 * Another double tap, or unplugging the glasses, undoes it. Nothing in system settings is changed.
 */
public class GlassesService extends AccessibilityService implements DisplayManager.DisplayListener {
    static final String TAG = "GlassesMode";
    private static final String ACTION_TOGGLE = "com.esfle.glassesmode.TOGGLE";
    private static final long DOUBLE_TAP_MS = 350;
    private static final long HOLD_START_MS = 500;
    private static final long HOLD_REPEAT_MS = 120;
    private static final long DISCONNECT_GRACE_MS = 2000;
    private static final int DISPLAY_TYPE_EXTERNAL = 2; // Display.TYPE_EXTERNAL (hidden)

    static volatile GlassesService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private DisplayManager displayManager;
    private AudioManager audio;
    private Vibrator vibrator;
    private WindowManager windowManager;

    private boolean glassesConnected;
    private boolean active;
    private View orientationOverlay;
    private SurfaceControl blackout;
    private Surface blackoutSurface;

    private int heldKey;             // volume key currently pressed, or 0
    private boolean heldIsSecondTap; // the current press completed a double tap
    private boolean heldRepeating;   // the current press has turned into a hold
    private int pendingKey;          // single tap waiting to see if a second one follows, or 0

    /** Lets me toggle from a PC for testing: adb shell am broadcast -a com.esfle.glassesmode.TOGGLE */
    private final BroadcastReceiver debugToggle = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            toggle();
        }
    };

    // ---- lifecycle ----

    @Override
    protected void onServiceConnected() {
        instance = this;
        try {
            HiddenApiBypass.addHiddenApiExemptions("Landroid/view/SurfaceControl", "Landroid/view/Display;");
        } catch (Throwable t) {
            Log.w(TAG, "Hidden API exemption failed", t);
        }
        displayManager = getSystemService(DisplayManager.class);
        audio = getSystemService(AudioManager.class);
        vibrator = getSystemService(VibratorManager.class).getDefaultVibrator();
        windowManager = getSystemService(WindowManager.class);

        displayManager.registerDisplayListener(this, handler);
        // Only senders holding DUMP (i.e. adb shell) can trigger this.
        registerReceiver(debugToggle, new IntentFilter(ACTION_TOGGLE), Manifest.permission.DUMP, handler,
                Context.RECEIVER_EXPORTED);
        refreshGlasses();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (active) {
            deactivate(false);
        }
        try {
            unregisterReceiver(debugToggle);
        } catch (Exception ignored) {
        }
        if (displayManager != null) {
            displayManager.unregisterDisplayListener(this);
        }
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    boolean isGlassesConnected() {
        return glassesConnected;
    }

    boolean isActive() {
        return active;
    }

    // ---- volume key trigger (only while glasses are connected) ----
    //
    // Double-tapping the same volume button toggles glasses mode. A single tap still changes the volume,
    // just after a short wait to rule out a second tap. Holding a button keeps changing the volume.

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (key != KeyEvent.KEYCODE_VOLUME_UP && key != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false;
        }
        if (!glassesConnected && !active && heldKey == 0 && pendingKey == 0) {
            return false; // normal volume behaviour
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() > 0) {
                return true;
            }
            handler.removeCallbacks(holdRepeat);
            if (pendingKey == key) {
                // Second tap: toggle instead of changing the volume.
                handler.removeCallbacks(pendingTap);
                pendingKey = 0;
                startPress(key, true);
                toggle();
                return true;
            }
            flushPendingTap(); // a tap on the other button is waiting: apply it now
            startPress(key, false);
            handler.postDelayed(holdRepeat, HOLD_START_MS);
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_UP) {
            handler.removeCallbacks(holdRepeat);
            if (key == heldKey) {
                if (!heldIsSecondTap && !heldRepeating) {
                    pendingKey = key;
                    handler.postDelayed(pendingTap, DOUBLE_TAP_MS);
                }
                heldKey = 0;
            }
            return true;
        }
        return true;
    }

    private void startPress(int key, boolean secondTap) {
        heldKey = key;
        heldIsSecondTap = secondTap;
        heldRepeating = false;
    }

    /** No second tap came in time: it was a normal volume press. */
    private final Runnable pendingTap = () -> {
        int key = pendingKey;
        pendingKey = 0;
        if (key != 0) {
            stepVolume(key);
        }
    };

    private void flushPendingTap() {
        if (pendingKey != 0) {
            handler.removeCallbacks(pendingTap);
            pendingTap.run();
        }
    }

    /** Button held down: keep stepping the volume, like Android normally does. */
    private final Runnable holdRepeat = new Runnable() {
        @Override
        public void run() {
            if (heldKey == 0 || heldIsSecondTap) {
                return;
            }
            heldRepeating = true;
            stepVolume(heldKey);
            handler.postDelayed(this, HOLD_REPEAT_MS);
        }
    };

    private void stepVolume(int key) {
        audio.adjustSuggestedStreamVolume(
                key == KeyEvent.KEYCODE_VOLUME_UP ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI);
    }

    private void toggle() {
        if (active) {
            deactivate(true);
        } else {
            activate();
        }
    }

    // ---- glasses mode ----

    private void activate() {
        try {
            // Keep whichever landscape the phone is already in; otherwise use normal landscape.
            boolean reverse = mainDisplay().getRotation() == Surface.ROTATION_270;
            showOrientationOverlay(reverse
                    ? ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                    : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            showBlackout();
            setTouchBlocked(true);
            active = true;
            toast("Glasses mode on");
            buzz(1);
        } catch (Throwable t) {
            Log.e(TAG, "activate failed", t);
            deactivate(false);
            toast("Glasses Mode error: " + t.getMessage());
        }
    }

    private void deactivate(boolean feedback) {
        active = false;
        setTouchBlocked(false);
        hideBlackout();
        removeOrientationOverlay();
        if (feedback) {
            toast("Glasses mode off");
            buzz(2);
        }
    }

    /**
     * Black layer over the phone's main screen. The "skip screenshot" flag makes SurfaceFlinger draw it
     * on built-in screens only, so it is left out of the copy sent to the glasses.
     */
    private void showBlackout() throws Exception {
        if (blackout != null) {
            return;
        }
        final int size = 16;
        SurfaceControl sc = new SurfaceControl.Builder()
                .setName("GlassesMode blackout")
                .setBufferSize(size, size)
                .setFormat(PixelFormat.OPAQUE)
                .setOpaque(true)
                .build();
        Surface surface = new Surface(sc);
        Canvas canvas = surface.lockCanvas(null);
        canvas.drawColor(Color.BLACK);
        surface.unlockCanvasAndPost(canvas);

        SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        // Far bigger than any screen, centred on the origin, so every rotation is covered.
        t.setPosition(sc, -8000, -8000);
        t.setScale(sc, 16000f / size, 16000f / size);
        t.setLayer(sc, Integer.MAX_VALUE);
        t.setVisibility(sc, true);
        SurfaceControl.Transaction.class.getMethod("setSkipScreenshot", SurfaceControl.class, boolean.class)
                .invoke(t, sc, true);
        t.apply();

        attachAccessibilityOverlayToDisplay(Display.DEFAULT_DISPLAY, sc);
        blackout = sc;
        blackoutSurface = surface;
    }

    private void hideBlackout() {
        if (blackout != null) {
            try {
                new SurfaceControl.Transaction().reparent(blackout, null).apply();
            } catch (Exception ignored) {
            }
            blackout.release();
            blackout = null;
        }
        if (blackoutSurface != null) {
            blackoutSurface.release();
            blackoutSurface = null;
        }
    }

    /**
     * Takes over the touchscreen at the system level, before apps and system gestures see it,
     * so taps, edge swipes and the notification shade all do nothing. Volume keys keep working.
     */
    private void setTouchBlocked(boolean blocked) {
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info == null) {
                return;
            }
            info.setMotionEventSources(blocked ? InputDevice.SOURCE_TOUCHSCREEN : 0);
            setServiceInfo(info);
        } catch (Throwable t) {
            Log.w(TAG, "Touch block failed", t);
        }
    }

    @Override
    public void onMotionEvent(MotionEvent event) {
        // Touches land here while glasses mode is on; dropping them is the point.
    }

    /**
     * Invisible full-screen window that forces landscape (even over portrait-only apps), keeps the phone
     * awake, and swallows every touch so nothing gets pressed by accident. It is transparent, so the
     * glasses don't see it.
     */
    private void showOrientationOverlay(int orientation) {
        removeOrientationOverlay();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.setFitInsetsTypes(0);
        lp.screenOrientation = orientation;
        orientationOverlay = new View(this);
        orientationOverlay.setOnTouchListener((v, e) -> true);
        windowManager.addView(orientationOverlay, lp);
    }

    private void removeOrientationOverlay() {
        if (orientationOverlay != null) {
            try {
                windowManager.removeView(orientationOverlay);
            } catch (Exception ignored) {
            }
            orientationOverlay = null;
        }
    }

    // ---- display events ----

    private final Runnable checkGlasses = () -> {
        refreshGlasses();
        if (active && !glassesConnected) {
            deactivate(true);
        }
    };

    private void refreshGlasses() {
        boolean found = false;
        for (Display d : displayManager.getDisplays()) {
            if (d.getDisplayId() != Display.DEFAULT_DISPLAY && isExternal(d)) {
                found = true;
                break;
            }
        }
        if (found != glassesConnected) {
            Log.i(TAG, "Glasses " + (found ? "connected" : "disconnected"));
        }
        glassesConnected = found;
    }

    private static boolean isExternal(Display d) {
        try {
            return (int) Display.class.getMethod("getType").invoke(d) == DISPLAY_TYPE_EXTERNAL;
        } catch (Throwable t) {
            String name = d.getName();
            return name != null && name.contains("HDMI");
        }
    }

    @Override
    public void onDisplayAdded(int displayId) {
        handler.removeCallbacks(checkGlasses);
        handler.postDelayed(checkGlasses, 300);
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        // Wait a bit: the glasses can briefly drop out when switching display modes.
        handler.removeCallbacks(checkGlasses);
        handler.postDelayed(checkGlasses, DISCONNECT_GRACE_MS);
    }

    @Override
    public void onDisplayChanged(int displayId) {
    }

    // ---- feedback ----

    private Display mainDisplay() {
        return displayManager.getDisplay(Display.DEFAULT_DISPLAY);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private void buzz(int pulses) {
        VibrationEffect effect = pulses == 1
                ? VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE)
                : VibrationEffect.createWaveform(new long[] {0, 50, 90, 50}, -1);
        vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ACCESSIBILITY));
    }
}
