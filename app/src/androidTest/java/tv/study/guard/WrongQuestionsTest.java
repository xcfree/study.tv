package tv.study.guard;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Dependency-free device regression test: ./gradlew :app:connectedDebugAndroidTest */
public final class WrongQuestionsTest extends Instrumentation {
    private Context databaseContext;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        Bundle status = new Bundle();
        status.putString("class", getClass().getName());
        status.putString("test", "wrongQuestionCollection");
        status.putInt("numtests", 1);
        status.putInt("current", 1);
        sendStatus(1, status);
        try {
            check(getContext().getPackageName().endsWith(".test"), "Use the isolated test package");
            databaseContext = new ContextWrapper(getTargetContext()) {
                @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode,
                        SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler errorHandler) {
                    return super.openOrCreateDatabase("test-" + name, mode, factory, errorHandler);
                }

                @Override public boolean deleteDatabase(String name) {
                    return super.deleteDatabase("test-" + name);
                }
            };
            databaseContext.deleteDatabase("wrong-questions.db");
            verifyCollection();
            QuestionScheduleChecks.run(getTargetContext());
            sendStatus(0, status);
            result.putString("stream", "Wrong-question and weekly multi-subject schedule checks passed.\n");
            finish(Activity.RESULT_OK, result);
        } catch (Exception | AssertionError error) {
            result.putString("stream", android.util.Log.getStackTraceString(error));
            status.putString("stack", android.util.Log.getStackTraceString(error));
            sendStatus(-2, status);
            finish(Activity.RESULT_CANCELED, result);
        } finally {
            if (databaseContext != null) databaseContext.deleteDatabase("wrong-questions.db");
        }
    }

    private void verifyCollection() throws Exception {
        LocalContent.Question original = question("1 + 1 = ?", "解析", "2", "1", "3");
        try (WrongQuestions wrong = new WrongQuestions(databaseContext)) {
            check(wrong.size() == 0 && wrong.review().bank.size == 0, "Empty collection");
            wrong.record("数学", original);
            for (int i = 0; i < 30; i++) wrong.record("数学", original.shuffle());
            check(wrong.size() == 1, "Shuffled options must not duplicate a question");
            wrong.record("数学", question(original.prompt, "更新解析", "2", "3", "1"));
            check(wrong.size() == 1, "Explanation updates keep one record");
        }
        try (WrongQuestions wrong = new WrongQuestions(databaseContext)) {
            check(wrong.size() == 1, "Collection survives closing and reopening");
            WrongQuestions.Review snapshot = wrong.review();
            LocalContent.Question saved = snapshot.bank.questionAt(0);
            check("数学".equals(snapshot.subjectAt(0)), "Original subject survives");
            check("更新解析".equals(saved.explanation), "Latest explanation survives");
            check("2".equals(saved.options.get(saved.answer)), "Correct answer survives sorting");
            wrong.record("英语", original);
            wrong.record("数学", question(original.prompt, "", "1", "2", "3"));
            wrong.record("数学", question(original.prompt, "", "2", "4"));
            check(wrong.size() == 4, "Distinct subjects, answers and option sets stay separate");
            check(snapshot.bank.size == 1, "An active review is a stable snapshot");

            WrongQuestions.Review review = wrong.review();
            QuizRound round = new QuizRound(review.bank);
            Set<Integer> seen = new HashSet<>();
            while (round.nextPracticeQuestion()) {
                check(seen.add(round.currentQuestionIndex()), "Review each question once");
                check(!review.subjectAt(round.currentQuestionIndex()).isEmpty(), "Review keeps subject mapping");
                LocalContent.Question source = review.bank.questionAt(round.currentQuestionIndex());
                check(source.options.get(source.answer).equals(round.question.options.get(round.question.answer)),
                        "Review shuffling preserves the correct answer");
                wrong.record(review.subjectAt(round.currentQuestionIndex()), round.question);
            }
            check(seen.size() == 4 && wrong.size() == 4, "Repeated mistakes during review stay deduplicated");
        }
    }

    private static LocalContent.Question question(String prompt, String explanation, String... options) {
        return new LocalContent.Question(prompt, Arrays.asList(options), 0, explanation);
    }

    private static void check(boolean passed, String message) {
        if (!passed) throw new AssertionError(message);
    }
}
