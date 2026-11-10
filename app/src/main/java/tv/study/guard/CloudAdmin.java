package tv.study.guard;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;

final class CloudAdmin {
    private static final String URL_KEY = "cloud_admin_url";
    private static final String TOKEN_KEY = "cloud_admin_device_token";
    private static final String ENABLED_KEY = "cloud_admin_enabled";
    private static final String WATCH_AUTH_KEY = "cloud_admin_watch_auth";
    private static final String TOKEN_FORMAT = "[A-Za-z0-9_-]{32,128}";
    private static CloudAdmin instance;

    private final SharedPreferences prefs;
    private final AdminControls controls;
    private final PowerManager power;
    private Config saved;
    private volatile Config config;
    private volatile String status = "等待连接";
    private Thread worker;
    private CloudWatch connection;

    static synchronized CloudAdmin get(Context context, Store store) {
        if (instance == null) instance = new CloudAdmin(context, store);
        return instance;
    }

    private CloudAdmin(Context context, Store store) {
        prefs = store.prefs;
        controls = new AdminControls(context, store);
        power = (PowerManager) context.getApplicationContext().getSystemService(Context.POWER_SERVICE);
        String url = prefs.getString(URL_KEY, "");
        String token = prefs.getString(TOKEN_KEY, "");
        if (!url.isEmpty() && token.matches(TOKEN_FORMAT)) {
            saved = new Config(url, token);
            // Existing installations enabled CloudBase by saving the connection details.
            if (prefs.getBoolean(ENABLED_KEY, true)) activate(true);
        }
    }

    synchronized String url() {
        return saved == null ? "" : saved.url;
    }

    synchronized boolean configured() {
        return saved != null;
    }

    boolean enabled() {
        return config != null;
    }

    synchronized String status() {
        if (saved == null) return "未配置";
        return enabled() ? status : "已关闭";
    }

    synchronized boolean configure(String address, String token) {
        URI uri;
        try {
            uri = URI.create(address);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("请输入完整的 HTTPS 服务地址");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || uri.getPort() == 0 || uri.getPort() > 65535
                || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
            throw new IllegalArgumentException("请输入 HTTPS 服务地址，只填域名和可选端口");
        }
        String url = "https://" + uri.getRawAuthority();
        if (token.isEmpty() && saved != null && url.equals(saved.url)) token = saved.token;
        if (!token.matches(TOKEN_FORMAT)) {
            throw new IllegalArgumentException("电视令牌须为 32–128 位字母、数字、下划线或短横线");
        }
        if (saved != null && url.equals(saved.url) && token.equals(saved.token)) return true;
        boolean wasEnabled = enabled();
        if (!prefs.edit().putString(URL_KEY, url).putString(TOKEN_KEY, token)
                .putBoolean(ENABLED_KEY, wasEnabled).remove(WATCH_AUTH_KEY).commit()) return false;
        saved = new Config(url, token);
        activate(wasEnabled);
        return true;
    }

    synchronized boolean setEnabled(boolean enabled) {
        if (enabled == enabled()) return true;
        if (enabled && saved == null) return false;
        if (!prefs.edit().putBoolean(ENABLED_KEY, enabled).commit()) return false;
        activate(enabled);
        return true;
    }

    private synchronized void activate(boolean enabled) {
        status = "等待连接";
        // Each activation has a new identity, including after a quick off/on toggle.
        config = enabled ? new Config(saved.url, saved.token) : null;
        if (connection != null) connection.close();
        connection = null;
        start();
        notifyAll();
    }

    private synchronized void start() {
        if (worker != null || config == null) return;
        worker = new Thread(this::run, "StudyTV CloudBase");
        worker.start();
    }

    private void run() {
        try {
            Config previous = null;
            CloudWatch.Auth auth = null;
            int retryMillis = 1000;
            while (true) {
                Config current = config;
                if (current == null) break;
                if (current != previous) {
                    previous = current;
                    auth = CloudWatch.Auth.restore(prefs.getString(WATCH_AUTH_KEY, ""));
                    retryMillis = 1000;
                }
                if (!power.isInteractive()) {
                    setStatus(current, "电视待机，已暂停订阅");
                    pause(current, 3000);
                    continue;
                }
                try (CloudWatch watch = openConnection(current)) {
                    if (watch == null) continue;
                    if (auth == null || auth.needsRefresh()) {
                        auth = authenticate(current, auth, watch);
                        saveAuth(current, auth);
                    }
                    watch.subscribe(auth);
                    setStatus(current, "已建立 CloudBase 实时订阅");
                    long connectedAt = SystemClock.elapsedRealtime();
                    while (config == current && power.isInteractive() && !auth.needsRefresh()) {
                        String id = watch.nextJob();
                        if (SystemClock.elapsedRealtime() - connectedAt >= 30_000) retryMillis = 1000;
                        if (id != null && config == current && power.isInteractive()) handleJob(current, id, watch);
                    }
                } catch (SetupFailure e) {
                    setStatus(current, e.getMessage());
                    pause(current, 10 * 60_000);
                } catch (IOException | JSONException e) {
                    setStatus(current, "实时订阅连接失败，正在重试");
                    pause(current, retryMillis);
                    retryMillis = Math.min(retryMillis * 2, 30_000);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (this) {
                connection = null;
                worker = null;
                start();
            }
        }
    }

    private synchronized CloudWatch openConnection(Config current) {
        if (config != current) return null;
        connection = new CloudWatch();
        return connection;
    }

    private CloudWatch.Auth authenticate(Config current, CloudWatch.Auth auth, CloudWatch watch)
            throws IOException, JSONException {
        if (auth != null) {
            try {
                return auth.refresh(watch);
            } catch (LanHttpServer.Failure e) {
                if (e.status != 400 && e.status != 401 && e.status != 403) throw e;
            }
        }
        try {
            JSONObject setup = deviceRequest(current, watch, "setup", new JSONObject());
            return new CloudWatch.Auth(setup.getString("env"), setup.getString("region"), setup);
        } catch (LanHttpServer.Failure e) {
            if (e.status == 401) throw new SetupFailure("电视令牌未通过验证");
            if (e.status == 404) throw new SetupFailure("请确认地址指向 CloudBase HTTP 云函数");
            throw new SetupFailure("电视订阅初始化失败，请检查云函数配置");
        }
    }

    private synchronized void saveAuth(Config current, CloudWatch.Auth auth) throws JSONException {
        if (config == current) prefs.edit().putString(WATCH_AUTH_KEY, auth.persisted().toString()).apply();
    }

    private void handleJob(Config current, String id, CloudWatch watch) {
        try {
            long started = SystemClock.elapsedRealtime();
            JSONObject job = deviceRequest(current, watch, "claim", new JSONObject().put("id", id));
            if (!id.equals(job.optString("id"))) return;
            JSONObject result = execute(current, job, started);
            if (config == current) deviceRequest(current, watch, "result", result);
        } catch (IOException | JSONException ignored) {
            // The cloud lease expires. A command with an uncertain outcome is never retried.
        }
    }

    private static JSONObject deviceRequest(Config current, CloudWatch watch, String action, JSONObject data)
            throws IOException, JSONException {
        return watch.post(current.url + "/relay/" + action, "X-StudyTV-Device", current.token, data);
    }

    private synchronized void setStatus(Config current, String message) {
        if (config == current) status = message;
    }

    private synchronized void pause(Config current, int millis) throws InterruptedException {
        if (config == current) wait(millis);
    }

    private JSONObject execute(Config current, JSONObject command, long claimStarted) throws JSONException {
        String id = command.getString("id");
        String path = command.getString("path");
        long deadline = claimStarted + Math.min(10_000, command.getLong("leaseMillis"));
        JSONObject result = new JSONObject().put("id", id);
        try {
            JSONObject response = controls.onMain(() -> {
                if (config != current || !power.isInteractive() || SystemClock.elapsedRealtime() >= deadline) {
                    throw new LanHttpServer.Failure(503, "电视已离线或操作已过期");
                }
                controls.requirePinReady();
                return controls.manage(path, command.getJSONObject("body"));
            });
            return result.put("status", 200).put("data", response);
        } catch (LanHttpServer.Failure e) {
            return result.put("status", e.status).put("message", e.getMessage());
        } catch (JSONException e) {
            return result.put("status", 400).put("message", "设置格式不正确");
        } catch (IOException e) {
            return result.put("status", 503).put("message", "电视无法处理该请求");
        }
    }

    private static final class Config {
        final String url;
        final String token;

        Config(String url, String token) {
            this.url = url;
            this.token = token;
        }
    }

    private static final class SetupFailure extends IOException {
        SetupFailure(String message) {
            super(message);
        }
    }
}
