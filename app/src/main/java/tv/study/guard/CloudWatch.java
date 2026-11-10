package tv.study.guard;

import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

final class CloudWatch implements AutoCloseable {
    private static final String COLLECTION = "studytv_remote";
    private static final String SDK_VERSION = "@cloudbase/js-sdk/3.11.0";
    private static final int MAX_RESPONSE = 128 * 1024;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build();
    private static final String CLOSED = "";
    private static final String OPEN = "{}";

    private final ArrayBlockingQueue<String> frames = new ArrayBlockingQueue<>(32);
    private final ArrayDeque<String> jobs = new ArrayDeque<>();
    private final String watchId = "watchid_" + UUID.randomUUID();
    private boolean closed;
    private Call call;
    private WebSocket socket;
    private Object queryId;
    private long eventId;
    private long lastFrame;
    private long lastHeartbeat;

    void subscribe(Auth auth) throws IOException, JSONException, InterruptedException {
        // Same sign, login and watch messages as CloudBase's document-database Web SDK.
        JSONObject sign = auth.sign(this);
        String url = sign.optString("wsUrl", "");
        if (url.isEmpty()) url = "wss://tcb-ws.tencentcloudapi.com";
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid CloudBase WebSocket URL", e);
        }
        String host = uri.getHost();
        if (host == null || !"wss".equals(uri.getScheme()) || uri.getRawUserInfo() != null
                || !(host.endsWith(".tencentcloudapi.com") || host.endsWith(".tcloudbase.com")
                    || host.endsWith(".tcloudbasegateway.com"))) {
            throw new IOException("Unexpected CloudBase WebSocket host");
        }
        openSocket(url);
        if (!OPEN.equals(frames.poll(8, TimeUnit.SECONDS))) throw new IOException("CloudBase connection failed");

        JSONObject login = new JSONObject().put("envId", auth.env).put("accessToken", "")
                .put("referrer", "web").put("sdkVersion", "").put("dataVersion", "");
        JSONObject signature = new JSONObject().put("runtime", "web").put("signStr", sign.getString("signStr"))
                .put("secretVersion", sign.opt("secretVersion"));
        await(send("LOGIN", login, signature));

        JSONObject filter = new JSONObject().put("_id", "tv").put("phase", "queued");
        JSONObject query = new JSONObject().put("envId", auth.env).put("collName", COLLECTION)
                .put("query", filter.toString()).put("limit", 1).put("orderBy", new JSONArray());
        JSONObject init = await(send("INIT_WATCH", query, null));
        if (!"INIT_EVENT".equals(init.optString("msgType"))) throw new IOException("CloudBase watch not initialized");
        JSONObject data = init.getJSONObject("msgData");
        queryId = data.get("queryID");
        receiveEvents(data);
        lastHeartbeat = lastFrame = SystemClock.elapsedRealtime();
    }

    private synchronized void openSocket(String url) throws IOException {
        requireOpen();
        socket = HTTP.newWebSocket(new Request.Builder().url(url).build(), new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {
                frames.offer(OPEN);
            }

            @Override public void onMessage(WebSocket webSocket, String text) {
                if (text.length() > 512 * 1024 || !frames.offer(text)) {
                    webSocket.cancel();
                    end();
                }
            }

            @Override public void onClosing(WebSocket webSocket, int code, String reason) {
                webSocket.cancel();
                end();
            }

            @Override public void onFailure(WebSocket webSocket, Throwable error, Response response) {
                end();
            }

            private void end() {
                frames.clear();
                frames.offer(CLOSED);
            }
        });
    }

    String nextJob() throws IOException, JSONException, InterruptedException {
        if (!jobs.isEmpty()) return jobs.removeFirst();
        JSONObject message = receive(3000);
        long now = SystemClock.elapsedRealtime();
        if (now - lastFrame > 25_000) throw new IOException("CloudBase heartbeat timed out");
        if (now - lastHeartbeat >= 10_000) {
            send("PING", null, null);
            send("CHECK_LAST", new JSONObject().put("queryID", queryId).put("eventID", eventId), null);
            lastHeartbeat = now;
        }
        if (message == null) return null;
        JSONObject data = message.optJSONObject("msgData");
        switch (message.optString("msgType")) {
            case "NEXT_EVENT":
                if (data != null) receiveEvents(data);
                break;
            case "CHECK_EVENT":
                if (data != null && data.optLong("currEvent", eventId) > eventId) {
                    throw new IOException("CloudBase watch missed an event");
                }
                break;
        }
        return jobs.pollFirst();
    }

    private void receiveEvents(JSONObject data) throws JSONException {
        eventId = data.getLong("currEvent");
        JSONArray events = data.getJSONArray("events");
        for (int i = 0; i < events.length(); i++) {
            JSONObject change = events.getJSONObject(i);
            String payload = change.optString("Doc");
            if (payload.isEmpty() || "{}".equals(payload)) continue;
            JSONObject document = new JSONObject(payload);
            String id = document.optString("id");
            if ("tv".equals(document.optString("_id")) && "queued".equals(document.optString("phase"))
                    && id.matches("[0-9a-f-]{36}") && !jobs.contains(id)) jobs.add(id);
        }
    }

    private JSONObject await(String requestId) throws IOException, JSONException, InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + 8000;
        long remaining;
        while ((remaining = deadline - SystemClock.elapsedRealtime()) > 0) {
            JSONObject message = receive(remaining);
            if (message != null && requestId.equals(message.optString("requestId"))) return message;
        }
        throw new IOException("CloudBase subscription timed out");
    }

    private JSONObject receive(long timeout) throws IOException, JSONException, InterruptedException {
        String frame = frames.poll(timeout, TimeUnit.MILLISECONDS);
        if (frame == null) return null;
        if (CLOSED.equals(frame)) throw new IOException("CloudBase subscription closed");
        lastFrame = SystemClock.elapsedRealtime();
        JSONObject message = new JSONObject(frame);
        if (hasError(message)) throw new IOException("CloudBase subscription rejected");
        return message;
    }

    private static boolean hasError(JSONObject message) {
        JSONObject data = message.optJSONObject("msgData");
        String code = data == null || data.isNull("code") ? "" : data.optString("code");
        return "ERROR".equals(message.optString("msgType")) || !(code.isEmpty() || "0".equals(code));
    }

    private String send(String type, JSONObject data, JSONObject signature) throws JSONException, IOException {
        String requestId = UUID.randomUUID().toString();
        JSONObject message = new JSONObject().put("requestId", requestId).put("msgType", type)
                .put("msgData", data == null ? JSONObject.NULL : data);
        if (!"LOGIN".equals(type) && !"PING".equals(type)) message.put("watchId", watchId);
        if (signature != null) message.put("exMsgData", signature);
        if (!socket.send(message.toString())) throw new IOException("CloudBase subscription closed");
        return requestId;
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (call != null) call.cancel();
        if (socket != null) socket.cancel();
        frames.clear();
        frames.offer(CLOSED);
    }

    private void requireOpen() throws IOException {
        if (closed) throw new IOException("CloudBase connection closed");
    }

    JSONObject post(String url, String header, String value, JSONObject payload)
            throws IOException, JSONException {
        Request.Builder request = new Request.Builder().url(url).post(RequestBody.create(payload.toString(), JSON));
        if (header != null) request.header(header, value);
        Call pending = HTTP.newCall(request.build());
        synchronized (this) {
            requireOpen();
            call = pending;
        }
        try (Response response = pending.execute()) {
            if (!response.isSuccessful()) throw new LanHttpServer.Failure(response.code(), "CloudBase 请求失败");
            ResponseBody body = response.peekBody(MAX_RESPONSE + 1);
            if (body.contentLength() > MAX_RESPONSE) throw new IOException("CloudBase response too large");
            return new JSONObject(body.string());
        } finally {
            synchronized (this) {
                call = null;
            }
        }
    }

    static final class Auth {
        final String env;
        final String region;
        private final String accessToken;
        private final String refreshToken;
        private final long refreshAt;

        Auth(String env, String region, JSONObject tokens) throws JSONException {
            if (!env.matches("[a-z0-9][a-z0-9-]{1,62}") || !region.matches("[a-z0-9-]+")) {
                throw new JSONException("Invalid CloudBase environment");
            }
            this.env = env;
            this.region = region;
            accessToken = tokens.optString("access_token");
            refreshToken = tokens.getString("refresh_token");
            long lifetime = tokens.optLong("expires_in");
            if (refreshToken.isEmpty() || (!accessToken.isEmpty() && lifetime <= 60)) {
                throw new JSONException("Invalid CloudBase access or refresh token");
            }
            refreshAt = SystemClock.elapsedRealtime() + (lifetime - 60) * 1000;
        }

        static Auth restore(String data) {
            try {
                JSONObject saved = new JSONObject(data);
                return new Auth(saved.getString("env"), saved.getString("region"), saved);
            } catch (JSONException e) {
                return null;
            }
        }

        boolean needsRefresh() {
            return accessToken.isEmpty() || SystemClock.elapsedRealtime() >= refreshAt;
        }

        JSONObject persisted() throws JSONException {
            return new JSONObject().put("env", env).put("region", region).put("refresh_token", refreshToken);
        }

        Auth refresh(CloudWatch watch) throws IOException, JSONException {
            JSONObject data = new JSONObject().put("grant_type", "refresh_token").put("refresh_token", refreshToken);
            JSONObject response = watch.post("https://" + env + ".api.tcloudbasegateway.com/auth/v1/token", null, null, data);
            if (!response.has("refresh_token") || !response.has("access_token")) {
                throw new LanHttpServer.Failure(401, "CloudBase 订阅凭证已失效");
            }
            return new Auth(env, region, response);
        }

        JSONObject sign(CloudWatch watch) throws IOException, JSONException {
            String url = String.format(Locale.ROOT, "https://%s.%s.tcb-api.tencentcloudapi.com/web?env=%s",
                    env, region, env);
            JSONObject data = new JSONObject().put("action", "auth.wsWebSign").put("env", env).put("runtime", "web")
                    .put("access_token", accessToken).put("dataVersion", "2020-01-10");
            JSONObject reply = watch.post(url, "X-SDK-Version", SDK_VERSION, data);
            return reply.getJSONObject("data");
        }
    }
}
