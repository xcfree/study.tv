package tv.study.guard;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.Semaphore;

final class WebAdmin implements AutoCloseable {
    interface PhoneVerification {
        void verified(PhoneChallenge challenge);
    }

    private static final String UNLOCK_PATH = "/unlock/";
    private static final int JSON_LIMIT = 64 * 1024;
    private static final long SESSION_DURATION = 15 * WatchClock.MINUTE;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Semaphore PIN_VERIFICATION = new Semaphore(1);
    private final Context context;
    private final Store store;
    private final AdminControls controls;
    private final LanHttpServer server;
    private final String origin;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Session session;
    private volatile PhoneChallenge phoneChallenge;

    private static final class Session {
        final String token = randomToken();
        final String authorization = "Bearer " + token;
        final long deadline = SystemClock.elapsedRealtime() + SESSION_DURATION;
        final String pinVersion;

        Session(String pinVersion) {
            this.pinVersion = pinVersion;
        }
    }

    final class PhoneChallenge implements AutoCloseable {
        private final String path = UNLOCK_PATH + randomToken();
        private final long deadline = SystemClock.elapsedRealtime() + 2 * WatchClock.MINUTE;
        private final PhoneVerification callback;
        private final boolean allowManagement;
        private volatile boolean verified;

        PhoneChallenge(PhoneVerification callback, boolean allowManagement) {
            this.callback = callback;
            this.allowManagement = allowManagement;
        }

        String url() { return origin + path; }

        long remainingMillis() {
            return Math.max(0, deadline - SystemClock.elapsedRealtime());
        }

        boolean isOpen() {
            return phoneChallenge == this && WebAdmin.this.isOpen() && remainingMillis() > 0;
        }

        @Override public void close() {
            if (phoneChallenge == this) phoneChallenge = null;
        }
    }

    WebAdmin(Context context, Store store, Inet4Address address, Runnable onFailure) throws IOException {
        this.context = context.getApplicationContext();
        this.store = store;
        controls = new AdminControls(context, store);
        server = new LanHttpServer(address);
        origin = "http://" + server.host();
        server.start(this::handle, onFailure);
    }

    String url() { return origin; }

    boolean isOpen() {
        return server.isOpen();
    }

    PhoneChallenge beginPhoneVerification(PhoneVerification callback, boolean allowManagement) {
        phoneChallenge = new PhoneChallenge(callback, allowManagement);
        return phoneChallenge;
    }

    @Override public void close() {
        session = null;
        phoneChallenge = null;
        server.close();
    }

    static Inet4Address findLanAddress(ConnectivityManager networks) {
        Inet4Address address = lanAddress(networks, networks.getActiveNetwork());
        if (address != null) return address;
        for (Network network : networks.getAllNetworks()) {
            address = lanAddress(networks, network);
            if (address != null) return address;
        }
        return null;
    }

    private static Inet4Address lanAddress(ConnectivityManager networks, Network network) {
        if (network == null) return null;
        NetworkCapabilities capabilities = networks.getNetworkCapabilities(network);
        LinkProperties properties = networks.getLinkProperties(network);
        if (capabilities == null || properties == null || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                || !(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) return null;
        for (LinkAddress link : properties.getLinkAddresses()) {
            if (link.getAddress() instanceof Inet4Address && link.getAddress().isSiteLocalAddress()) {
                return (Inet4Address) link.getAddress();
            }
        }
        return null;
    }

    private void handle(LanHttpServer.Request request, OutputStream output) throws IOException {
        if (!isOpen()) throw error(403, "网页管理已关闭");
        if (!request.path.startsWith("/api/")) {
            servePage(request, output);
            return;
        }
        String origin = request.headers.get("origin");
        if (!"1".equals(request.headers.get("x-studytv")) || (origin != null && !this.origin.equals(origin))) {
            throw error(403, "请求来源无效");
        }
        try {
            JSONObject response;
            PhoneChallenge challenge = request.path.startsWith("/api" + UNLOCK_PATH)
                    ? requirePhoneChallenge(request.path.substring(4)) : null;
            if (challenge != null || "/api/login".equals(request.path)) {
                requireMethod(request, "POST");
                Object supplied = readJson(request).get("pin");
                if (!(supplied instanceof String) || !((String) supplied).matches("[0-9]{6}")) {
                    throw error(400, "请输入 6 位数字 PIN");
                }
                response = authenticate((String) supplied, challenge);
            } else {
                String credential = request.headers.get("authorization");
                requireSession(credential);
                response = request.path.startsWith("/api/import/")
                        ? importContent(request, credential) : manage(request, credential);
            }
            try {
                LanHttpServer.respond(output, 200, "application/json; charset=utf-8",
                        response.toString().getBytes(StandardCharsets.UTF_8));
                if (challenge != null) output.flush();
            } finally {
                if (challenge != null) main.post(() -> challenge.callback.verified(challenge));
            }
        } catch (LanHttpServer.Failure e) {
            throw e;
        } catch (JSONException e) {
            throw error(400, e.getMessage() == null ? "设置格式不正确" : e.getMessage());
        }
    }

    private void servePage(LanHttpServer.Request request, OutputStream output) throws IOException {
        requireMethod(request, "GET");
        String name, type;
        if (request.path.startsWith(UNLOCK_PATH)) {
            requirePhoneChallenge(request.path);
            name = "index.html"; type = "text/html";
        } else {
            switch (request.path) {
                case "/": name = "index.html"; type = "text/html"; break;
                case "/admin.css": name = "admin.css"; type = "text/css"; break;
                case "/admin.js": name = "admin.js"; type = "text/javascript"; break;
                default: throw error(404, "页面不存在");
            }
        }
        try (InputStream file = context.getAssets().open("admin/" + name)) {
            LanHttpServer.respond(output, 200, type + "; charset=utf-8", readBytes(file));
        }
    }

    private PhoneChallenge requirePhoneChallenge(String path) throws LanHttpServer.Failure {
        PhoneChallenge challenge = phoneChallenge;
        if (challenge == null || !challenge.path.equals(path) || !challenge.isOpen() || challenge.verified) {
            throw error(404, "验证地址已失效，请在电视上重新扫码");
        }
        return challenge;
    }

    private JSONObject authenticate(String supplied, PhoneChallenge challenge) throws IOException, JSONException {
        if (!isOpen()) throw error(403, "网页管理已关闭");
        if (!PIN_VERIFICATION.tryAcquire()) throw error(409, "电视正在验证 PIN，请稍候");
        try {
            if (challenge != null) requirePhoneChallenge(challenge.path);
            String pinVersion = store.pinVersion();
            verifyPin(supplied);
            return controls.onMain(() -> completeAuthentication(challenge, pinVersion), 15);
        } finally {
            PIN_VERIFICATION.release();
        }
    }

    private void verifyPin(String supplied) throws LanHttpServer.Failure {
        controls.requirePinReady();
        char[] pin = supplied.toCharArray();
        boolean verified;
        try {
            verified = store.verifyPin(pin);
        } finally {
            Arrays.fill(pin, '\0');
        }
        if (!verified) {
            long wait = store.pinWaitSeconds();
            if (wait > 0) throw error(429, context.getString(R.string.pin_throttled, wait));
            throw error(401, "家长 PIN 不正确");
        }
    }

    private JSONObject completeAuthentication(PhoneChallenge challenge, String pinVersion) throws IOException, JSONException {
        if (!isOpen()) throw error(403, "网页管理已关闭");
        controls.requirePinReady();
        if (pinVersion == null || !pinVersion.equals(store.pinVersion())) {
            throw error(401, "家长 PIN 已更改，请重新验证");
        }
        if (challenge != null) requirePhoneChallenge(challenge.path);
        if (challenge != null && !challenge.allowManagement) {
            challenge.verified = true;
            return new JSONObject();
        }
        Session next = new Session(pinVersion);
        JSONObject result = lanState(controls.state(), next).put("token", next.token);
        session = next;
        if (challenge != null) challenge.verified = true;
        return result;
    }

    private static String randomToken() {
        byte[] random = new byte[24];
        RANDOM.nextBytes(random);
        return Base64.encodeToString(random, Base64.NO_PADDING | Base64.NO_WRAP | Base64.URL_SAFE);
    }

    private void requireSession(String credential) throws LanHttpServer.Failure {
        Session current = session;
        if (!isOpen() || current == null || SystemClock.elapsedRealtime() >= current.deadline
                || !current.pinVersion.equals(store.pinVersion())
                || !current.authorization.equals(credential)) {
            throw error(401, "登录已失效，请重新输入家长 PIN");
        }
        controls.requirePinReady();
    }

    private JSONObject manage(LanHttpServer.Request request, String credential) throws IOException, JSONException {
        boolean read = "/api/state".equals(request.path);
        requireMethod(request, read ? "GET" : "POST");
        JSONObject body = read ? null : readJson(request);
        return controls.onMain(() -> {
            requireSession(credential);
            if ("/api/logout".equals(request.path)) {
                session = null;
                return new JSONObject();
            }
            if ("/api/cloud".equals(request.path)) {
                Object enabled = body.opt("enabled");
                if (!(enabled instanceof Boolean)) throw error(400, "CloudBase 开关值无效");
                CloudAdmin cloud = CloudAdmin.get(context, store);
                if ((boolean) enabled && !cloud.configured()) throw error(409, "请先在电视上保存 CloudBase 连接设置");
                if (!cloud.setEnabled((boolean) enabled)) throw error(503, "无法保存 CloudBase 开关");
                return state();
            }
            return lanState(controls.manage(request.path, body), session);
        });
    }

    private JSONObject importContent(LanHttpServer.Request request, String credential)
            throws IOException, JSONException {
        requireMethod(request, "POST");
        try {
            if ("/api/import/questions".equals(request.path)) {
                return importQuestions(request, credential);
            }
            LocalContent.Kind kind;
            switch (request.path) {
                case "/api/import/image": kind = LocalContent.Kind.IMAGE; break;
                case "/api/import/video": kind = LocalContent.Kind.VIDEO; break;
                default: throw error(404, "导入入口不存在");
            }
            try (LocalContent.PreparedImport file = LocalContent.prepareImport(context, kind, request.body(kind.limit))) {
                return controls.onMain(() -> {
                    requireSession(credential);
                    store.installContent(context, file);
                    return state();
                });
            }
        } catch (LanHttpServer.Failure e) {
            throw e;
        } catch (IOException e) {
            throw error(400, e.getMessage() == null ? "导入中断" : e.getMessage());
        }
    }

    private JSONObject importQuestions(LanHttpServer.Request request, String credential)
            throws IOException, JSONException {
        String encoded = request.headers.get("x-studytv-subject");
        String fallbackName = QuestionBanks.DEFAULT_SUBJECT;
        if (encoded != null) {
            try {
                fallbackName = QuestionBanks.validateName(URLDecoder.decode(encoded, "UTF-8"));
            } catch (IllegalArgumentException e) {
                throw error(400, "学科名称编码不正确");
            }
        }
        InputStream body = request.body(QuestionBanks.UPLOAD_LIMIT_MB * 1024L * 1024L);
        boolean checkReplacement = "0".equals(request.headers.get("x-studytv-replace-questions"));
        try (QuestionBanks.PreparedImport prepared = QuestionBanks.prepareImport(context, fallbackName, body)) {
            return controls.onMain(() -> {
                requireSession(credential);
                if (checkReplacement) {
                    for (String name : prepared.names()) {
                        if (store.questionBanks.hasSource(QuestionBanks.subjectSource(name))) {
                            throw error(409, "本次导入学科为「" + TextUtils.join("、", prepared.names())
                                    + "」。是否替换其中的同名题库？");
                        }
                    }
                }
                store.installQuestions(prepared);
                return state().put("importedQuestionSubjects", new JSONArray(prepared.names()));
            });
        }
    }

    private JSONObject state() throws JSONException {
        return lanState(controls.state(), session);
    }

    private JSONObject lanState(JSONObject state, Session current) throws JSONException {
        CloudAdmin cloud = CloudAdmin.get(context, store);
        long remaining = Math.max(0, current.deadline - SystemClock.elapsedRealtime());
        return state.put("expiresIn", remaining).put("cloudBase", new JSONObject()
                .put("configured", cloud.configured()).put("enabled", cloud.enabled()).put("status", cloud.status()));
    }

    private static JSONObject readJson(LanHttpServer.Request request) throws IOException, JSONException {
        String type = request.headers.get("content-type");
        if (type == null || !"application/json".equalsIgnoreCase(type.split(";", 2)[0].trim())) {
            throw error(400, "请求需要 JSON 数据");
        }
        return new JSONObject(new String(readBytes(request.body(JSON_LIMIT)), StandardCharsets.UTF_8));
    }

    private static byte[] readBytes(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int size;
        while ((size = input.read(buffer)) != -1) output.write(buffer, 0, size);
        return output.toByteArray();
    }

    private static void requireMethod(LanHttpServer.Request request, String method) throws LanHttpServer.Failure {
        if (!method.equals(request.method)) throw error(405, "请求方式不正确");
    }

    private static LanHttpServer.Failure error(int status, String message) {
        return new LanHttpServer.Failure(status, message);
    }
}
