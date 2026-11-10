package tv.study.guard;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import java.util.List;

@SuppressLint("ViewConstructor") // Choices are built from question data in code.
final class AnswerChoices extends ViewGroup {
    private final int gap;
    private int columns, cellWidth;

    interface Selection {
        void choose(int index);
    }

    AnswerChoices(Context context, List<String> options, Selection selection) {
        super(context);
        gap = TvUi.dp(context, 8);
        setClipChildren(false);
        for (int i = 0; i < options.size(); i++) {
            int selected = i;
            Button choice = TvUi.button(context, options.get(i), () -> selection.choose(selected));
            choice.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            choice.setSingleLine(false);
            choice.setEllipsize(null);
            addView(choice);
        }
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int halfWidth = (width - gap) / 2;
        columns = fitsInTwoColumns(halfWidth) ? 2 : 1;
        cellWidth = columns == 2 ? halfWidth : width;
        int cellSpec = MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY);
        int height = 0;
        for (int row = 0; row < getChildCount(); row += columns) {
            for (int col = 0; col < columns; col++) {
                getChildAt(row + col).measure(cellSpec, MeasureSpec.UNSPECIFIED);
            }
            height += rowHeight(row) + (row == 0 ? 0 : gap);
        }
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec));
    }

    private boolean fitsInTwoColumns(int cellWidth) {
        if (getChildCount() != 4 || cellWidth <= 0) return false;
        for (int i = 0; i < getChildCount(); i++) {
            View choice = getChildAt(i);
            choice.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
            if (choice.getMeasuredWidth() > cellWidth) return false;
        }
        return true;
    }

    private int rowHeight(int row) {
        int height = getChildAt(row).getMeasuredHeight();
        return columns == 1 ? height : Math.max(height, getChildAt(row + 1).getMeasuredHeight());
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int y = 0;
        for (int row = 0; row < getChildCount(); row += columns) {
            int height = rowHeight(row);
            for (int col = 0; col < columns; col++) {
                int x = col * (cellWidth + gap);
                getChildAt(row + col).layout(x, y, x + cellWidth, y + height);
            }
            y += height + gap;
        }
    }
}
