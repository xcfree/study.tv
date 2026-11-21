package tv.study.guard;

import android.content.Context;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

final class GatePanel extends FrameLayout {
    private String displayedSource = "";
    private final Store store;
    private final Runnable onParent, onUnlocked, onStudy;
    private final LinearLayout content;
    private final ScrollView scroll;
    private TextView countdown;
    private WatchClock.Gate displayed;
    private LocalContent.Question displayedQuestion;
    private QuizRound.Result displayedResult;
    private int day;
    private boolean firstDone;
    private final boolean settingsLocked;

    GatePanel(Context context, Store store, Runnable onParent, Runnable onUnlocked, Runnable onStudy) {
        this(context, store, onParent, onUnlocked, false, onStudy);
    }

    GatePanel(Context context, Store store, Runnable onParent, Runnable onUnlocked,
              boolean settingsLocked, Runnable onStudy) {
        super(context);
        this.settingsLocked = settingsLocked;
        this.store = store;
        this.onParent = onParent;
        this.onUnlocked = onUnlocked;
        this.onStudy = onStudy;
        content = TvUi.panelColumn(context);
        content.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout holder = TvUi.column(context);
        holder.setGravity(Gravity.CENTER);
        int margin = TvUi.dp(context, 22);
        holder.setPadding(margin, margin, margin, margin);
        TvUi.add(holder, content, 0);
        int width = Math.min(TvUi.dp(context, 784), context.getResources().getDisplayMetrics().widthPixels);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, TvUi.MATCH, Gravity.CENTER);
        scroll = TvUi.scroll(context, holder);
        addView(scroll, params);
        if (settingsLocked) {
            reset("学习电视  /  系统设置", "请输入家长 PIN", "系统设置由家长管理。");
            actions(null);
        } else {
            refresh();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        return event.getKeyCode() == KeyEvent.KEYCODE_BACK || super.dispatchKeyEvent(event);
    }

    void refresh() {
        if (settingsLocked) return;
        WatchClock.Gate gate = store.clock.gate();
        QuizRound round = store.quiz();
        boolean showQuiz = round != null && (gate == WatchClock.Gate.QUIZ
                || (gate == WatchClock.Gate.REST && round.result == QuizRound.Result.REST));
        LocalContent.Question question = showQuiz ? round.question : null;
        QuizRound.Result result = showQuiz ? round.result : null;
        String source = gate == WatchClock.Gate.QUIZ && question == null ? store.questionSourceLabel() : "";
        if (gate != displayed || day != store.clock.day || firstDone != store.clock.firstDone
                || displayedQuestion != question || displayedResult != result || !source.equals(displayedSource)) {
            displayedSource = source;
            displayed = gate;
            day = store.clock.day;
            firstDone = store.clock.firstDone;
            displayedQuestion = question;
            displayedResult = result;
            if (question == null) showIntro();
            else showQuestion(round, question);
        }
        if (countdown != null) {
            countdown.setText(getContext().getString(R.string.rest_countdown,
                    TvUi.duration(store.clock.restLeft(SystemClock.elapsedRealtime()))));
        }
    }

    private void reset(String section, String title, String description) {
        reset(section, title, description, null);
    }

    private void reset(String section, String title, String description, String subject) {
        countdown = null;
        content.removeAllViews();
        scroll.scrollTo(0, 0);
        TvUi.add(content, subject == null ? TvUi.text(getContext(), section, 15, TvUi.ACCENT, true)
                : TvUi.questionHeader(getContext(), section, subject), 0);
        TvUi.add(content, TvUi.text(getContext(), title, 34, TvUi.WHITE, true), 8);
        TvUi.add(content, TvUi.text(getContext(), description, 19, TvUi.MUTED, false), 10);
    }

    private void actions(Button primary) {
        LinearLayout row = TvUi.row(getContext());
        if (primary != null) TvUi.gap(row, primary, 0);
        Button study = null;
        if (!settingsLocked && onStudy != null && (displayed == WatchClock.Gate.QUIZ
                || displayed == WatchClock.Gate.DAILY || displayed == WatchClock.Gate.REST)) {
            Context context = getContext();
            study = TvUi.button(context, context.getString(R.string.study_tile), onStudy);
            TvUi.gap(row, study, primary == null ? 0 : 12);
        }
        Button parent = TvUi.button(getContext(), "家长管理", onParent);
        TvUi.gap(row, parent, row.getChildCount() == 0 ? 0 : 12);
        TvUi.add(content, row, 20);
        TvUi.focus(primary != null ? primary : study != null ? study : parent);
    }

    private void showIntro() {
        switch (displayed) {
            case QUIZ:
                int questions = store.clock.firstDone ? store.clock.rules.nextQuestions : store.clock.rules.firstQuestions;
                long reward = (store.clock.firstDone ? store.clock.rules.nextMinutes : store.clock.rules.firstMinutes)
                        * WatchClock.MINUTE;
                reset("学习电视  /  答题解锁", "答几道题，再看电视",
                        store.questionSourceLabel() + " · 答对 " + questions + " 道题，获得最多 "
                                + TvUi.duration(Math.min(reward, store.clock.dailyLeft())) + " 的观看时间。");
                actions(TvUi.primaryButton(getContext(), "开始答题", this::startQuiz));
                break;
            case REST:
            case PIN_REST:
                boolean pinRest = displayed == WatchClock.Gate.PIN_REST;
                String description = pinRest ? getContext().getString(R.string.pin_rest)
                        : "休息结束后可以回来继续。";
                reset("学习电视  /  休息时间", "让眼睛休息一下", description);
                addCountdown();
                if (!pinRest) actions(null);
                break;
            case DAILY:
                reset("学习电视  /  今日上限", "今天的观看时间已用完", "明天会恢复额度。家长可以用 PIN 添加临时观看时间。");
                actions(null);
                break;
            case OPEN:
                onUnlocked.run();
                break;
        }
    }

    private void startQuiz() {
        store.startQuiz();
        refresh();
    }

    private void addCountdown() {
        countdown = TvUi.text(getContext(), "", 22, TvUi.ACCENT, true);
        TvUi.add(content, countdown, 12);
    }

    private void showQuestion(QuizRound round, LocalContent.Question question) {
        Context context = getContext();
        QuizRound.Result result = round.result;
        String description = "请选择正确答案";
        if (result != null) {
            description = result == QuizRound.Result.CORRECT || result == QuizRound.Result.PASSED
                    ? "回答正确" : "回答错误。正确答案是 " + question.options.get(question.answer);
        }
        QuestionBanks.Bank bank = round.currentBank();
        String subject = bank == store.questionBanks.builtin ? QuestionBanks.BUILTIN_SUBJECT : bank.name;
        reset("答题解锁  /  已答对 " + round.correct + " / " + round.target + " 题", question.prompt, description, subject);
        if (result == null) {
            AnswerChoices choices = new AnswerChoices(context, question.options, selected -> {
                store.answerQuiz(question, selected);
                refresh();
            });
            TvUi.add(content, choices, 8);
            TvUi.focus(choices.getChildAt(0));
        } else {
            showFeedback(round, question, result);
        }
    }

    private void showFeedback(QuizRound round, LocalContent.Question question, QuizRound.Result result) {
        Context context = getContext();
        TvUi.addQuestionExplanation(content, question.explanation);
        String action = "下一题";
        switch (result) {
            case WRONG:
                String progress = context.getString(R.string.quiz_wrong_answer, round.target - round.correct);
                TvUi.add(content, TvUi.text(context, progress, 16, TvUi.MUTED, false), 12);
                break;
            case REST:
                action = "继续";
                if (displayed == WatchClock.Gate.REST) {
                    String message = context.getString(R.string.quiz_wrong_answer_rest);
                    TvUi.add(content, TvUi.text(context, message, 16, TvUi.MUTED, false), 12);
                    addCountdown();
                }
                break;
            case PASSED:
                action = "解锁观看";
                break;
            default:
                break;
        }
        Button next = TvUi.primaryButton(context, action, () -> {
            store.continueQuiz(question);
            refresh();
        });
        TvUi.add(content, next, 16);
        TvUi.focus(next);
    }
}
