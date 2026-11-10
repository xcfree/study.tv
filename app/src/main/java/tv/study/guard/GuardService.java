package tv.study.guard;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.util.LongSparseArray;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.FrameLayout;

public final class GuardService extends AccessibilityService {
    static final String XIAOMI_STR_WAKE = "mitv.action.STR_BOOT_COMPLETED";
    private static final long PAUSE_RETRY_MS = 1000;
    private static GuardService connected;
    private static boolean homeResumed;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable heartbeat = this::inspect;
    private final Runnable restoreHome = this::restoreHomeAfterStr;
    private final LongSparseArray<Long> capturedKeys = new LongSparseArray<>();
    private boolean inspectionQueued;
    private final Runnable inspection = () -> {
        inspectionQueued = false;
        inspect();
    };
    private Store store;
    private PowerManager power;
    private AudioManager audio;
    private WindowManager windows;
    private GatePanel overlay;
    private Wallpaper overlayWallpaper;
    private boolean overlayIsSettings;
    private boolean interactive;
    private boolean receiverRegistered;
    private boolean xiaomi6129;
    private String foreground;
    private String pauseRequestedFor;
    private long lastPauseRequest;
    private final BroadcastReceiver powerEvents = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (XIAOMI_STR_WAKE.equals(action)) {
                relockAfterWake();
                // The firmware also tries to launch HOME while its display is still waking.
                handler.removeCallbacks(restoreHome);
                handler.postDelayed(restoreHome, 1200);
            } else {
                boolean screenOn = Intent.ACTION_SCREEN_ON.equals(action);
                if (!screenOn) handler.removeCallbacks(restoreHome);
                setInteractive(screenOn);
            }
            requestInspection();
        }
    };

    static boolean isConnected() {
        return connected != null;
    }

    static void refreshSoon() {
        if (connected != null) connected.requestInspection();
    }

    static void setHomeResumed(boolean resumed) {
        homeResumed = resumed;
        if (connected != null && resumed) {
            connected.foreground = connected.getPackageName();
            connected.store.update(false);
            connected.removeOverlay();
        }
        refreshSoon();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        store = Store.get(this);
        power = (PowerManager) getSystemService(POWER_SERVICE);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        windows = (WindowManager) getSystemService(WINDOW_SERVICE);
        interactive = power.isInteractive();
        xiaomi6129 = Build.VERSION.SDK_INT == 28 && "com.ts.launcher".equals(getPackageName());
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(powerEvents, filter, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(powerEvents, filter);
            if (xiaomi6129) {
                // Android 9 uses the sender permission to restrict this broadcast.
                //noinspection UnspecifiedRegisterReceiverFlag
                registerReceiver(powerEvents, new IntentFilter(XIAOMI_STR_WAKE),
                        "android.permission.DEVICE_POWER", null);
            }
            receiverRegistered = true;
        }
        connected = this;
        CloudAdmin.get(this, store);
        inspect();
    }

    private void setInteractive(boolean screenOn) {
        if (interactive == screenOn) return;
        interactive = screenOn;
        store.update(false);
        foreground = null;
        if (!screenOn) {
            pauseRequestedFor = null;
            store.clearParentSettings();
            removeOverlay();
        } else {
            relockAfterWake();
        }
    }

    private void relockAfterWake() {
        store.clearParentSettings();
        if (store.enabled() && store.clock.rules.relockOnWake) {
            store.update(false);
            if (store.clock.gate() == WatchClock.Gate.OPEN) Log.i("StudyTV", "Viewing round relocked on wake");
            store.clock.relock(false);
        }
    }

    private void restoreHomeAfterStr() {
        if (!power.isInteractive()) return;
        Log.i("StudyTV", "STR wake: guard=" + store.clock.gate() + ", homeResumed=" + homeResumed);
        if (homeResumed) return;
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo selected = getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        if (selected != null && getPackageName().equals(selected.activityInfo.packageName)) {
            home.setClass(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(home);
        }
    }

    private void requestInspection() {
        if (inspectionQueued) return;
        inspectionQueued = true;
        handler.postDelayed(inspection, 50);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (store != null && store.enabled()) requestInspection();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean home = code == KeyEvent.KEYCODE_HOME
                || (xiaomi6129 && code == KeyEvent.KEYCODE_ALL_APPS);
        if (!home && code != KeyEvent.KEYCODE_APP_SWITCH) return false;

        // Keep the down, repeats and up together even if the parent disables the guard mid-press.
        long key = ((long) event.getDeviceId() << 32) | code;
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            if (store == null || !store.enabled() || !power.isInteractive()) {
                capturedKeys.delete(key);
                return false;
            }
            capturedKeys.put(key, event.getDownTime());
            return true;
        }
        Long downTime = capturedKeys.get(key);
        if (downTime == null || downTime != event.getDownTime()) return false;
        if (event.getAction() == KeyEvent.ACTION_UP) {
            capturedKeys.delete(key);
            if (home && !event.isCanceled()) handler.post(this::openHomeFromKey);
        }
        return true;
    }

    private void openHomeFromKey() {
        if (!store.enabled() || !power.isInteractive()) return;
        // Open the selected launcher directly so two Home presses cannot become a system shortcut.
        startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void inspect() {
        handler.removeCallbacks(heartbeat);
        setInteractive(power.isInteractive());
        if (!interactive) return;
        if (!store.enabled()) {
            pauseRequestedFor = null;
            store.update(false);
            removeOverlay();
            handler.postDelayed(heartbeat, 1000);
            return;
        }

        String top = homeResumed ? getPackageName() : foregroundPackage();
        if (top != null) {
            foreground = top;
            if (!getPackageName().equals(top) && !store.isParentSettings(top)) store.clearParentSettings();
        }
        boolean externalApp = foreground != null && !getPackageName().equals(foreground)
                && !"com.mitv.screensaver".equals(foreground);
        boolean guarded = externalApp && !store.isParentSettings(foreground);
        boolean settingsLocked = guarded && store.isSettings(foreground);
        store.update(guarded && !settingsLocked);
        WatchClock.Gate gate = store.clock.gate();
        if (gate == WatchClock.Gate.PIN_REST) {
            store.clearParentSettings();
            guarded = externalApp;
            settingsLocked = false;
        }
        boolean pauseVideo = guarded && !settingsLocked && gate.pausesVideo();
        if (pauseVideo) requestPlaybackPause();
        else pauseRequestedFor = null;
        if (settingsLocked || (guarded && gate != WatchClock.Gate.OPEN)) {
            if (overlay != null && overlayIsSettings != settingsLocked) removeOverlay();
            if (overlay == null) showOverlay(settingsLocked, pauseVideo);
            if (overlay != null) {
                overlayWallpaper.setVideoPaused(pauseVideo);
                overlay.refresh();
            }
        } else {
            removeOverlay();
        }
        handler.postDelayed(heartbeat, 1000);
    }

    private void requestPlaybackPause() {
        long now = SystemClock.uptimeMillis();
        // Retry if a player starts or keeps playing audio behind the overlay.
        if (foreground.equals(pauseRequestedFor)
                && (now - lastPauseRequest < PAUSE_RETRY_MS || !audio.isMusicActive())) return;
        pauseRequestedFor = foreground;
        lastPauseRequest = now;
        try {
            audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0));
            audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0));
        } catch (SecurityException e) {
            Log.w("StudyTV", "Unable to dispatch media pause", e);
        }
    }

    private String foregroundPackage() {
        String top = null;
        int topLayer = Integer.MIN_VALUE;
        java.util.List<AccessibilityWindowInfo> applicationWindows = getWindows();
        try {
            for (AccessibilityWindowInfo window : applicationWindows) {
                if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                String packageName = packageName(window.getRoot());
                if (packageName == null) continue;
                if (window.isFocused() || window.isActive()) return packageName;
                if (window.getLayer() > topLayer) {
                    top = packageName;
                    topLayer = window.getLayer();
                }
            }
            return top == null && overlay == null ? packageName(getRootInActiveWindow()) : top;
        } finally {
            for (AccessibilityWindowInfo window : applicationWindows) window.recycle();
        }
    }

    private static String packageName(AccessibilityNodeInfo root) {
        if (root == null) return null;
        CharSequence name = root.getPackageName();
        root.recycle();
        return name == null ? null : name.toString();
    }

    private void showOverlay(boolean settingsLocked, boolean pauseVideo) {
        GatePanel panel = new GatePanel(this, store, this::openParent, this::removeOverlay, settingsLocked);
        Wallpaper wallpaper = new Wallpaper(this, store, true);
        wallpaper.setVideoPaused(pauseVideo);
        panel.addView(wallpaper, 0, new FrameLayout.LayoutParams(TvUi.MATCH, TvUi.MATCH));
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                TvUi.MATCH, TvUi.MATCH, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.OPAQUE);
        params.setTitle("StudyTV quiz");
        if (Build.VERSION.SDK_INT >= 28) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        TvUi.immersive(panel);
        try {
            windows.addView(panel, params);
            overlay = panel;
            overlayWallpaper = wallpaper;
            overlayIsSettings = settingsLocked;
        } catch (WindowManager.BadTokenException | SecurityException e) {
            Log.w("StudyTV", "Unable to show quiz overlay", e);
        }
    }

    private void openParent() {
        Intent intent = new Intent(this, HomeActivity.class).setAction(HomeActivity.ACTION_PARENT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        requestInspection();
    }

    private void removeOverlay() {
        if (overlay == null) return;
        windows.removeViewImmediate(overlay);
        overlay = null;
        overlayWallpaper = null;
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (receiverRegistered) unregisterReceiver(powerEvents);
        removeOverlay();
        if (store != null && connected == this) {
            store.clearParentSettings();
            store.update(false);
            store.clock.relock(false);
        }
        if (connected == this) connected = null;
        super.onDestroy();
    }
}
