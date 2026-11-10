package tv.study.guard;

import java.util.Calendar;
import java.util.Locale;

final class ViewingDay {
    static final int WEEKS = 12;

    final String date;
    final int month;
    final long watched;

    ViewingDay(Calendar day, long watched) {
        this.month = day.get(Calendar.MONTH) + 1;
        this.date = date(day);
        this.watched = watched;
    }

    static String date(Calendar day) {
        return String.format(Locale.ROOT, "%04d-%02d-%02d",
                day.get(Calendar.YEAR), day.get(Calendar.MONTH) + 1, day.get(Calendar.DAY_OF_MONTH));
    }

    static int key(Calendar day) {
        return day.get(Calendar.YEAR) * 1000 + day.get(Calendar.DAY_OF_YEAR);
    }

    static long endOf(int day, Calendar date) {
        Calendar midnight = (Calendar) date.clone();
        midnight.set(Calendar.YEAR, day / 1000);
        midnight.set(Calendar.DAY_OF_YEAR, day % 1000);
        midnight.add(Calendar.DAY_OF_YEAR, 1);
        midnight.set(Calendar.HOUR_OF_DAY, 0);
        midnight.set(Calendar.MINUTE, 0);
        midnight.set(Calendar.SECOND, 0);
        midnight.set(Calendar.MILLISECOND, 0);
        return midnight.getTimeInMillis();
    }

    int level() {
        if (watched <= 0) return watched < 0 ? -1 : 0;
        if (watched < 15 * WatchClock.MINUTE) return 1;
        if (watched < 30 * WatchClock.MINUTE) return 2;
        return watched < 60 * WatchClock.MINUTE ? 3 : 4;
    }

    String description() {
        if (watched < 0) return date + " · 无历史记录";
        if (watched > 0 && watched < 1000) return date + " · 不足 1 秒";
        return date + " · " + TvUi.duration(watched - watched % 1000) + "（分:秒）";
    }
}
