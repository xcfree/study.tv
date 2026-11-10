package tv.study.guard;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

@SuppressLint("ViewConstructor") // Built in code with the recorded dates.
final class ViewingHistoryPanel extends LinearLayout {
    private static final int[] COLORS = {
            0xff58463e, 0xff796054, 0xff9b5036, 0xffc57449, 0xffe89e66, 0xffffca89
    };
    private static final String[] LEGEND = {"无记录", "0", "<15", "15–29", "30–59", "≥60"};
    private static final String WEEKDAYS = "一二三四五六日";
    private final TextView detail;
    private View selected;

    ViewingHistoryPanel(Context context, List<ViewingDay> days, int width) {
        super(context);
        setOrientation(VERTICAL);
        int gap = width < TvUi.dp(context, 300) ? 2 : 4;
        int gapPixels = TvUi.dp(context, gap);
        int weekdayWidth = TvUi.dp(context, 22);
        int cell = Math.max(1, Math.min(TvUi.dp(context, 29), (width - weekdayWidth) / ViewingDay.WEEKS - gapPixels));
        ViewingDay today = days.get(days.size() - 1);
        detail = TvUi.text(context, "", 16, TvUi.WHITE, true);
        TvUi.add(this, detail, 0);
        addMonthLabels(days, cell, gapPixels, weekdayWidth);

        for (int weekday = 0; weekday < 7; weekday++) {
            LinearLayout row = TvUi.row(context);
            TextView label = TvUi.text(context, WEEKDAYS.substring(weekday, weekday + 1), 13, TvUi.MUTED, false);
            row.addView(label, new LinearLayout.LayoutParams(weekdayWidth, TvUi.WRAP));
            for (int week = 0; week < ViewingDay.WEEKS; week++) {
                int index = week * 7 + weekday;
                if (index >= days.size()) break;
                ViewingDay day = days.get(index);
                View square = new View(context);
                square.setFocusable(true);
                square.setContentDescription(day.description());
                square.setBackground(cellBackground(day.level()));
                if (android.os.Build.VERSION.SDK_INT >= 26) square.setDefaultFocusHighlightEnabled(false);
                square.setOnFocusChangeListener((view, focused) -> {
                    if (focused) select(view, day);
                });
                LinearLayout.LayoutParams position = new LinearLayout.LayoutParams(cell, cell);
                position.leftMargin = gapPixels;
                row.addView(square, position);
                if (day == today) select(square, day);
            }
            TvUi.add(this, row, gap);
        }

        int firstRowEnd = width < TvUi.dp(context, 360) ? 3 : LEGEND.length;
        addLegendRow("分钟", 0, firstRowEnd);
        if (firstRowEnd < LEGEND.length) addLegendRow("", firstRowEnd, LEGEND.length);
    }

    private void addMonthLabels(List<ViewingDay> days, int cell, int gap, int weekdayWidth) {
        Context context = getContext();
        FrameLayout axis = new FrameLayout(context);
        int pitch = cell + gap;
        int lastWeek = (days.size() - 1) / 7;
        boolean shortOpeningMonth = days.get(0).month != days.get(Math.min(13, days.size() - 1)).month;
        int previousMonth = 0;
        int previousWeek = -1;
        for (int index = 0; index < days.size(); index++) {
            int month = days.get(index).month;
            if (month == previousMonth) continue;
            // A month starting in the opening week gets the next column if that week already has a label.
            int week = Math.max(index / 7, previousWeek + 1);
            previousMonth = month;
            previousWeek = week;
            TextView label = TvUi.text(context, month + "月", 12, TvUi.MUTED, false);
            int offset = week * pitch;
            if ((week == 0 && shortOpeningMonth) || week == lastWeek) {
                int textWidth = (int) Math.ceil(label.getPaint().measureText(label.getText().toString()));
                offset += cell - textWidth;
            }
            FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(TvUi.WRAP, TvUi.WRAP);
            position.leftMargin = weekdayWidth + gap + offset;
            axis.addView(label, position);
        }
        TvUi.add(this, axis, 6);
    }

    private void select(View square, ViewingDay day) {
        if (selected != null) selected.setSelected(false);
        selected = square;
        selected.setSelected(true);
        detail.setText(day.description());
    }

    private void addLegendRow(String label, int start, int end) {
        Context context = getContext();
        LinearLayout legend = TvUi.row(context);
        TextView unit = TvUi.text(context, label, 13, TvUi.MUTED, false);
        legend.addView(unit, new LinearLayout.LayoutParams(TvUi.dp(context, 30), TvUi.WRAP));
        for (int i = start; i < end; i++) {
            View swatch = new View(context);
            swatch.setBackground(square(i - 1, 0));
            int size = TvUi.dp(context, 12);
            LinearLayout.LayoutParams position = new LinearLayout.LayoutParams(size, size);
            position.leftMargin = TvUi.dp(context, 6);
            legend.addView(swatch, position);
            TvUi.gap(legend, TvUi.text(context, LEGEND[i], 13, TvUi.MUTED, false), 3);
        }
        TvUi.add(this, legend, 6);
    }

    private StateListDrawable cellBackground(int level) {
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_focused}, square(level, .58f));
        background.addState(new int[]{android.R.attr.state_selected}, square(level, .33f));
        background.addState(new int[]{}, square(level, 0));
        return background;
    }

    private GradientDrawable square(int level, float highlight) {
        int color = COLORS[level + 1];
        float base = 1 - highlight;
        float white = 255 * highlight;
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.rgb(Math.round(Color.red(color) * base + white),
                Math.round(Color.green(color) * base + white),
                Math.round(Color.blue(color) * base + white)));
        drawable.setCornerRadius(TvUi.dp(getContext(), 3));
        return drawable;
    }
}
