package tv.study.guard;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

final class QuizRound {
    enum Result { CORRECT, WRONG, PASSED, REST }

    private final List<Pool> pools = new ArrayList<>();
    private final List<Pool> fallback;
    private final Random random = new Random();
    private Pool currentPool;
    private int currentIndex;
    LocalContent.Question question;
    Result result;
    int correct;
    int target;

    QuizRound(QuestionBanks banks, String source, int initialTarget) {
        this(banks, Collections.singletonList(source), initialTarget);
    }

    QuizRound(QuestionBanks banks, List<String> sources, int initialTarget) {
        List<QuestionBanks.Bank> active = banks.activeBanks(sources);
        for (QuestionBanks.Bank bank : active) pools.add(new Pool(bank));
        fallback = active.contains(banks.builtin) ? Collections.emptyList()
                : Collections.singletonList(new Pool(banks.builtin));
        target = Math.min(banks.capacity(sources), initialTarget);
        if (!advance()) throw new IllegalStateException("No eligible quiz questions");
    }

    QuizRound(QuestionBanks.Bank bank) {
        pools.add(new Pool(bank));
        fallback = Collections.emptyList();
        target = bank.size;
    }

    boolean nextPracticeQuestion() {
        LocalContent.Question next = draw(pools);
        question = next == null ? null : next.shuffle();
        return question != null;
    }

    boolean recordWrongAnswer(int restTarget) {
        currentPool.missed.add(currentIndex);
        return ++target >= restTarget;
    }

    QuestionBanks.Bank currentBank() {
        return currentPool.bank;
    }

    int currentQuestionIndex() {
        return currentIndex;
    }

    boolean advance() {
        LocalContent.Question next = draw(pools);
        if (next == null) next = draw(fallback);
        if (next == null) {
            for (Pool pool : pools) pool.refill();
            for (Pool pool : fallback) pool.refill();
            next = draw(pools);
            if (next == null) next = draw(fallback);
        }
        if (next == null) return false;
        question = next.shuffle();
        return true;
    }

    private LocalContent.Question draw(List<Pool> candidates) {
        while (true) {
            int remaining = 0;
            for (Pool pool : candidates) remaining += pool.remaining;
            if (remaining == 0) return null;
            // Weight each subject by its remaining questions, then draw one record by index.
            int slot = random.nextInt(remaining);
            for (Pool pool : candidates) {
                if (slot >= pool.remaining) {
                    slot -= pool.remaining;
                    continue;
                }
                int index = pool.take(slot);
                if (!pool.missed.contains(index) && (pool != currentPool || index != currentIndex)) {
                    try {
                        LocalContent.Question next = pool.bank.questionAt(index);
                        currentPool = pool;
                        currentIndex = index;
                        return next;
                    } catch (IOException e) {
                        pool.failed = true;
                        pool.remaining = 0;
                    }
                }
                break;
            }
        }
    }

    private static final class Pool {
        final QuestionBanks.Bank bank;
        final Map<Integer, Integer> swaps = new HashMap<>();
        final Set<Integer> missed = new HashSet<>();
        int remaining;
        boolean failed;

        Pool(QuestionBanks.Bank bank) {
            this.bank = bank;
            remaining = bank.size;
        }

        int take(int slot) {
            // Fisher–Yates on a virtual array. Only positions touched by a draw are stored.
            int tail = --remaining;
            Integer index = swaps.remove(slot);
            if (slot != tail) {
                Integer replacement = swaps.remove(tail);
                swaps.put(slot, replacement == null ? tail : replacement);
            }
            return index == null ? slot : index;
        }

        void refill() {
            swaps.clear();
            remaining = failed ? 0 : bank.size;
        }
    }
}
