package tv.study.guard;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

final class QuizHistory {
    private static final String DAY_PREFIX = "quiz_history_";
    private static final int RECENT_DAYS = 30;
    private final SharedPreferences prefs;

    QuizHistory(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    void record(Calendar date, String subject, boolean correct) {
        String key = DAY_PREFIX + ViewingDay.key(date);
        JSONObject counts = read(key);
        JSONArray previous = counts.optJSONArray(subject);
        long total = previous == null ? 0 : previous.optLong(0);
        long right = previous == null ? 0 : previous.optLong(1);
        try {
            counts.put(subject, new JSONArray().put(total + 1).put(right + (correct ? 1 : 0)));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        prefs.edit().putString(key, counts.toString()).apply();
    }

    List<Day> recentDays() {
        Calendar date = Calendar.getInstance();
        Collator collator = Collator.getInstance(Locale.SIMPLIFIED_CHINESE);
        List<Day> days = new ArrayList<>(RECENT_DAYS);
        for (int i = 0; i < RECENT_DAYS; i++) {
            JSONObject counts = read(DAY_PREFIX + ViewingDay.key(date));
            List<String> names = new ArrayList<>();
            for (Iterator<String> keys = counts.keys(); keys.hasNext(); ) names.add(keys.next());
            Collections.sort(names, (first, second) -> {
                int priority = Integer.compare(
                        QuestionBanks.subjectPriority(first), QuestionBanks.subjectPriority(second));
                if (priority != 0) return priority;
                int byName = collator.compare(first, second);
                return byName == 0 ? first.compareTo(second) : byName;
            });
            List<Subject> subjects = new ArrayList<>(names.size());
            for (String name : names) {
                JSONArray row = counts.optJSONArray(name);
                if (row != null && row.optLong(0) > 0) {
                    subjects.add(new Subject(name, row.optLong(0), row.optLong(1)));
                }
            }
            days.add(new Day(ViewingDay.date(date), subjects));
            date.add(Calendar.DAY_OF_YEAR, -1);
        }
        return days;
    }

    private JSONObject read(String key) {
        String stored = prefs.getString(key, null);
        if (stored != null) {
            try {
                return new JSONObject(stored);
            } catch (JSONException ignored) {
                // An unreadable day does not prevent future answers from being recorded.
            }
        }
        return new JSONObject();
    }

    static final class Day {
        final String date;
        final List<Subject> subjects;

        Day(String date, List<Subject> subjects) {
            this.date = date;
            this.subjects = subjects;
        }
    }

    static final class Subject {
        final String name;
        final long total;
        final long correct;

        Subject(String name, long total, long correct) {
            this.name = name;
            this.total = total;
            this.correct = correct;
        }

        String accuracy() {
            return String.format(Locale.ROOT, "%.1f%%", 100.0 * correct / total);
        }
    }
}
