package tv.study.guard;

import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import io.nayuki.qrcodegen.QrCode;

final class ParentPanel {
    private final HomeActivity home;
    private final Store store;
    private LinearLayout content;
    private LinearLayout group;
    private TextView phoneCountdown;
    private TextView webAddress;
    private TextView cloudStatus;
    private Button cloudToggle;
    private Dialog extraTimeDialog;
    private boolean changingPin;
    private String favoriteKey;
    private int favoriteButton;

    ParentPanel(HomeActivity home) {
        this.home = home;
        store = home.store;
    }

    void render(HomeActivity.Page page) {
        switch (page) {
            case PIN:
                changingPin = false;
                pin(false);
                break;
            case PHONE_PIN:
                phonePin();
                break;
            case RULES:
                rules();
                break;
            case FAVORITES:
                favorites();
                break;
            case PARENT:
                dashboard();
                break;
            case WEB:
                web();
                break;
            case CLOUD:
                cloud();
                break;
            default:
                throw new IllegalArgumentException(page.name());
        }
    }

    private void startScreen() {
        content = home.screenColumn();
        group = null;
        phoneCountdown = null;
        webAddress = null;
        cloudStatus = null;
        cloudToggle = null;
        home.setScreen(TvUi.scroll(home, content), true);
    }

    private void start(String eyebrow, String title, String description) {
        startScreen();
        TvUi.add(content, TvUi.text(home, eyebrow, 16, TvUi.ACCENT, true), 0);
        TvUi.add(content, TvUi.text(home, title, 33, TvUi.WHITE, true), 8);
        TvUi.add(content, TvUi.text(home, description, 17, TvUi.MUTED, false), 8);
    }

    private LinearLayout pinLayout() {
        startScreen();
        content.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout layout = TvUi.row(home);
        TvUi.add(content, layout, 0);
        return layout;
    }

    private LinearLayout pinIntro(LinearLayout layout, String title, String description) {
        LinearLayout info = TvUi.column(home);
        layout.addView(info, new LinearLayout.LayoutParams(0, TvUi.WRAP, 1));
        TvUi.add(info, TvUi.text(home, "学习电视  /  家长管理", 16, TvUi.ACCENT, true), 0);
        TvUi.add(info, TvUi.text(home, title, 30, TvUi.WHITE, true), 8);
        TvUi.add(info, TvUi.text(home, description, 17, TvUi.MUTED, false), 8);
        return info;
    }

    private int pinKeyWidth() {
        return home.getResources().getConfiguration().screenWidthDp >= 800 ? 92 : 68;
    }

    private void pin(boolean change) {
        changingPin = change;
        boolean create = change || !store.hasPin();
        LinearLayout layout = pinLayout();
        LinearLayout info = pinIntro(layout, create ? (change ? "更改家长 PIN" : "创建家长 PIN") : "电视键盘验证",
                create ? "请设置 6 位数字，供以后管理电视使用。" : "使用遥控器方向键输入 6 位家长 PIN。");
        if (!create) {
            LinearLayout row = TvUi.row(home);
            TvUi.gap(row, TvUi.button(home, "切换到手机验证", () -> home.show(HomeActivity.Page.PHONE_PIN)), 0);
            TvUi.add(info, row, 14);
        }
        TextView dots = TvUi.text(home, "", 26, TvUi.WHITE, true);
        TextView hint = TvUi.text(home, "", 17, TvUi.ACCENT, true);
        if (Build.VERSION.SDK_INT >= 26) hint.setDefaultFocusHighlightEnabled(false);
        TvUi.add(info, dots, 14);
        TvUi.add(info, hint, 2);
        LinearLayout keypad = TvUi.column(home);
        inset(keypad);
        TvUi.gap(layout, keypad, 20);
        PinEntry entry = new PinEntry(dots, hint, keypad, create);
        int keyWidth = pinKeyWidth();
        Button first = null;
        for (int rowIndex = 0; rowIndex < 4; rowIndex++) {
            LinearLayout row = TvUi.row(home);
            for (int col = 0; col < 3; col++) {
                int key = 1 + rowIndex * 3 + col;
                Button button;
                if (key < 10) button = TvUi.button(home, String.valueOf(key), () -> entry.digit(key));
                else if (key == 10) button = TvUi.button(home, "清除", entry::clear);
                else if (key == 11) button = TvUi.button(home, "0", () -> entry.digit(0));
                else button = TvUi.button(home, "退格", entry::delete);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(TvUi.dp(home, keyWidth), TvUi.WRAP);
                params.leftMargin = TvUi.dp(home, col == 0 ? 0 : 8);
                row.addView(button, params);
                if (first == null) first = button;
            }
            TvUi.add(keypad, row, rowIndex == 0 ? 0 : 8);
        }
        entry.firstKey = first;
        group = keypad;
        actions(TvUi.primaryButton(home, "确认", entry::confirm), TvUi.button(home, "取消",
                () -> home.show(changingPin ? HomeActivity.Page.PARENT : HomeActivity.Page.HOME)));
        TvUi.focus(first);
    }

    private final class PinEntry {
        final TextView dots, hint;
        final LinearLayout keypad;
        final boolean create;
        final char[] digits = new char[6];
        Button firstKey;
        char[] initial;
        int length;
        boolean verifying;

        PinEntry(TextView dots, TextView hint, LinearLayout keypad, boolean create) {
            this.dots = dots;
            this.hint = hint;
            this.keypad = keypad;
            this.create = create;
            redraw();
        }

        void digit(int value) {
            if (verifying) return;
            if (length < digits.length) digits[length++] = (char) ('0' + value);
            if (!create && length == digits.length) confirm();
            else redraw();
        }

        void clear() {
            if (verifying) return;
            Arrays.fill(digits, '\0');
            length = 0;
            redraw();
        }

        void delete() {
            if (verifying) return;
            if (length > 0) digits[--length] = '\0';
            redraw();
        }

        void redraw() {
            StringBuilder marks = new StringBuilder();
            for (int i = 0; i < digits.length; i++) marks.append(i < length ? "●  " : "○  ");
            dots.setText(marks.toString());
            dots.setContentDescription("已输入 " + length + " 位");
            if (initial != null) hint.setText("请再输入一次确认");
        }

        void confirm() {
            if (verifying) return;
            if (length != digits.length) {
                hint.setText("请输入 6 位数字");
                return;
            }
            if (create) {
                if (initial == null) {
                    initial = digits.clone();
                    clear();
                    return;
                }
                if (!Arrays.equals(initial, digits)) {
                    Arrays.fill(initial, '\0');
                    initial = null;
                    clear();
                    hint.setText("两次输入不同，请重新设置");
                    return;
                }
                store.setPin(digits);
                Arrays.fill(initial, '\0');
                clear();
                home.onAuthenticated();
                return;
            }
            long seconds = store.pinWaitSeconds();
            if (seconds > 0) {
                hint.setText(home.getString(R.string.pin_retry, seconds));
                clear();
                focusHint();
                return;
            }
            char[] entered = digits.clone();
            clear();
            verifying = true;
            hint.setText(R.string.pin_verifying);
            keypad.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            focusHint();
            new Thread(() -> {
                boolean verified;
                try {
                    verified = store.verifyPin(entered);
                } catch (RuntimeException e) {
                    home.runOnUiThread(() -> retry(home.getString(R.string.pin_unavailable)));
                    return;
                } finally {
                    Arrays.fill(entered, '\0');
                }
                home.runOnUiThread(() -> finishVerification(verified));
            }, "StudyTV PIN verification").start();
        }

        private void finishVerification(boolean verified) {
            if (!dots.isAttachedToWindow()) return;
            if (verified) {
                home.onLocalPinVerified(dots);
                return;
            }
            long seconds = store.pinWaitSeconds();
            retry(seconds > 0 ? home.getString(R.string.pin_throttled, seconds) : "PIN 不正确，请重试");
        }

        private void focusHint() {
            View focusedKey = keypad.findFocus();
            if (focusedKey != null) focusedKey.setPressed(false);
            hint.setFocusable(true);
            hint.requestFocus();
        }

        private void retry(String message) {
            if (!dots.isAttachedToWindow()) return;
            verifying = false;
            keypad.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
            hint.setText(message);
            hint.setFocusable(false);
            TvUi.focus(firstKey);
        }
    }

    private void dashboard() {
        int totalQuestions = store.questionBanks.count(store.questionSource());
        String service = GuardService.isConnected() ? "服务已连接" : "服务未连接";
        start("学习电视  /  家长管理", "家庭设置",
                (store.enabled() ? "管控已启用" : "管控未启用") + " · " + service + " · " + totalQuestions + " 道题");
        int verticalPadding = TvUi.dp(home, 18);
        content.setPadding(content.getPaddingLeft(), verticalPadding, content.getPaddingRight(), verticalPadding);

        LinearLayout columns = TvUi.row(home);
        columns.setGravity(Gravity.TOP);
        int gap = TvUi.dp(home, 16);
        int availableWidth = home.getResources().getDisplayMetrics().widthPixels
                - content.getPaddingLeft() - content.getPaddingRight() - gap;
        int operationsWidth = availableWidth * 2 / 5;
        LinearLayout operations = TvUi.column(home);
        inset(operations);
        columns.addView(operations, new LinearLayout.LayoutParams(operationsWidth, TvUi.WRAP));

        LinearLayout history = TvUi.column(home);
        inset(history);
        LinearLayout.LayoutParams historyLayout = new LinearLayout.LayoutParams(0, TvUi.WRAP, 1);
        historyLayout.leftMargin = gap;
        columns.addView(history, historyLayout);
        TvUi.add(content, columns, 10);

        sectionTitle(operations, "观看管控", 0);
        Button extraTime = TvUi.button(home, "增加时间", this::chooseExtraTime);
        Button toggle = TvUi.primaryButton(home, store.enabled() ? "关闭管控" : "开启管控", this::toggleGuard);
        menuActions(operations, extraTime,
                TvUi.button(home, "设置规则", () -> home.show(HomeActivity.Page.RULES)),
                toggle,
                TvUi.button(home, "更改 PIN", () -> pin(true)));

        sectionTitle(operations, "桌面", 8);
        menuActions(operations, TvUi.button(home, "管理常用图标", () -> {
                    favoriteKey = null;
                    home.show(HomeActivity.Page.FAVORITES);
                }),
                TvUi.button(home, "系统设置", home::openSettings));

        sectionTitle(operations, "网页控制台", 8);
        menuActions(operations, TvUi.button(home, "局域网控制台", home::openWeb),
                TvUi.button(home, "CloudBase 控制台", () -> home.show(HomeActivity.Page.CLOUD)));

        int historyWidth = availableWidth - operationsWidth - history.getPaddingLeft() - history.getPaddingRight();
        historyTabs(history, historyWidth);
        TvUi.focus(extraTime);
    }

    private void historyTabs(LinearLayout history, int width) {
        LinearLayout tabs = TvUi.row(home);
        LinearLayout viewing = TvUi.column(home);
        LinearLayout quiz = TvUi.column(home);
        TextView viewingTab = TvUi.tab(home, "每日观看", () -> selectHistoryTab(tabs, viewing, quiz, false));
        TextView quizTab = TvUi.tab(home, "每日答题", () -> selectHistoryTab(tabs, viewing, quiz, true));
        TvUi.gap(tabs, viewingTab, 0);
        TvUi.gap(tabs, quizTab, 0);
        viewingTab.setNextFocusRightId(quizTab.getId());
        quizTab.setNextFocusLeftId(viewingTab.getId());
        TvUi.add(history, tabs, 0);
        View divider = new View(home);
        divider.setBackgroundColor(0x44dac2b1);
        history.addView(divider, new LinearLayout.LayoutParams(TvUi.MATCH, TvUi.dp(home, 1)));
        TvUi.add(history, viewing, 10);
        TvUi.add(history, quiz, 10);

        LinearLayout historyHeader = TvUi.row(home);
        historyHeader.addView(TvUi.text(home, "近 12 周", 18, TvUi.ACCENT, true),
                new LinearLayout.LayoutParams(0, TvUi.WRAP, 1));
        TvUi.gap(historyHeader, TvUi.text(home, "今日已用 " + TvUi.duration(store.clock.used), 22, TvUi.WHITE, true), 8);
        TvUi.add(viewing, historyHeader, 0);
        TvUi.add(viewing, new ViewingHistoryPanel(home, store.viewingHistory(), width), 6);

        Spinner date = quizStats(quiz);
        date.setNextFocusUpId(quizTab.getId());
        quizTab.setNextFocusDownId(date.getId());
        selectHistoryTab(tabs, viewing, quiz, false);
    }

    private void selectHistoryTab(LinearLayout tabs, View viewing, View quiz, boolean showQuiz) {
        tabs.getChildAt(0).setSelected(!showQuiz);
        tabs.getChildAt(1).setSelected(showQuiz);
        viewing.setVisibility(showQuiz ? View.GONE : View.VISIBLE);
        quiz.setVisibility(showQuiz ? View.VISIBLE : View.GONE);
    }

    private Spinner quizStats(LinearLayout quiz) {
        List<QuizHistory.Day> days = store.quizHistory.recentDays();
        List<String> labels = new ArrayList<>(days.size());
        for (QuizHistory.Day day : days) labels.add(day.date);
        labels.set(0, labels.get(0) + " · 今天");

        Spinner date = new Spinner(home, Spinner.MODE_DROPDOWN);
        date.setId(View.generateViewId());
        ArrayAdapter<String> adapter = new ArrayAdapter<>(home, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        date.setAdapter(adapter);
        TextView label = TvUi.text(home, "统计日期", 17, TvUi.MUTED, true);
        label.setLabelFor(date.getId());
        LinearLayout filters = TvUi.row(home);
        TvUi.gap(filters, label, 0);
        TvUi.gap(filters, date, 12);
        TvUi.add(quiz, filters, 0);

        LinearLayout results = TvUi.column(home);
        TvUi.add(quiz, results, 8);
        date.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                results.removeAllViews();
                List<QuizHistory.Subject> subjects = days.get(position).subjects;
                if (subjects.isEmpty()) {
                    TvUi.add(results, TvUi.text(home, "当天暂无答题记录", 17, TvUi.MUTED, false), 0);
                    return;
                }
                quizStatsRow(results, true, "学科", "总答题数", "正确题数", "正确率");
                for (QuizHistory.Subject subject : subjects) {
                    quizStatsRow(results, false, subject.name, Long.toString(subject.total),
                            Long.toString(subject.correct), subject.accuracy());
                }
            }

            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        return date;
    }

    private void quizStatsRow(LinearLayout results, boolean header, String... values) {
        LinearLayout row = TvUi.row(home);
        row.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < values.length; i++) {
            TextView cell = TvUi.text(home, values[i], header ? 15 : 17,
                    header ? TvUi.ACCENT : TvUi.WHITE, header);
            if (i == 0) cell.setPadding(0, 0, TvUi.dp(home, 6), 0);
            else cell.setGravity(Gravity.END);
            row.addView(cell, new LinearLayout.LayoutParams(0, TvUi.WRAP, i == 0 ? 1.5f : 1));
        }
        TvUi.add(results, row, header ? 0 : 8);
    }

    private void cloud() {
        CloudAdmin cloud = CloudAdmin.get(home, store);
        start("学习电视  /  家长管理", "CloudBase 控制台",
                "开启后，电视开机联网时会连接 CloudBase。家长可以用家长令牌从外网管理电视。");
        cloudStatus = TvUi.text(home, "", 16, TvUi.ACCENT, true);
        TvUi.add(content, cloudStatus, 12);
        group = content;
        cloudToggle = TvUi.button(home, "", () -> {
            if (!cloud.setEnabled(!cloud.enabled())) home.message("无法保存 CloudBase 开关");
            updateCloudStatus();
        });
        actions(cloudToggle);
        updateCloudStatus();
        EditText address = cloudField("HTTPS 服务地址", InputType.TYPE_TEXT_VARIATION_URI);
        String currentUrl = cloud.url();
        address.setText(currentUrl);
        EditText token = cloudField(currentUrl.isEmpty() ? "电视连接令牌" : "电视连接令牌（地址不变时可留空）",
                InputType.TYPE_TEXT_VARIATION_PASSWORD);
        Button save = TvUi.primaryButton(home, "保存连接设置", () -> {
            try {
                if (cloud.configure(address.getText().toString().trim(), token.getText().toString().trim())) {
                    home.render();
                } else {
                    home.message("无法保存 CloudBase 设置");
                }
            } catch (IllegalArgumentException e) {
                home.message(e.getMessage());
            }
        });
        actions(save, TvUi.button(home, "返回", () -> home.show(HomeActivity.Page.PARENT)));
        TvUi.focus(currentUrl.isEmpty() ? address : cloudToggle);
    }

    private EditText cloudField(String hint, int variation) {
        EditText field = new EditText(home);
        field.setHint(hint);
        field.setTextColor(TvUi.WHITE);
        field.setHintTextColor(TvUi.MUTED);
        field.setTextSize(17);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | variation | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        TvUi.add(content, field, 8);
        return field;
    }

    void updateCloudStatus() {
        if (cloudStatus == null) return;
        CloudAdmin cloud = CloudAdmin.get(home, store);
        cloudStatus.setText(cloud.configured() ? cloud.status() : "请先保存连接设置");
        cloudToggle.setEnabled(cloud.configured());
        cloudToggle.setText(cloud.enabled() ? "关闭 CloudBase 控制台" : "开启 CloudBase 控制台");
    }

    private void chooseExtraTime() {
        if (!store.enabled()) {
            home.message("请先开启观看管控");
            return;
        }
        if (extraTimeDialog != null) return;

        Dialog dialog = new Dialog(home);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = TvUi.column(home);
        inset(panel);
        int padding = TvUi.dp(home, 20);
        panel.setPadding(padding, padding, padding, padding);
        TvUi.add(panel, TvUi.text(home, "增加时间", 24, TvUi.WHITE, true), 0);
        TvUi.add(panel, TvUi.text(home, "按上下键选择分钟，按确认键增加。", 15, TvUi.MUTED, false), 6);

        NumberPicker picker = new NumberPicker(home);
        picker.setId(View.generateViewId());
        picker.setMinValue(Store.MIN_EXTRA_MINUTES);
        picker.setMaxValue(Store.MAX_EXTRA_MINUTES);
        picker.setWrapSelectorWheel(false);
        String[] minutes = new String[Store.MAX_EXTRA_MINUTES - Store.MIN_EXTRA_MINUTES + 1];
        for (int i = 0; i < minutes.length; i++) minutes[i] = (i + Store.MIN_EXTRA_MINUTES) + " 分钟";
        picker.setDisplayedValues(minutes);
        picker.setValue(Store.DEFAULT_EXTRA_MINUTES);
        picker.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        picker.setFocusableInTouchMode(true);

        Button confirm = TvUi.primaryButton(home, "确认增加", () -> {
            if (!dialog.isShowing() || home.page != HomeActivity.Page.PARENT) return;
            dialog.dismiss();
            if (!store.enabled()) {
                home.message("请先开启观看管控");
                return;
            }
            store.approveExtraTime(picker.getValue());
            home.show(HomeActivity.Page.HOME);
        });
        Button cancel = TvUi.button(home, "取消", dialog::cancel);
        picker.setNextFocusRightId(confirm.getId());
        confirm.setNextFocusLeftId(picker.getId());
        cancel.setNextFocusLeftId(picker.getId());
        picker.setOnKeyListener((view, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_DPAD_CENTER && keyCode != KeyEvent.KEYCODE_ENTER) return false;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) confirm.performClick();
            return true;
        });

        LinearLayout row = TvUi.row(home);
        row.addView(picker, new LinearLayout.LayoutParams(0, TvUi.WRAP, 1));
        LinearLayout buttons = TvUi.column(home);
        TvUi.add(buttons, confirm, 0);
        TvUi.add(buttons, cancel, 8);
        TvUi.gap(row, buttons, 14);
        TvUi.add(panel, row, 8);

        extraTimeDialog = dialog;
        dialog.setOnDismissListener(dismissed -> extraTimeDialog = null);
        dialog.setContentView(panel);
        dialog.show();
        Window window = dialog.getWindow();
        window.setBackgroundDrawableResource(android.R.color.transparent);
        int width = Math.min(TvUi.dp(home, 380), home.getResources().getDisplayMetrics().widthPixels - padding * 2);
        window.setLayout(width, TvUi.WRAP);
        TvUi.immersive(window.getDecorView());
        TvUi.focus(picker);
    }

    void dismissExtraTime() {
        if (extraTimeDialog != null) extraTimeDialog.dismiss();
    }

    private void phonePin() {
        LinearLayout layout = pinLayout();
        LinearLayout info = pinIntro(layout, "手机扫码验证",
                "用同一局域网内的手机扫码，输入 6 位家长 PIN 后，电视会自动继续。");
        LinearLayout mode = TvUi.row(home);
        Button keyboard = TvUi.button(home, "切换到电视键盘", () -> home.show(HomeActivity.Page.PIN));
        TvUi.gap(mode, keyboard, 0);
        TvUi.add(info, mode, 16);

        LinearLayout card = TvUi.column(home);
        card.setGravity(Gravity.CENTER);
        inset(card);
        int cardWidth = 3 * pinKeyWidth() + 40;
        card.setMinimumWidth(TvUi.dp(home, cardWidth));
        TvUi.gap(layout, card, 20);
        WebAdmin.PhoneChallenge session = home.phoneChallenge();
        if (session != null) {
            String url = session.url();
            TvUi.add(info, TvUi.text(home, "也可在手机浏览器输入地址", 15, TvUi.MUTED, false), 16);
            TvUi.add(info, TvUi.text(home, url, 16, TvUi.WHITE, true), 3);
            Bitmap bitmap = verificationQr(url, TvUi.dp(home, Math.min(232, cardWidth - 32)));
            ImageView qr = new ImageView(home);
            qr.setImageBitmap(bitmap);
            qr.setScaleType(ImageView.ScaleType.CENTER);
            qr.setContentDescription("手机验证二维码");
            card.addView(qr, new LinearLayout.LayoutParams(bitmap.getWidth(), bitmap.getHeight()));
            phoneCountdown = TvUi.text(home, "", 16, TvUi.MUTED, false);
            phoneCountdown.setGravity(Gravity.CENTER);
            updatePhoneCountdown(session.remainingMillis());
            TvUi.add(card, phoneCountdown, 10);
            TvUi.focus(keyboard);
        } else {
            TvUi.add(info, TvUi.text(home, home.phonePinError(), 16, TvUi.MUTED, false), 14);
            Button retry = TvUi.button(home, "重新开启扫码", home::retryPhonePin);
            card.addView(retry);
            TvUi.focus(retry);
        }
    }

    void updatePhoneCountdown(long remainingMillis) {
        if (phoneCountdown != null) {
            phoneCountdown.setText(home.getString(R.string.phone_pin_countdown, TvUi.duration(remainingMillis)));
        }
    }

    private static Bitmap verificationQr(String url, int maxPixels) {
        QrCode qr = QrCode.encodeText(url, QrCode.Ecc.MEDIUM);
        int quietZone = 4;
        int modules = qr.size + 2 * quietZone;
        int scale = Math.max(1, maxPixels / modules);
        int size = modules * scale;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.WHITE);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        paint.setColor(Color.BLACK);
        for (int y = 0; y < qr.size; y++) {
            for (int x = 0; x < qr.size; x++) {
                if (!qr.getModule(x, y)) continue;
                int left = (x + quietZone) * scale;
                int top = (y + quietZone) * scale;
                canvas.drawRect(left, top, left + scale, top + scale, paint);
            }
        }
        return bitmap;
    }

    private void web() {
        start("家长管理  /  局域网", "在浏览器中管理",
                "将手机或电脑连接到电视所在的网络，并在浏览器中打开下方地址。");
        section("管理地址");
        webAddress = TvUi.text(home, "", 26, TvUi.WHITE, true);
        TvUi.add(group, webAddress, 4);
        updateWebAddress();
        TvUi.add(group, TvUi.text(home, "请在网页中输入 6 位家长 PIN。", 17, TvUi.MUTED, false), 14);
    }

    void updateWebAddress() {
        if (webAddress == null) return;
        WebAdmin server = WebAdminService.server();
        String address = server == null ? WebAdminService.unavailableMessage() : server.url();
        if (!address.contentEquals(webAddress.getText())) webAddress.setText(address);
    }

    private void toggleGuard() {
        if (!store.enabled() && !GuardService.isConnected()) {
            home.message("请先在无障碍设置中启用「学习电视 · 观看管控」");
            return;
        }
        store.setEnabled(!store.enabled());
        GuardService.refreshSoon();
        if (store.enabled()) home.show(HomeActivity.Page.HOME);
        else dashboard();
    }

    private void rules() {
        int[] values = store.readRuleValues();
        int bankSize = store.questionCapacity();
        boolean[] relock = {store.clock.rules.relockOnWake};
        start("家长管理  /  观看规则", "配置时长和题数", "题数受当前题库限制。休息和每日上限独立于答题奖励。");
        Button first = null;
        for (int i = 0; i < values.length; i++) {
            int index = i;
            int limit = (i == 0 || i == 2) ? Math.min(bankSize, Store.RULE_LIMITS[i]) : Store.RULE_LIMITS[i];
            LinearLayout row = TvUi.row(home);
            inset(row);
            TextView label = TvUi.text(home, Store.RULE_LABELS[i], 19, TvUi.WHITE, true);
            row.addView(label, new LinearLayout.LayoutParams(0, TvUi.WRAP, 1));
            TextView number = TvUi.text(home, String.valueOf(values[i]), 20, TvUi.WHITE, true);
            number.setGravity(Gravity.CENTER);
            number.setMinWidth(TvUi.dp(home, 64));
            Button lower = TvUi.button(home, "−", () -> changeValue(values, index, -1, limit, number));
            Button higher = TvUi.button(home, "+", () -> changeValue(values, index, 1, limit, number));
            lower.setOnLongClickListener(v -> {
                changeValue(values, index, -5, limit, number);
                return true;
            });
            higher.setOnLongClickListener(v -> {
                changeValue(values, index, 5, limit, number);
                return true;
            });
            lower.setContentDescription("减少" + Store.RULE_LABELS[i]);
            higher.setContentDescription("增加" + Store.RULE_LABELS[i]);
            TvUi.gap(row, lower, 10);
            TvUi.gap(row, number, 2);
            TvUi.gap(row, higher, 2);
            TvUi.add(content, row, 8);
            if (first == null) first = lower;
        }
        Button wake = TvUi.button(home, wakeLabel(relock[0]), () -> {});
        wake.setOnClickListener(v -> {
            relock[0] = !relock[0];
            wake.setText(wakeLabel(relock[0]));
        });
        actions(wake);
        actions(TvUi.primaryButton(home, "保存规则", () -> {
                    if (Math.max(values[0], values[2]) > store.questionCapacity()) {
                        home.message("出题范围已变更，请按当前题量重新设置");
                        rules();
                        return;
                    }
                    store.setRules(values, relock[0]);
                    home.show(HomeActivity.Page.PARENT);
                }),
                TvUi.button(home, "取消", () -> home.show(HomeActivity.Page.PARENT)));
        TvUi.focus(first);
    }

    private static void changeValue(int[] values, int index, int delta, int limit, TextView number) {
        values[index] = Math.max(1, Math.min(limit, values[index] + delta));
        number.setText(String.valueOf(values[index]));
    }

    private static String wakeLabel(boolean relock) {
        return relock ? "待机唤醒后重新答题" : "待机唤醒后保留剩余时间";
    }

    private void favorites() {
        start("家长管理  /  桌面", "常用图标", "已固定图标按桌面顺序显示。用「前移」「后移」调整位置。");
        LinearLayout fixed = TvUi.row(home);
        inset(fixed);
        TvUi.add(fixed, TvUi.text(home, "1  " + home.getString(R.string.study_tile) + "（固定）",
                18, TvUi.WHITE, true), 0);
        TvUi.add(content, fixed, 8);
        List<String> keys = home.favorites();
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(Store.SETTINGS_TILE, "系统设置");
        for (AppCatalog.Entry app : home.apps) {
            if (!store.isSettings(app.key()) || keys.contains(app.key())) {
                labels.put(app.key(), app.label);
            }
        }
        LinkedHashSet<String> ordered = new LinkedHashSet<>(keys);
        ordered.addAll(labels.keySet());
        Button first = null;
        for (String key : ordered) {
            Button toggle = favoriteRow(keys, key, labels.get(key));
            if (first == null) first = toggle;
        }
        actions(TvUi.primaryButton(home, "完成", () -> home.show(HomeActivity.Page.PARENT)));
        if (!labels.containsKey(favoriteKey)) TvUi.focus(first);
    }

    private Button favoriteRow(List<String> keys, String key, String label) {
        LinearLayout row = TvUi.row(home);
        inset(row);
        int index = keys.indexOf(key);
        TextView name = TvUi.text(home, (index < 0 ? "" : (index + 2) + "  ") + label, 18, TvUi.WHITE, true);
        row.addView(name, new LinearLayout.LayoutParams(0, TvUi.WRAP, 1));
        Button toggle = TvUi.button(home, index < 0 ? "固定" : "移除", () -> updateFavorite(keys, key, 0));
        toggle.setContentDescription((index < 0 ? "固定" : "移除") + label);
        TvUi.gap(row, toggle, 8);
        Button focus = toggle;
        if (index > 0) {
            Button up = TvUi.button(home, "前移", () -> updateFavorite(keys, key, -1));
            up.setContentDescription("前移" + label);
            TvUi.gap(row, up, 8);
            if (favoriteButton == -1) focus = up;
        }
        if (index >= 0 && index < keys.size() - 1) {
            Button down = TvUi.button(home, "后移", () -> updateFavorite(keys, key, 1));
            down.setContentDescription("后移" + label);
            TvUi.gap(row, down, 8);
            if (favoriteButton == 1) focus = down;
        }
        TvUi.add(content, row, 8);
        if (key.equals(favoriteKey)) TvUi.focus(focus);
        return toggle;
    }

    private void updateFavorite(List<String> keys, String key, int direction) {
        int index = keys.indexOf(key);
        if (direction == 0) {
            if (index >= 0) keys.remove(index);
            else keys.add(key);
        } else {
            Collections.swap(keys, index, index + direction);
        }
        store.saveFavorites(keys);
        favoriteKey = key;
        favoriteButton = direction;
        favorites();
    }

    private void section(String label) {
        group = TvUi.column(home);
        inset(group);
        sectionTitle(group, label, 0);
        TvUi.add(content, group, 14);
    }

    private void sectionTitle(LinearLayout column, String label, int marginTop) {
        TvUi.add(column, TvUi.text(home, label, 18, TvUi.ACCENT, true), marginTop);
    }

    private void inset(View view) {
        view.setBackground(TvUi.panel(home, 20));
        int padding = TvUi.dp(home, 12);
        view.setPadding(padding, padding, padding, padding);
    }

    private void actions(View... buttons) {
        LinearLayout row = TvUi.row(home);
        for (int i = 0; i < buttons.length; i++) TvUi.gap(row, buttons[i], i == 0 ? 0 : 10);
        TvUi.add(group == null ? content : group, row, 10);
    }

    private void menuActions(LinearLayout column, Button... buttons) {
        TvUi.add(column, new MenuActions(home, buttons), 6);
    }

    private static final class MenuActions extends ViewGroup {
        private final int gap;

        MenuActions(HomeActivity home, Button[] buttons) {
            super(home);
            gap = TvUi.dp(home, 10);
            setClipChildren(false);
            for (Button button : buttons) addView(button, new LayoutParams(TvUi.WRAP, TvUi.WRAP));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int buttonWidthSpec = getChildMeasureSpec(widthMeasureSpec, 0, TvUi.WRAP);
            int buttonHeightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            int desiredWidth = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View button = getChildAt(i);
                button.measure(buttonWidthSpec, buttonHeightSpec);
                desiredWidth += button.getMeasuredWidth() + (i == 0 ? 0 : gap);
            }
            int width = resolveSize(desiredWidth, widthMeasureSpec);
            int height = arrangeButtons(width, false);
            setMeasuredDimension(width, resolveSize(height, heightMeasureSpec));
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            arrangeButtons(right - left, true);
        }

        private int arrangeButtons(int width, boolean layout) {
            int rowWidth = 0;
            int rowHeight = 0;
            int top = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View button = getChildAt(i);
                int buttonWidth = button.getMeasuredWidth();
                int buttonHeight = button.getMeasuredHeight();
                int left = rowWidth == 0 ? 0 : rowWidth + gap;
                if (rowWidth > 0 && left + buttonWidth > width) {
                    top += rowHeight + gap;
                    left = 0;
                    rowHeight = 0;
                }
                rowWidth = left + buttonWidth;
                rowHeight = Math.max(rowHeight, buttonHeight);
                if (layout) button.layout(left, top, rowWidth, top + buttonHeight);
            }
            return top + rowHeight;
        }
    }
}
