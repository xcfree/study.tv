package tv.study.guard;

import android.annotation.SuppressLint;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

@SuppressLint("ViewConstructor") // The launcher creates this panel with its active store.
final class StudyPanel extends FrameLayout {
    private final HomeActivity home;
    private final QuestionBanks banks;
    private final LinearLayout content;
    private final ScrollView scroll;
    private QuestionBanks.Bank bank;
    private QuizRound round;
    private int answered;
    private boolean showingFeedback;

    StudyPanel(HomeActivity home) {
        super(home);
        this.home = home;
        banks = home.store.questionBanks;
        content = home.screenColumn();
        scroll = TvUi.scroll(home, content);
        int width = Math.min(TvUi.dp(home, 784), home.getResources().getDisplayMetrics().widthPixels);
        addView(scroll, new LayoutParams(width, TvUi.MATCH, Gravity.CENTER));
        showSubjects("选择一个科目，开始答题。");
    }

    void back() {
        if (bank == null) home.show(HomeActivity.Page.HOME);
        else showSubjects("请选择要练习的科目。");
    }

    private void showSubjects(String description) {
        bank = null;
        round = null;
        reset(home.getString(R.string.study_tile), "选择科目", description, false);
        Button first = null;
        for (QuestionBanks.Bank entry : banks.practiceBanks()) {
            Button subject = TvUi.button(home, label(entry) + "  ·  " + entry.size + " 道题", () -> begin(entry));
            subject.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            TvUi.add(content, subject, 8);
            if (first == null) first = subject;
        }
        TvUi.add(content, TvUi.button(home, "返回桌面", this::back), 16);
        TvUi.focus(first);
    }

    private void begin(QuestionBanks.Bank entry) {
        bank = entry;
        round = new QuizRound(entry);
        answered = 0;
        next();
    }

    private void next() {
        showingFeedback = false;
        if (answered == bank.size) {
            reset(label(bank), "练习完成", "答对 " + round.correct + " / " + answered + " 道题", false);
            Button subjects = TvUi.primaryButton(home, "选择科目", this::back);
            TvUi.add(content, subjects, 16);
            TvUi.focus(subjects);
            return;
        }
        if (!hasCurrentBank()) return;
        if (!round.nextPracticeQuestion()) {
            showSubjects("暂时无法读取该题库，请选择其他科目。");
            return;
        }
        LocalContent.Question question = round.question;
        LinearLayout panel = reset(progress(answered + 1), question.prompt, "请选择正确答案", true);
        AnswerChoices choices = new AnswerChoices(home, question.options, selected -> answer(question, selected));
        TvUi.add(panel, choices, 8);
        TvUi.focus(choices.getChildAt(0));
    }

    private void answer(LocalContent.Question question, int selected) {
        if (round == null || round.question != question || showingFeedback || !hasCurrentBank()) return;
        showingFeedback = true;
        answered++;
        boolean correct = selected == question.answer;
        if (correct) round.correct++;
        home.store.recordAnswer(bank, correct);
        String feedback = correct ? "回答正确" : "回答错误。正确答案是 " + question.options.get(question.answer);
        LinearLayout panel = reset(progress(answered), question.prompt, feedback, true);
        TvUi.add(panel, TvUi.text(home, "题目解析", 16, TvUi.ACCENT, true), 16);
        String explanation = question.explanation.isEmpty() ? "这道题暂无解析。" : question.explanation;
        TvUi.add(panel, TvUi.text(home, explanation, 19, TvUi.WHITE, false), 6);
        Button next = TvUi.primaryButton(home, answered == bank.size ? "查看结果" : "下一题", () -> {
            if (showingFeedback && round != null && round.question == question) next();
        });
        TvUi.add(panel, next, 16);
        TvUi.focus(next);
    }

    private boolean hasCurrentBank() {
        if (banks.contains(bank)) return true;
        showSubjects("题库已更新，请重新选择科目。");
        return false;
    }

    private String label(QuestionBanks.Bank entry) {
        return entry == banks.builtin ? QuestionBanks.BUILTIN_SUBJECT + "（内置题库）" : entry.name;
    }

    private String progress(int number) {
        return label(bank) + "  /  第 " + number + " / " + bank.size + " 题";
    }

    private LinearLayout reset(String section, String title, String description, boolean inPanel) {
        content.removeAllViews();
        scroll.scrollTo(0, 0);
        LinearLayout column = content;
        if (inPanel) {
            column = TvUi.panelColumn(home);
            TvUi.add(content, column, 0);
        }
        TvUi.add(column, TvUi.text(home, section, 15, TvUi.ACCENT, true), 0);
        TvUi.add(column, TvUi.text(home, title, 34, TvUi.WHITE, true), 8);
        TvUi.add(column, TvUi.text(home, description, 19, TvUi.MUTED, false), 10);
        return column;
    }
}
