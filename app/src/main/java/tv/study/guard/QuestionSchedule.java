package tv.study.guard;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/** Monday-first overrides; null weekdays inherit the default selection. */
final class QuestionSchedule {
    static final String[] DAYS = {"默认科目", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};
    final List<String> defaults;
    final List<List<String>> week;

    QuestionSchedule(List<String> defaults, List<List<String>> week) {
        this.defaults = Collections.unmodifiableList(new ArrayList<>(defaults));
        List<List<String>> copy = new ArrayList<>();
        for (List<String> day : week) copy.add(day == null ? null
                : Collections.unmodifiableList(new ArrayList<>(day)));
        this.week = Collections.unmodifiableList(copy);
    }

    static QuestionSchedule single(String source) {
        return new QuestionSchedule(Collections.singletonList(source), Collections.nCopies(7, null));
    }

    static QuestionSchedule parse(JSONObject json) throws JSONException {
        List<String> defaults = selection(json.getJSONArray("default"), DAYS[0]);
        JSONArray days = json.getJSONArray("week");
        if (days.length() != 7) throw new JSONException("请完整设置周一到周日的科目");
        List<List<String>> week = new ArrayList<>();
        for (int i = 0; i < 7; i++) week.add(days.isNull(i) ? null : selection(days.getJSONArray(i), DAYS[i + 1]));
        return new QuestionSchedule(defaults, week);
    }

    private static List<String> selection(JSONArray values, String day) throws JSONException {
        if (values.length() < 1 || values.length() > QuestionBanks.MAX_SUBJECTS + 1) {
            throw new JSONException(day + "：请选择 1–" + (QuestionBanks.MAX_SUBJECTS + 1) + " 个出题科目");
        }
        List<String> sources = new ArrayList<>();
        for (int i = 0; i < values.length(); i++) {
            Object value = values.get(i);
            if (!(value instanceof String) || sources.contains(value)) throw new JSONException(day + "：出题科目格式不正确或重复");
            sources.add((String) value);
        }
        if (sources.size() > 1 && sources.contains(QuestionBanks.MIXED)) {
            throw new JSONException(day + "：全部已导入学科不能与单独科目同时选择");
        }
        return sources;
    }

    JSONObject json() {
        try {
            JSONArray days = new JSONArray();
            for (List<String> day : week) days.put(day == null ? JSONObject.NULL : new JSONArray(day));
            return new JSONObject().put("default", new JSONArray(defaults)).put("week", days);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    static int weekday(Calendar date) {
        return (date.get(Calendar.DAY_OF_WEEK) + 5) % 7;
    }

    List<String> sources(Calendar date) {
        List<String> day = week.get(weekday(date));
        return day == null ? defaults : day;
    }

    void validate(QuestionBanks.Bank builtin, List<QuestionBanks.Bank> available, int required) throws IOException {
        validateSelection(defaults, builtin, available, required, DAYS[0]);
        for (int i = 0; i < 7; i++) {
            if (week.get(i) != null) validateSelection(week.get(i), builtin, available, required, DAYS[i + 1]);
        }
    }

    private static void validateSelection(List<String> sources, QuestionBanks.Bank builtin,
            List<QuestionBanks.Bank> available, int required, String day) throws IOException {
        HashSet<String> known = new HashSet<>();
        known.add(QuestionBanks.MIXED);
        known.add(QuestionBanks.BUILTIN);
        for (QuestionBanks.Bank bank : available) known.add(QuestionBanks.subjectSource(bank.name));
        if (!known.containsAll(sources)) throw new IOException(day + "：科目已删除，请刷新后重新选择");
        int count = capacity(sources, builtin, available);
        if (count < required) throw new IOException(day + "：所选科目共 " + count + " 道题，至少需要 "
                + required + " 道，请增加科目或调低每轮题数");
    }

    static int capacity(List<String> sources, QuestionBanks.Bank builtin, List<QuestionBanks.Bank> available) {
        int count = 0;
        if (sources.contains(QuestionBanks.BUILTIN)) count += builtin.size;
        for (QuestionBanks.Bank bank : available) {
            if (sources.contains(QuestionBanks.MIXED) || sources.contains(QuestionBanks.subjectSource(bank.name))) {
                count += bank.size;
            }
        }
        return sources.contains(QuestionBanks.MIXED) ? Math.max(count, builtin.size) : count;
    }

    int minimumCapacity(QuestionBanks banks) {
        int size = capacity(defaults, banks.builtin, banks.subjects());
        for (List<String> day : week) {
            if (day != null) size = Math.min(size, capacity(day, banks.builtin, banks.subjects()));
        }
        return size;
    }

    QuestionSchedule withoutMissing(QuestionBanks banks) {
        List<List<String>> days = new ArrayList<>();
        for (List<String> day : week) days.add(day == null ? null : available(day, banks));
        return new QuestionSchedule(available(defaults, banks), days);
    }

    private static List<String> available(List<String> sources, QuestionBanks banks) {
        List<String> result = new ArrayList<>();
        for (String source : sources) if (banks.hasSource(source)) result.add(source);
        return result.isEmpty() ? Collections.singletonList(QuestionBanks.MIXED) : result;
    }

    QuestionSchedule without(String source) {
        List<List<String>> days = new ArrayList<>();
        for (List<String> day : week) days.add(day == null ? null : remove(day, source));
        return new QuestionSchedule(remove(defaults, source), days);
    }

    private static List<String> remove(List<String> sources, String source) {
        List<String> result = new ArrayList<>(sources);
        result.remove(source);
        return result.isEmpty() ? Collections.singletonList(QuestionBanks.MIXED) : result;
    }
}
