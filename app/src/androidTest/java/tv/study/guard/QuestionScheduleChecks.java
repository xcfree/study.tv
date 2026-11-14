package tv.study.guard;

import android.content.Context;
import android.content.ContextWrapper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class QuestionScheduleChecks {
    static void run(Context context) throws Exception {
        File directory = new File(context.getCacheDir(), "question-schedule-test-" + System.nanoTime());
        check(directory.mkdirs(), "Create isolated bank directory");
        Context isolated = new ContextWrapper(context) {
            @Override public File getFilesDir() { return directory; }
        };
        try {
            QuestionBanks banks = new QuestionBanks(isolated);
            install(isolated, banks, "语文", 3, null, 0);
            install(isolated, banks, "英语", 4, null, 0);
            String chinese = QuestionBanks.subjectSource("语文");
            String english = QuestionBanks.subjectSource("英语");
            List<List<String>> week = new ArrayList<>(Collections.nCopies(7, null));
            week.set(0, Arrays.asList(english));
            week.set(6, Arrays.asList(QuestionBanks.BUILTIN));
            QuestionSchedule plan = new QuestionSchedule(Arrays.asList(chinese, english), week);
            plan = QuestionSchedule.parse(plan.json());
            plan.validate(banks.builtin, banks.subjects(), 4);
            check(plan.minimumCapacity(banks) == 4, "Rules respect the smallest weekday pool");
            check(banks.count(plan.defaults) == 7, "Selected subjects combine their question counts");
            check(banks.count(Arrays.asList(chinese, QuestionBanks.BUILTIN)) == 13, "Built-in combines with an imported subject");

            Calendar date = Calendar.getInstance();
            date.set(2026, Calendar.NOVEMBER, 9, 12, 0); // Monday
            for (int day = 0; day < 7; day++) {
                check(QuestionSchedule.weekday(date) == day, "Monday-first weekday mapping " + day);
                List<String> expected = day == 0 ? week.get(0) : day == 6 ? week.get(6) : plan.defaults;
                check(plan.sources(date).equals(expected), "Weekday override or default inheritance " + day);
                date.add(Calendar.DAY_OF_YEAR, 1);
            }
            check(QuestionSchedule.single(chinese).sources(date).equals(Arrays.asList(chinese)), "Legacy single-source migration");
            check(QuestionSchedule.single(QuestionBanks.MIXED).minimumCapacity(banks) == 10, "Legacy mixed capacity keeps built-in fallback");

            QuizRound round = new QuizRound(banks, plan.defaults, 7);
            Set<String> seen = new HashSet<>();
            Set<String> subjects = new HashSet<>();
            for (int i = 0; i < 7; i++) {
                check(seen.add(round.question.prompt), "No repeated question before selected pools are exhausted");
                subjects.add(round.currentBank().name);
                if (i < 6) round.advance();
            }
            check(subjects.equals(new HashSet<>(Arrays.asList("语文", "英语"))), "Draws from both selected subjects");

            QuestionSchedule saved = plan;
            expectFailure(() -> install(isolated, banks, "英语", 1, saved, 4), "周一");
            check(banks.count(Arrays.asList(english)) == 4, "Invalid replacement leaves original bank readable");
            QuestionSchedule afterDelete = plan.without(english);
            expectFailure(() -> banks.delete("英语", available -> afterDelete.validate(banks.builtin, available, 4)), "默认科目");
            check(banks.hasSource(english), "Invalid deletion leaves bank installed");
            banks.delete("英语", available -> afterDelete.validate(banks.builtin, available, 3));
            check(afterDelete.defaults.equals(Arrays.asList(chinese)), "Deletion removes only the deleted subject");
            check(afterDelete.week.get(0).equals(Arrays.asList(QuestionBanks.MIXED)), "Empty weekday falls back to all imports");
            check(new QuestionBanks(isolated).count(Arrays.asList(chinese)) == 3, "Bank changes survive reload");

            expectBadJson(new JSONObject().put("default", new JSONArray()).put("week", plan.json().getJSONArray("week")));
            expectBadJson(new JSONObject().put("default", new JSONArray().put(chinese).put(chinese)).put("week", plan.json().getJSONArray("week")));
            expectBadJson(new JSONObject().put("default", new JSONArray().put("").put(chinese)).put("week", plan.json().getJSONArray("week")));
            expectBadJson(new JSONObject().put("default", new JSONArray().put(chinese)).put("week", new JSONArray()));
            expectFailure(() -> QuestionSchedule.single("subject:missing").validate(banks.builtin, banks.subjects(), 1), "科目已删除");
        } finally {
            File[] files = directory.listFiles();
            if (files != null) for (File file : files) file.delete();
            directory.delete();
        }
    }

    private static void install(Context context, QuestionBanks banks, String subject, int count,
            QuestionSchedule plan, int required) throws Exception {
        JSONArray items = new JSONArray();
        for (int i = 0; i < count; i++) items.put(new JSONObject().put("subject", subject).put("question", subject + i)
                .put("options", new JSONArray().put("正确").put("错误")).put("answer", 0));
        try (QuestionBanks.PreparedImport prepared = QuestionBanks.prepareImport(context, "默认学科",
                new ByteArrayInputStream(items.toString().getBytes(StandardCharsets.UTF_8)))) {
            banks.install(prepared, available -> { if (plan != null) plan.validate(banks.builtin, available, required); });
        }
    }

    interface Action { void run() throws Exception; }

    private static void expectFailure(Action action, String message) throws Exception {
        try { action.run(); } catch (IOException e) { check(e.getMessage().contains(message), e.getMessage()); return; }
        throw new AssertionError("Expected failure: " + message);
    }

    private static void expectBadJson(JSONObject json) throws JSONException {
        try { QuestionSchedule.parse(json); } catch (JSONException expected) { return; }
        throw new AssertionError("Invalid schedule must be rejected");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
