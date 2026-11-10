package tv.study.guard;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public final class HomeActivity extends Activity {
    static final String ACTION_PARENT = "tv.study.guard.action.PARENT";
    private static final long SETTINGS_RETURN_DELAY_MS = 750;
    private static final long SETTINGS_LAUNCH_TIMEOUT_MS = 2000;
    private static final long FOCUS_RESUME_DELAY_MS = 250;
    enum Page { HOME, APPS, STUDY, PIN, PHONE_PIN, PARENT, RULES, FAVORITES, WEB, CLOUD }
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = this::tick;
    private final Runnable endSettingsOnReturn = this::endSettingsIfFocused;
    private final Runnable resumeAfterFocus = this::resumeAfterFocus;
    private final BroadcastReceiver parentIdleEvents = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            lockParentAccess();
        }
    };
    Store store;
    List<AppCatalog.Entry> apps = new ArrayList<>();
    Page page = Page.HOME;
    private GatePanel gate;
    private StudyPanel study;
    private TextView status;
    private FrameLayout screen;
    private Wallpaper wallpaper;
    private boolean active;
    private boolean xiaomi6129;
    private boolean authenticated;
    private boolean serviceWasConnected;
    private boolean requestSystemSettings;
    private ParentPanel parent;
    private WebAdmin.PhoneChallenge phoneChallenge;
    private String phonePinError;
    private final SharedPreferences.OnSharedPreferenceChangeListener desktopChanges = (prefs, key) -> {
        if (Store.FAVORITES_KEY.equals(key) && active && page == Page.HOME && gate == null) render();
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = Store.get(this);
        CloudAdmin.get(this, store);
        store.prefs.registerOnSharedPreferenceChangeListener(desktopChanges);
        parent = new ParentPanel(this);
        acceptIntent(getIntent());
        IntentFilter idleFilter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        idleFilter.addAction(Intent.ACTION_DREAMING_STARTED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(parentIdleEvents, idleFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(parentIdleEvents, idleFilter);
        }
        xiaomi6129 = Build.VERSION.SDK_INT == 28 && "com.ts.launcher".equals(getPackageName());
        if (xiaomi6129) {
            //noinspection UnspecifiedRegisterReceiverFlag
            registerReceiver(parentIdleEvents, new IntentFilter(GuardService.XIAOMI_STR_WAKE),
                    "android.permission.DEVICE_POWER", null);
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(resumeAfterFocus);
        store.prefs.unregisterOnSharedPreferenceChangeListener(desktopChanges);
        unregisterReceiver(parentIdleEvents);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        store.clearParentSettings();
        setIntent(intent);
        acceptIntent(intent);
        if (active) render();
    }

    private void acceptIntent(Intent intent) {
        parent.dismissExtraTime();
        closePhoneChallenge();
        study = null;
        phonePinError = null;
        authenticated = false;
        requestSystemSettings = false;
        page = ACTION_PARENT.equals(intent.getAction()) ? initialPinPage() : Page.HOME;
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(resumeAfterFocus);
        GuardService.setHomeResumed(true);
        active = true;
        WebAdminService.startIfConfigured(this, store);
        apps = AppCatalog.load(this);
        render();
        tick();
        scheduleSettingsReturnCheck(SETTINGS_RETURN_DELAY_MS);
    }

    @Override
    protected void onPause() {
        active = false;
        handler.removeCallbacks(endSettingsOnReturn);
        parent.dismissExtraTime();
        // Some TV screensavers cover the launcher with an Activity instead of a system dream.
        if (authenticated && !store.hasParentSettingsAccess()) lockParentAccess();
        closePhoneChallenge();
        GuardService.setHomeResumed(false);
        handler.removeCallbacks(ticker);
        if (wallpaper != null) wallpaper.setActive(false);
        super.onPause();
    }

    private void lockParentAccess() {
        if (page == Page.HOME || page == Page.APPS || page == Page.STUDY) return;
        authenticated = false;
        requestSystemSettings = false;
        store.clearParentSettings();
        show(Page.PIN);
    }

    @Override
    public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        handler.removeCallbacks(resumeAfterFocus);
        if (focused) {
            TvUi.immersive(getWindow().getDecorView());
            // Xiaomi's screensaver can return focus without resuming the launcher Activity.
            if (xiaomi6129 && !active) handler.postDelayed(resumeAfterFocus, FOCUS_RESUME_DELAY_MS);
        }
        scheduleSettingsReturnCheck(SETTINGS_RETURN_DELAY_MS);
    }

    private void resumeAfterFocus() {
        if (active || !hasWindowFocus() || isFinishing()) return;
        String action = page == Page.HOME || page == Page.APPS || page == Page.STUDY
                ? Intent.ACTION_MAIN : ACTION_PARENT;
        startActivity(new Intent(this, HomeActivity.class).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void scheduleSettingsReturnCheck(long delay) {
        handler.removeCallbacks(endSettingsOnReturn);
        // Xiaomi's settings panel can cover a resumed launcher and briefly hand focus back
        // while its menu Activity starts. End the grant only after a settled return.
        if (active && hasWindowFocus()) handler.postDelayed(endSettingsOnReturn, delay);
    }

    private void endSettingsIfFocused() {
        if (active && hasWindowFocus()) store.clearParentSettings();
    }

    @Override
    public void onBackPressed() {
        if (page == Page.RULES || page == Page.FAVORITES || page == Page.WEB || page == Page.CLOUD) {
            show(Page.PARENT);
        } else if (page == Page.STUDY) {
            study.back();
        } else if (page != Page.HOME) {
            show(Page.HOME);
        } else if (gate == null) {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo selected = getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            if (selected == null || !getPackageName().equals(selected.activityInfo.packageName)) finish();
        }
    }

    void show(Page next) {
        if (next != Page.PARENT) parent.dismissExtraTime();
        if (next != Page.STUDY) study = null;
        if (next != Page.PHONE_PIN) closePhoneChallenge();
        if (next == Page.PHONE_PIN && next != page) phonePinError = null;
        if (next == Page.HOME || next == Page.APPS) {
            authenticated = false;
            requestSystemSettings = false;
        }
        page = next;
        render();
    }

    private void manage(boolean forSystemSettings) {
        requestSystemSettings = forSystemSettings;
        if (!store.hasPin() || !authenticated) show(initialPinPage());
        else onAuthenticated();
    }

    private Page initialPinPage() {
        return store.hasPin() ? Page.PHONE_PIN : Page.PIN;
    }

    void onAuthenticated() {
        WebAdminService.startIfConfigured(this, store);
        authenticated = true;
        show(Page.PARENT);
        if (requestSystemSettings) {
            requestSystemSettings = false;
            openSettings();
        }
    }

    void openWeb() {
        if (!authenticated || !store.hasPin()) return;
        show(Page.WEB);
    }

    private void openPhonePin() {
        WebAdmin server = WebAdminService.server();
        if (server != null) {
            phoneChallenge = server.beginPhoneVerification(this::onPhoneVerified, !requestSystemSettings);
        }
    }

    void retryPhonePin() {
        if (!active || page != Page.PHONE_PIN || phoneChallenge != null) return;
        phonePinError = null;
        render();
    }

    String phonePinError() {
        return phonePinError == null ? WebAdminService.unavailableMessage() : phonePinError;
    }

    private void onPhoneVerified(WebAdmin.PhoneChallenge challenge) {
        if (active && page == Page.PHONE_PIN && phoneChallenge == challenge && challenge.isOpen()) {
            onAuthenticated();
        }
    }

    void onLocalPinVerified(View pinView) {
        if (active && page == Page.PIN && pinView.isAttachedToWindow()) onAuthenticated();
    }

    WebAdmin.PhoneChallenge phoneChallenge() {
        return phoneChallenge;
    }

    private void closePhoneChallenge() {
        if (phoneChallenge == null) return;
        phoneChallenge.close();
        phoneChallenge = null;
    }

    private boolean mustLock() {
        WatchClock.Gate current = store.clock.gate();
        return current == WatchClock.Gate.PIN_REST || (store.enabled() && current != WatchClock.Gate.OPEN);
    }

    private boolean mustReturnHome() {
        WatchClock.Gate current = store.clock.gate();
        return (page != Page.HOME && current == WatchClock.Gate.PIN_REST)
                || (page == Page.STUDY && store.enabled() && current == WatchClock.Gate.REST);
    }

    private void tick() {
        handler.removeCallbacks(ticker);
        if (!active) return;
        long delay = 1000;
        store.refresh();
        if (mustReturnHome()) {
            show(Page.HOME);
        }
        if (page == Page.HOME || page == Page.APPS) {
            if ((gate != null) != mustLock()
                    || (gate == null && store.enabled() && serviceWasConnected != GuardService.isConnected())) render();
            if (gate != null) {
                wallpaper.setVideoPaused(store.clock.gate().pausesVideo());
                gate.refresh();
            }
            if (status != null) status.setText(statusLine());
        } else if (page == Page.PHONE_PIN) {
            if (phoneChallenge != null && !phoneChallenge.isOpen()) {
                closePhoneChallenge();
                phonePinError = "验证地址已失效，请重新开启扫码。";
                render();
            } else if (phoneChallenge != null) {
                long remaining = phoneChallenge.remainingMillis();
                parent.updatePhoneCountdown(remaining);
                // Refresh at the next whole-second boundary of the challenge's clock.
                long partialSecond = remaining % 1000;
                if (remaining == 0) delay = 1;
                else if (partialSecond != 0) delay = partialSecond;
            } else if (phonePinError == null && WebAdminService.server() != null) {
                render();
            }
        } else if (page == Page.WEB) {
            parent.updateWebAddress();
        } else if (page == Page.CLOUD) {
            parent.updateCloudStatus();
        }
        handler.postDelayed(ticker, delay);
    }

    void render() {
        if (!active) return;
        store.refresh();
        if (mustReturnHome()) {
            show(Page.HOME);
            return;
        }
        if (page == Page.PHONE_PIN && phoneChallenge == null && phonePinError == null) openPhonePin();
        status = null;
        gate = null;
        serviceWasConnected = GuardService.isConnected();
        if ((page == Page.HOME || page == Page.APPS) && mustLock()) {
            page = Page.HOME;
            gate = new GatePanel(this, store, () -> manage(false), this::render, () -> show(Page.STUDY));
            setScreen(gate, true);
        } else if (page == Page.HOME || page == Page.APPS) {
            renderDesktop();
        } else if (page == Page.STUDY) {
            if (study == null) study = new StudyPanel(this);
            setScreen(study, true);
        } else {
            parent.render(page);
        }
        TvUi.immersive(getWindow().getDecorView());
    }

    private String statusLine() {
        if (!store.enabled()) return "管控未启用";
        if (!GuardService.isConnected()) return "无障碍服务未连接";
        String roundLeft = TvUi.duration(store.clock.available());
        String dailyLeft = TvUi.duration(store.clock.dailyLeft());
        if (page == Page.HOME) return "今日剩余 " + dailyLeft + "，距离下一次答题 " + roundLeft;
        return "本轮  " + roundLeft + "     今日剩余  " + dailyLeft;
    }

    private void renderDesktop() {
        boolean isHome = page == Page.HOME;
        boolean compact = compactScreen();
        LinearLayout content = screenColumn();
        status = TvUi.text(this, statusLine(), isHome ? 17 : 15, TvUi.WHITE, true);
        boolean serviceOffline = store.enabled() && !serviceWasConnected;
        if (!isHome) {
            LinearLayout header = TvUi.row(this);
            TextView brand = TvUi.text(this, "◈  学习电视", 20, TvUi.WHITE, true);
            header.addView(brand, new LinearLayout.LayoutParams(0, TvUi.WRAP, 1));
            status.setBackground(TvUi.panel(this, 20));
            status.setPadding(TvUi.dp(this, 14), TvUi.dp(this, 6), TvUi.dp(this, 14), TvUi.dp(this, 6));
            TvUi.gap(header, status, 12);
            TvUi.add(content, header, 0);

            TvUi.add(content, TvUi.text(this, "STUDY TV  /  应用库", 14, TvUi.ACCENT, true), 14);
            TvUi.add(content, TvUi.text(this, "全部应用", 38, TvUi.WHITE, true), 3);
            TvUi.add(content, TvUi.text(this, serviceOffline ? "管控服务离线。请家长检查无障碍设置。" : "选择一个应用打开",
                    17, serviceOffline ? TvUi.ACCENT : TvUi.MUTED, false), 8);
        }

        List<View> tiles = new ArrayList<>();
        if (isHome) {
            tiles.add(tile(getString(R.string.study_tile), getDrawable(R.drawable.mark), () -> show(Page.STUDY)));
            for (String key : favorites()) {
                if (Store.SETTINGS_TILE.equals(key)) {
                    tiles.add(tile("系统设置", getDrawable(android.R.drawable.ic_menu_preferences), () -> manage(true)));
                }
                else for (AppCatalog.Entry app : apps) {
                    if (app.key().equals(key)) {
                        tiles.add(appTile(app));
                        break;
                    }
                }
            }
        } else {
            for (AppCatalog.Entry app : apps) tiles.add(appTile(app));
        }
        LinearLayout dock = TvUi.column(this);
        if (isHome) {
            View spacer = new View(this);
            spacer.setMinimumHeight(TvUi.dp(this, compact ? 10 : 24));
            content.addView(spacer, new LinearLayout.LayoutParams(TvUi.MATCH, TvUi.WRAP, 1));
            dock.setBackground(TvUi.dockPanel(this));
            int padding = TvUi.dp(this, compact ? 8 : 16);
            dock.setPadding(padding, padding, padding, padding);
            dock.setClipToPadding(false);
        }
        LinearLayout actions = TvUi.row(this);
        TextView heading = isHome ? status : TvUi.text(this, "应用列表", 17, TvUi.WHITE, true);
        actions.addView(heading, new LinearLayout.LayoutParams(0, TvUi.WRAP, 1));
        Button library = TvUi.button(this, isHome ? "全部应用" : "返回桌面",
                () -> show(isHome ? Page.APPS : Page.HOME));
        TvUi.gap(actions, library, 10);
        TvUi.gap(actions, TvUi.button(this, "家长管理", () -> manage(false)), 10);
        TvUi.add(dock, actions, 0);
        if (isHome && serviceOffline) {
            TvUi.add(dock, TvUi.text(this, "请家长检查无障碍设置。", 15, TvUi.ACCENT, false), 6);
        }
        addTiles(dock, tiles, isHome);
        if (tiles.isEmpty()) {
            TvUi.add(dock, TvUi.text(this, "当前没有可打开的应用。",
                    17, TvUi.MUTED, false), 14);
        }
        TvUi.add(content, dock, isHome ? 0 : 20);
        setScreen(TvUi.scroll(this, content), !isHome);
        TvUi.focus(tiles.isEmpty() ? library : tiles.get(0));
    }

    void setScreen(View content, boolean dimmed) {
        boolean pauseVideo = content == gate && store.clock.gate().pausesVideo();
        if (screen == null) {
            screen = new FrameLayout(this);
            wallpaper = new Wallpaper(this, store, dimmed);
            wallpaper.setVideoPaused(pauseVideo);
            screen.addView(wallpaper, new FrameLayout.LayoutParams(TvUi.MATCH, TvUi.MATCH));
            setContentView(screen);
        } else {
            screen.removeViewAt(1);
            wallpaper.setDimmed(dimmed);
            wallpaper.setVideoPaused(pauseVideo);
            wallpaper.setActive(true);
        }
        screen.addView(content, new FrameLayout.LayoutParams(TvUi.MATCH, TvUi.MATCH));
    }

    LinearLayout screenColumn() {
        LinearLayout content = TvUi.column(this);
        int horizontal = TvUi.dp(this, 40);
        int vertical = TvUi.dp(this, compactScreen() ? 16 : 26);
        content.setPadding(horizontal, vertical, horizontal, vertical);
        return content;
    }

    private boolean compactScreen() {
        return getResources().getConfiguration().screenHeightDp <= 400;
    }

    private void addTiles(LinearLayout content, List<View> tiles, boolean dock) {
        int width = getResources().getDisplayMetrics().widthPixels;
        int available = width - TvUi.dp(this, dock ? 112 : 80);
        int columns = Math.max(2, Math.min(6, available / TvUi.dp(this, dock ? 86 : 130)));
        boolean compact = compactScreen();
        int tileHeight = TvUi.dp(this, compact ? (dock ? 62 : 76) : 100);
        for (int start = 0; start < tiles.size(); start += columns) {
            LinearLayout row = TvUi.row(this);
            for (int i = 0; i < columns; i++) {
                int index = start + i;
                View tile = index < tiles.size() ? tiles.get(index) : new View(this);
                if (i > 0 && index < tiles.size()) {
                    View previous = tiles.get(index - 1);
                    previous.setNextFocusRightId(tile.getId());
                    tile.setNextFocusLeftId(previous.getId());
                }
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, tileHeight, 1);
                params.leftMargin = i == 0 ? 0 : TvUi.dp(this, 12);
                row.addView(tile, params);
            }
            TvUi.add(content, row, compact ? 8 : 12);
        }
    }

    private View appTile(AppCatalog.Entry app) {
        return tile(app.label, app.icon(getPackageManager()), () -> launch(app));
    }

    private View tile(String label, Drawable icon, Runnable action) {
        LinearLayout tile = TvUi.column(this);
        boolean compact = compactScreen();
        tile.setId(View.generateViewId());
        tile.setGravity(Gravity.CENTER);
        TvUi.control(tile, 21, 1.055f);
        tile.setFocusable(true);
        tile.setContentDescription(label);
        tile.setPadding(TvUi.dp(this, 8), 0, TvUi.dp(this, 8), 0);
        if (icon != null) {
            ImageView image = new ImageView(this);
            image.setImageDrawable(icon);
            int size = TvUi.dp(this, compact ? 35 : 44);
            tile.addView(image, new LinearLayout.LayoutParams(size, size));
        }
        TextView title = TvUi.text(this, label, compact ? 14 : 17, TvUi.WHITE, true);
        title.setDuplicateParentStateEnabled(true);
        title.setTextColor(TvUi.focusText());
        title.setMaxLines(compact ? 1 : 2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setGravity(Gravity.CENTER);
        TvUi.add(tile, title, icon == null ? 0 : 6);
        tile.setOnClickListener(view -> action.run());
        return tile;
    }

    private void launch(AppCatalog.Entry app) {
        store.refresh();
        if (mustLock()) {
            show(Page.HOME);
            return;
        }
        if (store.enabled() && !GuardService.isConnected()) {
            message("请家长先重新启用观看管控服务");
            return;
        }
        if (store.enabled() && store.isSettings(app.key())) {
            manage(true);
            return;
        }
        try {
            startActivity(app.intent());
        } catch (ActivityNotFoundException | SecurityException e) {
            message("无法打开「" + app.label + "」");
        }
    }

    List<String> favorites() {
        return store.favorites(apps);
    }

    void openSettings() {
        if (startSettings(new Intent(Settings.ACTION_SETTINGS))) return;
        if (startSettings(new Intent(Store.XIAOMI_SETTINGS))) return;
        message("电视没有提供此设置入口，可使用 ADB 配置");
    }

    private boolean startSettings(Intent intent) {
        ResolveInfo target = getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
        if (target == null) return false;
        store.allowParentSettings(target.activityInfo.packageName);
        scheduleSettingsReturnCheck(SETTINGS_LAUNCH_TIMEOUT_MS);
        try {
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            store.clearParentSettings();
            return false;
        }
    }

    void message(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }
}
