package tv.study.guard;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Owns question snapshots so replacing or deleting a source bank keeps reviews usable. */
final class WrongQuestions extends SQLiteOpenHelper {
    static final String SUBJECT = "错题集";

    WrongQuestions(Context context) {
        super(context, "wrong-questions.db", null, 1);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE questions (identity TEXT PRIMARY KEY, subject TEXT NOT NULL, "
                + "prompt TEXT NOT NULL, options TEXT NOT NULL, answer INTEGER NOT NULL, "
                + "explanation TEXT NOT NULL)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Version 1 is the first wrong-question collection.
    }

    void record(String subject, LocalContent.Question question) {
        // Option order changes on every draw; identify the answer by its text instead of its index.
        List<String> options = new ArrayList<>(question.options);
        Collections.sort(options);
        String answer = question.options.get(question.answer);
        JSONArray choices = new JSONArray(options);
        String identity = new JSONArray().put(subject).put(question.prompt).put(choices).put(answer).toString();
        ContentValues values = new ContentValues();
        values.put("identity", identity);
        values.put("subject", subject);
        values.put("prompt", question.prompt);
        values.put("options", choices.toString());
        values.put("answer", options.indexOf(answer));
        values.put("explanation", question.explanation);
        getWritableDatabase().insertWithOnConflict("questions", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    int size() {
        return (int) DatabaseUtils.queryNumEntries(getReadableDatabase(), "questions");
    }

    Review review() {
        List<LocalContent.Question> questions = new ArrayList<>();
        List<String> subjects = new ArrayList<>();
        try (Cursor rows = getReadableDatabase().query("questions",
                new String[]{"subject", "prompt", "options", "answer", "explanation"},
                null, null, null, null, "rowid DESC")) {
            while (rows.moveToNext()) {
                try {
                    JSONArray choices = new JSONArray(rows.getString(2));
                    List<String> options = new ArrayList<>();
                    for (int i = 0; i < choices.length(); i++) options.add(choices.getString(i));
                    int answer = rows.getInt(3);
                    if (options.size() < 2 || answer < 0 || answer >= options.size()) continue;
                    questions.add(new LocalContent.Question(rows.getString(1), options, answer, rows.getString(4)));
                    subjects.add(rows.getString(0));
                } catch (JSONException ignored) {
                    // A damaged record must not prevent reviewing the remaining questions.
                }
            }
        }
        return new Review(new QuestionBanks.Bank(SUBJECT, questions), subjects);
    }

    static final class Review {
        final QuestionBanks.Bank bank;
        private final List<String> subjects;

        Review(QuestionBanks.Bank bank, List<String> subjects) {
            this.bank = bank;
            this.subjects = subjects;
        }

        String subjectAt(int index) {
            return subjects.get(index);
        }
    }
}
