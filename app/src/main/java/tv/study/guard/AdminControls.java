package tv.study.guard;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class AdminControls {
    private final Context context;
    private final Store store;
    private final Handler main = new Handler(Looper.getMainLooper());

    AdminControls(Context context, Store store) {
        this.context = context.getApplicationContext();
        this.store = store;
    }

    void requirePinReady() throws LanHttpServer.Failure {
        long wait = store.pinWaitSeconds();
        if (wait > 0) throw error(429, context.getString(R.string.pin_retry, wait));
    }

    JSONObject manage(String path, JSONObject body) throws IOException, JSONException {
        switch (path) {
            case "/api/state":
                break;
            case "/api/rules":
                saveRules(body);
                GuardService.refreshSoon();
                break;
            case "/api/guard":
                boolean enabled = readBoolean(body, "enabled");
                if (enabled != store.enabled()) {
                    if (enabled && !GuardService.isConnected()) {
                        throw error(409, "请先在电视上启用「学习电视 · 观看管控」无障碍服务");
                    }
                    store.setEnabled(enabled);
                    GuardService.refreshSoon();
                }
                break;
            case "/api/extra":
                if (!store.enabled()) throw error(409, "请先开启观看管控");
                Object minutes = body.has("minutes") ? body.get("minutes") : Store.DEFAULT_EXTRA_MINUTES;
                if (!(minutes instanceof Integer) || (int) minutes < Store.MIN_EXTRA_MINUTES
                        || (int) minutes > Store.MAX_EXTRA_MINUTES) {
                    throw error(400, "加时须为 " + Store.MIN_EXTRA_MINUTES + "–" + Store.MAX_EXTRA_MINUTES + " 分钟");
                }
                store.approveExtraTime((int) minutes);
                break;
            case "/api/favorites":
                saveFavorites(body.getJSONArray("favorites"));
                break;
            case "/api/wallpaper":
                saveWallpaper(body.getString("wallpaper"));
                break;
            case "/api/questions/reset":
            case "/api/questions/source":
                try {
                    store.selectQuestions("/api/questions/reset".equals(path)
                            ? QuestionBanks.BUILTIN : body.getString("source"));
                } catch (IOException e) {
                    throw error(409, e.getMessage());
                }
                break;
            case "/api/questions/delete":
                try {
                    store.deleteQuestions(body.getString("subject"));
                } catch (IOException e) {
                    throw error(409, e.getMessage());
                }
                break;
            default:
                throw error(404, "设置入口不存在");
        }
        return state();
    }

    JSONObject state() throws JSONException {
        store.refresh();
        int bankSize = store.questionCapacity();
        String source = store.questionSource();
        JSONArray subjects = new JSONArray();
        for (QuestionBanks.Bank bank : store.questionBanks.subjects()) {
            subjects.put(new JSONObject().put("name", bank.name).put("count", bank.size)
                    .put("source", QuestionBanks.subjectSource(bank.name)));
        }
        int[] values = store.readRuleValues();
        JSONArray rules = new JSONArray();
        for (int i = 0; i < values.length; i++) {
            rules.put(new JSONObject().put("label", Store.RULE_LABELS[i]).put("value", values[i])
                    .put("max", ruleLimit(i, bankSize)));
        }
        List<AppCatalog.Entry> apps = AppCatalog.load(context);
        List<String> favorites = store.favorites(apps);
        JSONArray catalog = new JSONArray().put(appItem(Store.SETTINGS_TILE, "系统设置"));
        for (AppCatalog.Entry app : apps) {
            if (!store.isSettings(app.key()) || favorites.contains(app.key())) {
                catalog.put(appItem(app.key(), app.label));
            }
        }
        JSONArray viewingHistory = new JSONArray();
        for (ViewingDay day : store.viewingHistory()) {
            viewingHistory.put(new JSONObject().put("date", day.date).put("level", day.level())
                    .put("description", day.description()));
        }
        return new JSONObject().put("enabled", store.enabled()).put("connected", GuardService.isConnected())
                .put("extraMax", Store.MAX_EXTRA_MINUTES)
                .put("used", TvUi.duration(store.clock.used)).put("available", TvUi.duration(store.clock.available()))
                .put("viewingHistory", viewingHistory)
                .put("quizHistory", quizHistory())
                .put("dailyLeft", TvUi.duration(store.clock.dailyLeft())).put("rules", rules)
                .put("relockOnWake", store.clock.rules.relockOnWake)
                .put("questionCount", store.questionBanks.count(source))
                .put("customQuestions", !QuestionBanks.BUILTIN.equals(source) && subjects.length() > 0)
                .put("questionSource", source).put("questionSubjects", subjects)
                .put("builtinQuestionCount", store.questionBanks.builtin.size)
                .put("questionUploadLimitMb", QuestionBanks.UPLOAD_LIMIT_MB)
                .put("wallpaper", store.wallpaperKind())
                .put("hasImage", LocalContent.file(context, LocalContent.Kind.IMAGE).isFile())
                .put("hasVideo", LocalContent.file(context, LocalContent.Kind.VIDEO).isFile())
                .put("studyTile", context.getString(R.string.study_tile))
                .put("favorites", new JSONArray(favorites)).put("apps", catalog);
    }

    private JSONArray quizHistory() throws JSONException {
        JSONArray history = new JSONArray();
        for (QuizHistory.Day day : store.quizHistory.recentDays()) {
            JSONArray subjects = new JSONArray();
            for (QuizHistory.Subject subject : day.subjects) {
                subjects.put(new JSONObject().put("name", subject.name).put("total", subject.total)
                        .put("correct", subject.correct).put("accuracy", subject.accuracy()));
            }
            history.put(new JSONObject().put("date", day.date).put("subjects", subjects));
        }
        return history;
    }

    private static JSONObject appItem(String key, String label) throws JSONException {
        return new JSONObject().put("key", key).put("label", label);
    }

    private void saveRules(JSONObject body) throws JSONException, LanHttpServer.Failure {
        JSONArray values = body.getJSONArray("values");
        int[] rules = new int[Store.DEFAULTS.length];
        int bankSize = store.questionCapacity();
        if (values.length() != rules.length) throw error(400, "观看规则不完整");
        for (int i = 0; i < rules.length; i++) {
            int limit = ruleLimit(i, bankSize);
            Object value = values.get(i);
            if (!(value instanceof Integer) || (int) value < 1 || (int) value > limit) {
                throw error(400, Store.RULE_LABELS[i] + "须为 1–" + limit + " 的整数");
            }
            rules[i] = (int) value;
        }
        store.setRules(rules, readBoolean(body, "relockOnWake"));
    }

    private static int ruleLimit(int index, int bankSize) {
        return index == 0 || index == 2 ? Math.min(bankSize, Store.RULE_LIMITS[index]) : Store.RULE_LIMITS[index];
    }

    private void saveFavorites(JSONArray favorites) throws JSONException, LanHttpServer.Failure {
        List<AppCatalog.Entry> apps = AppCatalog.load(context);
        List<String> previous = store.favorites(apps);
        Set<String> available = new HashSet<>();
        available.add(Store.SETTINGS_TILE);
        for (AppCatalog.Entry app : apps) {
            if (!store.isSettings(app.key()) || previous.contains(app.key())) available.add(app.key());
        }
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < favorites.length(); i++) {
            String key = favorites.getString(i);
            if (!available.remove(key)) throw error(400, "应用重复或已卸载，请刷新页面");
            keys.add(key);
        }
        store.saveFavorites(keys);
    }

    private void saveWallpaper(String name) throws LanHttpServer.Failure {
        if (name.isEmpty()) {
            store.setWallpaper("");
            return;
        }
        LocalContent.Kind kind = "IMAGE".equals(name) ? LocalContent.Kind.IMAGE : LocalContent.Kind.VIDEO;
        if (!kind.name().equals(name) || !LocalContent.file(context, kind).isFile()) {
            throw error(400, "请先上传对应的背景文件");
        }
        store.setWallpaper(name);
    }

    private static boolean readBoolean(JSONObject body, String name) throws JSONException {
        Object value = body.get(name);
        if (!(value instanceof Boolean)) throw new JSONException("开关值不正确");
        return (boolean) value;
    }

    private static LanHttpServer.Failure error(int status, String message) {
        return new LanHttpServer.Failure(status, message);
    }

    JSONObject onMain(Callable<JSONObject> action) throws IOException, JSONException {
        return onMain(action, 5);
    }

    JSONObject onMain(Callable<JSONObject> action, long timeoutSeconds) throws IOException, JSONException {
        FutureTask<JSONObject> task = new FutureTask<>(action);
        main.post(task);
        try {
            return task.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            task.cancel(false);
            Thread.currentThread().interrupt();
            throw error(503, "网页请求已中断");
        } catch (TimeoutException e) {
            task.cancel(false);
            throw error(503, "电视界面响应超时，请稍后重试");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) throw (IOException) cause;
            if (cause instanceof JSONException) throw (JSONException) cause;
            throw error(503, "电视无法处理该请求");
        }
    }
}
