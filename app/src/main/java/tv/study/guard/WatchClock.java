package tv.study.guard;

final class WatchClock {
    static final long MINUTE = 60_000L;

    enum Gate {
        QUIZ, OPEN, REST, PIN_REST, DAILY;

        boolean pausesVideo() {
            return this != OPEN;
        }
    }

    static final class Rules {
        final int firstQuestions, firstMinutes, nextQuestions, nextMinutes;
        final int dailyMinutes, continuousMinutes, restMinutes;
        final boolean relockOnWake;

        Rules(int firstQuestions, int firstMinutes, int nextQuestions, int nextMinutes,
              int dailyMinutes, int continuousMinutes, int restMinutes, boolean relockOnWake) {
            this.firstQuestions = firstQuestions;
            this.firstMinutes = firstMinutes;
            this.nextQuestions = nextQuestions;
            this.nextMinutes = nextMinutes;
            this.dailyMinutes = dailyMinutes;
            this.continuousMinutes = continuousMinutes;
            this.restMinutes = restMinutes;
            this.relockOnWake = relockOnWake;
        }
    }

    Rules rules;
    int day;
    boolean firstDone;
    long used, continuous, extra, restWall;
    long remaining;
    private long restEnd, pinRestEnd, last, idleSince;
    private boolean viewing;
    boolean dirty;

    WatchClock(Rules rules, int day, boolean firstDone, long used, long continuous,
               long extra, long restWall, long now, long wall) {
        this.rules = rules;
        this.day = day;
        this.firstDone = firstDone;
        this.used = used;
        this.continuous = continuous;
        this.extra = extra;
        this.restWall = restWall;
        restEnd = restWall == 0 ? 0 : now + Math.min(60 * MINUTE, Math.max(0, restWall - wall));
        last = idleSince = now;
    }

    long dailyLeft() {
        return Math.max(0, rules.dailyMinutes * MINUTE + extra - used);
    }

    long available() {
        return Math.min(remaining, dailyLeft());
    }

    long restLeft(long now) {
        return Math.max(0, (pinRestEnd != 0 ? pinRestEnd : restEnd) - now);
    }

    Gate gate() {
        if (pinRestEnd != 0) return Gate.PIN_REST;
        if (restWall != 0) return Gate.REST;
        if (dailyLeft() == 0) return Gate.DAILY;
        return remaining == 0 ? Gate.QUIZ : Gate.OPEN;
    }

    long update(long now, long wall, int currentDay, long dayEnd, boolean nextViewing, long pinRetryDeadline) {
        boolean newDay = currentDay > day;
        if (viewing && gate() == Gate.OPEN) {
            // At rollover the previous day's grant only covers time up to midnight.
            long end = newDay ? Math.min(now, dayEnd) : now;
            long allowedContinuous = Math.max(0, rules.continuousMinutes * MINUTE - continuous);
            long watched = Math.min(Math.min(end - last, available()), allowedContinuous);
            if (watched > 0) {
                used += watched;
                remaining -= watched;
                continuous += watched;
                dirty = true;
            }
            if (continuous >= rules.continuousMinutes * MINUTE) {
                startRest(rules.restMinutes, now, wall);
            }
        }
        long archived = 0;
        if (newDay) {
            archived = used;
            day = currentDay;
            used = extra = remaining = 0;
            firstDone = false;
            dirty = true;
        }
        if (restWall != 0 && now >= restEnd) {
            restWall = restEnd = continuous = 0;
            dirty = true;
        }
        if (pinRestEnd != 0 && now >= pinRestEnd && continuous != 0) {
            continuous = 0;
            dirty = true;
        }
        pinRestEnd = pinRetryDeadline > now ? pinRetryDeadline : 0;
        if (!viewing && continuous != 0 && restWall == 0 && pinRestEnd == 0
                && now - idleSince >= rules.restMinutes * MINUTE) {
            continuous = 0;
            dirty = true;
        }
        if (viewing && !nextViewing) idleSince = now;
        viewing = nextViewing;
        last = now;
        return archived;
    }

    boolean isViewing() {
        return viewing;
    }

    private void startRest(int minutes, long now, long wall) {
        long duration = minutes * MINUTE;
        restEnd = now + duration;
        restWall = wall + duration;
        dirty = true;
    }

    void restAfterQuiz(long now, long wall) {
        startRest(10, now, wall);
    }

    void passQuiz() {
        if (gate() != Gate.QUIZ) return;
        remaining = (firstDone ? rules.nextMinutes : rules.firstMinutes) * MINUTE;
        firstDone = true;
        dirty = true;
    }

    void approve(int minutes) {
        long allowance = minutes * MINUTE;
        extra += allowance;
        remaining = Math.max(remaining, allowance);
        dirty = true;
    }

    void relock(boolean firstQuiz) {
        remaining = 0;
        if (firstQuiz) {
            firstDone = false;
            dirty = true;
        }
    }
}
