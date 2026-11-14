package tv.study.guard;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

final class Store {
    private static Store instance;
    static final String XIAOMI_SETTINGS = "com.xiaomi.mitv.settings.SETTINGS";
    static final String SETTINGS_TILE = "@settings";
    static final String WALLPAPER_REVISION = "wallpaper_revision";
    static final String FAVORITES_KEY = "favorites";
    static final int MIN_EXTRA_MINUTES = 1;
    static final int DEFAULT_EXTRA_MINUTES = 15;
    static final int MAX_EXTRA_MINUTES = 60;
    private static final int PIN_HASH_BITS = 256;
    private static final int PIN_CHECK_BITS = 160;
    private static final int PIN_FAILURE_LIMIT = 3;
    private static final long PIN_REST_DURATION = 5 * WatchClock.MINUTE;
    private static final String HISTORY_START = "watch_history_start";
    private static final String HISTORY_DAY = "watch_history_";
    private static final String QUESTION_SOURCE = "question_source";
    private static final String QUESTION_SCHEDULE = "question_schedule";
    static final int[] DEFAULTS = {5, 30, 3, 15, 60, 30, 5};
    static final String[] RULE_LABELS = {"首次答题题数", "首次奖励分钟", "续看答题题数",
            "续看奖励分钟", "每日上限分钟", "连续观看上限分钟", "休息分钟"};
    static final int[] RULE_LIMITS = {20, 180, 20, 180, 720, 180, 60};
    final SharedPreferences prefs;
    final WatchClock clock;
    final QuestionBanks questionBanks;
    final QuizHistory quizHistory;
    final WrongQuestions wrongQuestions;
    private QuizRound quiz;
    private final ContentResolver resolver;
    private final Set<String> settingsPackages = new HashSet<>();
    private String parentSettingsPackage;
    private final Object pinLock = new Object();
    private long pinRetryElapsed;

    static Store get(Context context) {
        if (instance == null) instance = new Store(context.getApplicationContext());
        return instance;
    }

    private Store(Context context) {
        LocalContent.discardInterruptedImports(context);
        questionBanks = new QuestionBanks(context);
        resolver = context.getContentResolver();
        for (String action : new String[]{Settings.ACTION_SETTINGS, Settings.ACTION_ACCESSIBILITY_SETTINGS,
                Settings.ACTION_HOME_SETTINGS, XIAOMI_SETTINGS}) {
            ResolveInfo target = context.getPackageManager().resolveActivity(new Intent(action),
                    PackageManager.MATCH_DEFAULT_ONLY);
            if (target != null && !"android".equals(target.activityInfo.packageName)) {
                settingsPackages.add(target.activityInfo.packageName);
            }
        }
        prefs = context.getSharedPreferences("study_tv", Context.MODE_PRIVATE);
        quizHistory = new QuizHistory(prefs);
        wrongQuestions = new WrongQuestions(context);
        int today = ViewingDay.key(Calendar.getInstance());
        if (!prefs.contains(HISTORY_START)) prefs.edit().putInt(HISTORY_START, today).apply();
        clock = new WatchClock(readRules(), prefs.getInt("day", today),
                prefs.getBoolean("first_done", false), prefs.getLong("used", 0),
                prefs.getLong("continuous", 0), prefs.getLong("extra", 0),
                prefs.getLong("rest_wall", 0), SystemClock.elapsedRealtime(), System.currentTimeMillis());
        syncBoot(false);
        refresh();
    }

    void syncBoot(boolean fromBroadcast) {
        int boot = Settings.Global.getInt(resolver, "boot_count", -1);
        if (boot >= 0 && boot != prefs.getInt("boot_count", -1)) {
            prefs.edit().putInt("boot_count", boot).apply();
            clock.relock(true);
        } else if (boot < 0 && fromBroadcast) {
            clock.relock(true);
        }
        saveClock();
    }

    void update(boolean viewing) {
        long now = SystemClock.elapsedRealtime();
        long wall = System.currentTimeMillis();
        Calendar date = Calendar.getInstance();
        date.setTimeInMillis(wall);
        int today = ViewingDay.key(date);
        int previousDay = clock.day;
        long dayEnd = today > previousDay ? now + ViewingDay.endOf(previousDay, date) - wall : now;
        long archived = clock.update(now, wall, today, dayEnd, viewing && enabled(), pinRetryDeadline());
        if (quiz != null && (clock.day != previousDay || clock.gate() != WatchClock.Gate.QUIZ)) quiz = null;
        saveClock(previousDay, archived);
    }

    void refresh() {
        update(clock.isViewing());
    }

    void saveClock() {
        saveClock(0, 0);
    }

    private void saveClock(int previousDay, long archived) {
        if (!clock.dirty) return;
        SharedPreferences.Editor edit = prefs.edit()
                .putInt("day", clock.day).putBoolean("first_done", clock.firstDone)
                .putLong("used", clock.used).putLong("continuous", clock.continuous)
                .putLong("extra", clock.extra).putLong("rest_wall", clock.restWall);
        // Persist the archive and the reset together so a restart cannot lose the old total.
        if (archived > 0) edit.putLong(HISTORY_DAY + previousDay, archived);
        edit.apply();
        clock.dirty = false;
    }

    List<ViewingDay> viewingHistory() {
        Calendar date = Calendar.getInstance();
        int today = ViewingDay.key(date);
        int start = prefs.getInt(HISTORY_START, today);
        int weekday = (date.get(Calendar.DAY_OF_WEEK) + 5) % 7;
        int daysBeforeToday = (ViewingDay.WEEKS - 1) * 7 + weekday;
        date.add(Calendar.DAY_OF_YEAR, -daysBeforeToday);
        List<ViewingDay> history = new ArrayList<>(daysBeforeToday + 1);
        for (int i = 0; i <= daysBeforeToday; i++) {
            int day = ViewingDay.key(date);
            long watched = day == clock.day ? clock.used
                    : prefs.getLong(HISTORY_DAY + day, day >= start ? 0 : -1);
            history.add(new ViewingDay(date, watched));
            date.add(Calendar.DAY_OF_YEAR, 1);
        }
        return history;
    }

    boolean enabled() {
        return prefs.getBoolean("enabled", false);
    }

    void setEnabled(boolean enabled) {
        update(false);
        quiz = null;
        clock.relock(enabled);
        saveClock();
        prefs.edit().putBoolean("enabled", enabled).apply();
    }

    int[] readRuleValues() {
        int[] values = DEFAULTS.clone();
        for (int i = 0; i < values.length; i++) values[i] = prefs.getInt("rule_" + i, values[i]);
        return values;
    }

    private WatchClock.Rules readRules() {
        int[] v = readRuleValues();
        return new WatchClock.Rules(v[0], v[1], v[2], v[3], v[4], v[5], v[6],
                prefs.getBoolean("relock_wake", true));
    }

    void setRules(int[] values, boolean relockOnWake) {
        refresh();
        SharedPreferences.Editor edit = prefs.edit().putBoolean("relock_wake", relockOnWake);
        for (int i = 0; i < values.length; i++) edit.putInt("rule_" + i, values[i]);
        edit.apply();
        clock.rules = readRules();
        quiz = null;
        refresh();
    }

    QuizRound quiz() {
        return quiz;
    }

    void startQuiz() {
        refresh();
        if (!enabled() || clock.gate() != WatchClock.Gate.QUIZ || quiz != null) return;
        int target = clock.firstDone ? clock.rules.nextQuestions : clock.rules.firstQuestions;
        quiz = new QuizRound(questionBanks, questionSources(), target);
    }

    boolean answerQuiz(LocalContent.Question question, int selected) {
        refresh();
        if (quiz == null || quiz.question != question || selected < 0 || selected >= question.options.size()) {
            return false;
        }
        boolean wrongAnswer = selected != question.answer;
        recordAnswer(quiz.currentBank(), question, !wrongAnswer);
        if (!wrongAnswer) {
            if (++quiz.correct >= quiz.target) {
                clock.passQuiz();
                quiz = null;
                saveClock();
                return false;
            }
        } else if (quiz.recordWrongAnswer()) {
            clock.restAfterQuiz(SystemClock.elapsedRealtime(), System.currentTimeMillis());
            quiz = null;
            saveClock();
            return true;
        }
        quiz.advance();
        return wrongAnswer;
    }

    void recordAnswer(QuestionBanks.Bank bank, LocalContent.Question question, boolean correct) {
        String subject = bank == questionBanks.builtin ? QuestionBanks.BUILTIN_SUBJECT : bank.name;
        recordAnswer(subject, question, correct);
    }

    void recordAnswer(String subject, LocalContent.Question question, boolean correct) {
        quizHistory.record(Calendar.getInstance(), subject, correct);
        if (!correct) wrongQuestions.record(subject, question);
    }

    List<String> favorites(List<AppCatalog.Entry> apps) {
        List<String> result = new ArrayList<>();
        String saved = prefs.getString(FAVORITES_KEY, null);
        if (saved == null) {
            for (int i = 0; i < Math.min(6, apps.size()); i++) result.add(apps.get(i).key());
            return result;
        }
        Set<String> installed = new HashSet<>();
        for (AppCatalog.Entry app : apps) installed.add(app.key());
        try {
            JSONArray values = new JSONArray(saved);
            for (int i = 0; i < values.length(); i++) {
                String key = values.getString(i);
                if (SETTINGS_TILE.equals(key) || installed.contains(key)) result.add(key);
            }
        } catch (JSONException ignored) {
            result.clear();
        }
        return result;
    }

    void saveFavorites(List<String> keys) {
        prefs.edit().putString(FAVORITES_KEY, new JSONArray(keys).toString()).apply();
    }

    private int requiredQuestions() {
        int[] values = readRuleValues();
        return Math.max(values[0], values[2]);
    }

    String questionSource() {
        List<String> sources = questionSources();
        return sources.size() == 1 ? sources.get(0) : QuestionBanks.MIXED;
    }

    QuestionSchedule questionSchedule() {
        String stored = prefs.getString(QUESTION_SCHEDULE, null);
        if (stored != null) {
            try {
                return QuestionSchedule.parse(new JSONObject(stored)).withoutMissing(questionBanks);
            } catch (JSONException ignored) {
                // Preserve the legacy selection if a stored schedule is unreadable.
            }
        }
        return QuestionSchedule.single(prefs.getString(QUESTION_SOURCE, QuestionBanks.MIXED))
                .withoutMissing(questionBanks);
    }

    List<String> questionSources() {
        return questionSchedule().sources(Calendar.getInstance());
    }

    int questionCount() {
        return questionBanks.count(questionSources());
    }

    int questionCapacity() {
        return questionSchedule().minimumCapacity(questionBanks);
    }

    String questionSourceLabel() {
        List<QuestionBanks.Bank> banks = questionBanks.activeBanks(questionSources());
        StringBuilder label = new StringBuilder();
        for (QuestionBanks.Bank bank : banks) {
            if (label.length() > 0) label.append("、");
            label.append(bank == questionBanks.builtin ? QuestionBanks.BUILTIN_SUBJECT + "（内置）" : bank.name);
        }
        return label.toString();
    }

    void installQuestions(QuestionBanks.PreparedImport prepared) throws IOException {
        QuestionSchedule schedule = questionSchedule();
        questionBanks.install(prepared, banks -> schedule.validate(questionBanks.builtin, banks, requiredQuestions()));
        quiz = null;
    }

    void deleteQuestions(String name) throws IOException {
        QuestionSchedule next = questionSchedule().without(QuestionBanks.subjectSource(name));
        questionBanks.delete(name, banks -> next.validate(questionBanks.builtin, banks, requiredQuestions()));
        saveQuestionSchedule(next);
    }

    void selectQuestions(String source) throws IOException {
        selectQuestionSchedule(QuestionSchedule.single(source));
    }

    void selectQuestionSchedule(QuestionSchedule schedule) throws IOException {
        try {
            schedule = QuestionSchedule.parse(schedule.json());
        } catch (JSONException e) {
            throw new IOException(e.getMessage(), e);
        }
        schedule.validate(questionBanks.builtin, questionBanks.subjects(), requiredQuestions());
        saveQuestionSchedule(schedule);
    }

    private void saveQuestionSchedule(QuestionSchedule schedule) {
        prefs.edit().putString(QUESTION_SCHEDULE, schedule.json().toString()).remove(QUESTION_SOURCE).apply();
        quiz = null;
    }

    String wallpaperKind() {
        return prefs.getString("wallpaper", "");
    }

    void setWallpaper(String kind) {
        SharedPreferences.Editor edit = prefs.edit();
        if (kind.isEmpty()) edit.remove("wallpaper");
        else edit.putString("wallpaper", kind);
        // A new upload can replace the file without changing the selected media kind.
        edit.putLong(WALLPAPER_REVISION, prefs.getLong(WALLPAPER_REVISION, 0) + 1).apply();
    }

    void installContent(Context context, LocalContent.PreparedImport file) throws IOException {
        file.install(context);
        setWallpaper(file.kind.name());
    }

    void approveExtraTime(int minutes) {
        if (minutes < MIN_EXTRA_MINUTES || minutes > MAX_EXTRA_MINUTES) {
            throw new IllegalArgumentException("Extra viewing time is out of range");
        }
        refresh();
        clock.approve(minutes);
        saveClock();
        GuardService.refreshSoon();
    }

    boolean hasPin() {
        return pinVersion() != null;
    }

    String pinVersion() {
        return prefs.getString("pin_hash", null);
    }

    void setPin(char[] pin) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String encodedHash = encode(hash(pin, salt, PIN_HASH_BITS));
        synchronized (pinLock) {
            prefs.edit().putString("pin_salt", encode(salt)).putString("pin_hash", encodedHash)
                    .remove("pin_failures").apply();
        }
    }

    long pinWaitSeconds() {
        return Math.max(0, (pinRetryDeadline() - SystemClock.elapsedRealtime() + 999) / 1000);
    }

    private long pinRetryDeadline() {
        synchronized (pinLock) {
            if (pinRetryElapsed == 0) {
                long pending = prefs.getLong("pin_retry", 0) - System.currentTimeMillis();
                if (pending > 0) {
                    pinRetryElapsed = SystemClock.elapsedRealtime() + Math.min(pending, PIN_REST_DURATION);
                }
            }
            return pinRetryElapsed;
        }
    }

    boolean verifyPin(char[] pin) {
        String encodedHash;
        byte[] salt;
        synchronized (pinLock) {
            encodedHash = prefs.getString("pin_hash", null);
            if (encodedHash == null || pinWaitSeconds() > 0) return false;
            salt = Base64.decode(prefs.getString("pin_salt", ""), Base64.NO_WRAP);
        }
        byte[] expected = Base64.decode(encodedHash, Base64.NO_WRAP);
        // A PBKDF2-HMAC-SHA1 block is 160 bits. Existing 256-bit hashes start with this block.
        boolean matches = expected.length == PIN_HASH_BITS / Byte.SIZE
                && MessageDigest.isEqual(Arrays.copyOf(expected, PIN_CHECK_BITS / Byte.SIZE),
                hash(pin, salt, PIN_CHECK_BITS));
        synchronized (pinLock) {
            // A PIN change or lockout can occur while the hash is running on a web worker.
            if (!encodedHash.equals(prefs.getString("pin_hash", null)) || pinWaitSeconds() > 0) return false;
            if (matches) {
                prefs.edit().remove("pin_failures").remove("pin_retry").apply();
                pinRetryElapsed = 0;
                return true;
            }
            int failures = prefs.getInt("pin_failures", 0) + 1;
            if (failures >= PIN_FAILURE_LIMIT) {
                pinRetryElapsed = SystemClock.elapsedRealtime() + PIN_REST_DURATION;
                prefs.edit().remove("pin_failures")
                        .putLong("pin_retry", System.currentTimeMillis() + PIN_REST_DURATION).apply();
            } else {
                prefs.edit().putInt("pin_failures", failures).apply();
            }
            return false;
        }
    }

    private static String encode(byte[] value) {
        return Base64.encodeToString(value, Base64.NO_WRAP);
    }

    private static byte[] hash(char[] pin, byte[] salt, int bits) {
        PBEKeySpec key = new PBEKeySpec(pin, salt, 120_000, bits);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(key).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PIN hashing unavailable", e);
        } finally {
            key.clearPassword();
        }
    }

    void allowParentSettings(String packageName) {
        parentSettingsPackage = packageName;
    }

    void clearParentSettings() {
        parentSettingsPackage = null;
    }

    boolean hasParentSettingsAccess() {
        return parentSettingsPackage != null;
    }

    boolean isParentSettings(String packageName) {
        return parentSettingsPackage != null
                && (parentSettingsPackage.equals(packageName) || isSettings(packageName));
    }

    boolean isSettings(String packageName) {
        return settingsPackages.contains(packageName);
    }
}
