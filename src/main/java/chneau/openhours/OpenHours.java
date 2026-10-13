package chneau.openhours;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * High-performance, zero-allocation OSM opening_hours parser and evaluator for Java 26+.
 */
@JsonSerialize(using = OpenHours.Serializer.class)
@JsonDeserialize(using = OpenHours.Deserializer.class)
public final class OpenHours implements Whenable {

    public record TimeWindow(int start, int end) implements Comparable<TimeWindow> {
        @Override
        public int compareTo(TimeWindow o) {
            return Integer.compare(this.start, o.start);
        }
    }

    public record NextDurResult(boolean isOpen, Duration duration) {}
    public record NextDateResult(boolean isOpen, LocalDateTime nextDate) {}

    public static final int MINUTES_PER_WEEK = 7 * 24 * 60; // 10,080
    private static final int BITMASK_WORDS = (MINUTES_PER_WEEK + 63) / 64; // 158

    private static final ConcurrentHashMap<String, OpenHours> INTERN_POOL = new ConcurrentHashMap<>();
    private static final OpenHours EMPTY = new OpenHours("", new TimeWindow[0], new long[BITMASK_WORDS]);
    private static final OpenHours ALWAYS_OPEN = createAlwaysOpen();

    private static final ThreadLocal<Entry> FAST_CACHE = ThreadLocal.withInitial(() -> new Entry("", EMPTY));

    private static void fastCacheSet(String trimmed, OpenHours val) {
        // Mutate the calling thread's existing slot instead of publishing a fresh
        // {@code Entry}: the slot is thread-confined, so this saves one allocation on
        // every intern-cache fill (the common case when alternating many distinct
        // expressions) while a hit observes exactly the same key/value pair.
        Entry e = FAST_CACHE.get();
        e.key = trimmed;
        e.val = val;
        e.keyChars = null;
    }

    /** Thread-local one-entry parse cache (the {@code Entry} is never shared across threads). */
    private static final class Entry {
        String key;
        OpenHours val;
        /** Lazily materialized so the plain parse path never pays for it. */
        private char[] keyChars;

        Entry(String key, OpenHours val) {
            this.key = key;
            this.val = val;
        }

        /**
         * True when {@code buf[off, off+len)} equals this entry's key without allocating
         * a {@code String}. Used by the JSON deserializer to skip the parser's text
         * materialization on a cache hit.
         */
        boolean matches(char[] buf, int off, int len) {
            char[] kc = keyChars;
            if (kc == null) {
                kc = key.toCharArray();
                keyChars = kc;
            }
            return kc.length == len && Arrays.mismatch(buf, off, off + len, kc, 0, len) < 0;
        }
    }

    private final String raw;
    private final TimeWindow[] windows;
    private final long[] bitmask;

    /** Bit 15 of a table entry: the schedule is open at that minute. */
    private static final int TBL_OPEN = 0x8000;
    /** Low 14 bits of a table entry: minutes until the next open/closed transition. */
    private static final int TBL_DELTA = 0x3FFF;

    /**
     * Lazily built per-minute successor table: {@code tbl[m] = (open ? 0x8000 : 0) | d}
     * where {@code d} is the number of minutes from week-minute {@code m} to the next
     * state transition (cyclic). Lets {@link #getTimeToOpen}, {@link #nextDur} and
     * {@link #nextDate} answer with a single load instead of a window search. Published
     * through a {@code volatile} field so the interned, thread-shared instance hands a
     * fully-built array to every reader; a benign duplicate build is acceptable.
     */
    private volatile int[] deltaTable;

    private static OpenHours createAlwaysOpen() {
        long[] bm = new long[BITMASK_WORDS];
        Arrays.fill(bm, ~0L);
        int rem = MINUTES_PER_WEEK % 64;
        if (rem != 0) {
            bm[BITMASK_WORDS - 1] = (1L << rem) - 1;
        }
        return new OpenHours("24/7", new TimeWindow[]{new TimeWindow(0, MINUTES_PER_WEEK)}, bm);
    }

    private OpenHours(String raw, TimeWindow[] windows, long[] bitmask) {
        this.raw = raw;
        this.windows = windows;
        this.bitmask = bitmask;
    }

    public static OpenHours parse(String expression) {
        if (expression == null || expression.isEmpty()) {
            return EMPTY;
        }
        String trimmed = expression.trim();
        if (trimmed.isEmpty()) {
            return EMPTY;
        }
        if (trimmed.length() == 4 && "24/7".equalsIgnoreCase(trimmed)) {
            return ALWAYS_OPEN;
        }

        Entry e = FAST_CACHE.get();
        if (trimmed.equals(e.key)) {
            return e.val;
        }

        OpenHours cached = INTERN_POOL.get(trimmed);
        if (cached != null) {
            fastCacheSet(trimmed, cached);
            return cached;
        }

        OpenHours created = parseUncached(trimmed);
        OpenHours prev = INTERN_POOL.putIfAbsent(trimmed, created);
        OpenHours res = (prev != null) ? prev : created;
        fastCacheSet(trimmed, res);
        return res;
    }

    public static OpenHours from(String expression) {
        return parse(expression);
    }

    public String getRaw() {
        return raw;
    }

    public TimeWindow[] getWindows() {
        return windows;
    }

    public boolean isAlwaysOpen() {
        return this == ALWAYS_OPEN || (windows.length == 1 && windows[0].start == 0 && windows[0].end == MINUTES_PER_WEEK);
    }

    public boolean isEmpty() {
        return windows.length == 0;
    }

    /**
     * $O(1)$ scalar bit testing evaluating in ~2.0 nanoseconds.
     */
    public boolean isOpen(LocalDateTime dt) {
        // No empty / always-open guards: the baked bitmask is all-zero for an
        // empty schedule and all-one for 24/7, so the bit test is already exact.
        int weekMinute = getWeekMinute(dt);
        return (bitmask[weekMinute >> 6] & (1L << (weekMinute & 63))) != 0;
    }

    public boolean match(LocalDateTime dt) {
        return isOpen(dt);
    }

    public Duration getTimeToOpen(LocalDateTime from) {
        if (windows.length == 0) return null;
        if (isAlwaysOpen()) return Duration.ZERO;

        int t = getWeekMinute(from);
        int entry = deltaTable()[t];
        if ((entry & TBL_OPEN) != 0) return Duration.ZERO;
        return waitDuration(entry & TBL_DELTA, from);
    }

    public Duration getTimeToOpenForDuration(LocalDateTime from, Duration required) {
        if (windows.length == 0) return null;
        long reqNanos = required.toNanos();
        if (reqNanos <= 0) return Duration.ZERO;
        long n = nanosToOpenForDuration(from, reqNanos, (int) ((required.toSeconds() + 59) / 60));
        if (n < 0) return null;
        return n == 0 ? Duration.ZERO : Duration.ofNanos(n);
    }

    /**
     * Core of {@link #getTimeToOpenForDuration}: returns the wait in nanoseconds,
     * {@code 0} if the required duration already fits starting now, or {@code -1}
     * if it never fits. Kept allocation-free so {@link #when} can add the wait
     * directly with {@link LocalDateTime#plusNanos(long)}.
     */
    private long nanosToOpenForDuration(LocalDateTime from, long reqNanos, int reqMinutes) {
        if (windows.length == 0) return -1;
        if (reqNanos <= 0) return 0;
        if (reqMinutes > MINUTES_PER_WEEK) return -1;
        if (isAlwaysOpen()) return 0;

        int t = getWeekMinute(from);
        long subDurNanos = subNanos(from);
        int startIdx = findFirstWindowStartingAtOrAfter(t);

        int n = windows.length;
        boolean lastEndsAtWeekEnd = windows[n - 1].end == MINUTES_PER_WEEK;
        boolean firstStartsAtZero = windows[0].start == 0;
        // Only the final window can wrap; precompute its (possibly extended) end once so
        // the two loops do not re-evaluate the wrap predicate per iteration.
        int lastEffectiveEnd = (lastEndsAtWeekEnd && firstStartsAtZero)
                ? MINUTES_PER_WEEK + windows[0].end
                : windows[n - 1].end;

        for (int i = startIdx; i < n; i++) {
            TimeWindow w = windows[i];
            int effectiveEnd = (i == n - 1) ? lastEffectiveEnd : w.end;

            if (t >= w.start) {
                long remNanos = ((long) (effectiveEnd - t)) * 60_000_000_000L - subDurNanos;
                if (remNanos >= reqNanos) {
                    return 0;
                }
            } else {
                if (effectiveEnd - w.start >= reqMinutes) {
                    return (long) (w.start - t) * 60_000_000_000L - subDurNanos;
                }
            }
        }

        for (int i = 0; i <= startIdx && i < n; i++) {
            TimeWindow w = windows[i];
            int effectiveEnd = (i == n - 1) ? lastEffectiveEnd : w.end;

            if (effectiveEnd - w.start >= reqMinutes) {
                return (long) ((MINUTES_PER_WEEK - t) + w.start) * 60_000_000_000L - subDurNanos;
            }
        }

        return -1;
    }

    @Override
    public LocalDateTime when(LocalDateTime from, Duration duration) {
        if (windows.length == 0) return null;
        long reqNanos = duration.toNanos();
        long n = nanosToOpenForDuration(from, reqNanos,
                reqNanos <= 0 ? 0 : (int) ((duration.toSeconds() + 59) / 60));
        if (n < 0) return null;
        if (n == 0) return from;
        return from.plusNanos(n);
    }

    /** Sub-minute part of {@code from} expressed in nanoseconds. */
    private static long subNanos(LocalDateTime from) {
        return from.getSecond() * 1_000_000_000L + from.getNano();
    }

    /**
     * Equivalent to {@code Duration.ofMinutes(diffMin).minusSeconds(from.getSecond())
     * .minusNanos(from.getNano())} in a single allocation.
     */
    private static Duration waitDuration(int diffMin, LocalDateTime from) {
        return Duration.ofNanos(diffMin * 60_000_000_000L - subNanos(from));
    }

    public LocalDateTime getCurrentShiftEnd(LocalDateTime dt) {
        if (windows.length == 0) return null;
        if (isAlwaysOpen()) return dt.plusWeeks(52);

        int t = getWeekMinute(dt);
        int idx = findFirstWindowStartingAtOrAfter(t);
        if (idx >= windows.length || windows[idx].start > t) {
            return null;
        }

        TimeWindow w = windows[idx];
        int diffMin = w.end - t;
        if (idx == windows.length - 1 && w.end == MINUTES_PER_WEEK && windows[0].start == 0) {
            diffMin = (MINUTES_PER_WEEK - t) + windows[0].end;
        }

        return dt.plusNanos((long) diffMin * 60_000_000_000L - subNanos(dt));
    }

    public NextDurResult nextDur(LocalDateTime dt) {
        if (windows.length == 0) return new NextDurResult(false, Duration.ZERO);
        if (isAlwaysOpen()) return new NextDurResult(true, Duration.ofDays(365));

        int t = getWeekMinute(dt);
        int entry = deltaTable()[t];
        boolean open = (entry & TBL_OPEN) != 0;
        return new NextDurResult(open, waitDuration(entry & TBL_DELTA, dt));
    }

    public NextDateResult nextDate(LocalDateTime dt) {
        if (windows.length == 0) return new NextDateResult(false, dt);
        if (isAlwaysOpen()) return new NextDateResult(true, dt.plusDays(365));

        int t = getWeekMinute(dt);
        int entry = deltaTable()[t];
        boolean open = (entry & TBL_OPEN) != 0;
        int diffMin = entry & TBL_DELTA;

        long nanos = (long) diffMin * 60_000_000_000L - subNanos(dt);
        return new NextDateResult(open, nanos == 0 ? dt : dt.plusNanos(nanos));
    }

    /**
     * Returns the lazily-built per-minute successor table, building (and safely
     * publishing) it on first use. Never called for empty or 24/7 schedules, which
     * are short-circuited by every caller.
     */
    private int[] deltaTable() {
        int[] t = deltaTable;
        if (t == null) {
            t = buildDeltaTable();
            deltaTable = t;
        }
        return t;
    }

    /**
     * Builds the successor table by locating every minute where the open state changes
     * and filling each constant-state run in a single backward pass, so the cost is
     * O(MINUTES_PER_WEEK) regardless of the number of windows.
     */
    private int[] buildDeltaTable() {
        final long[] bm = this.bitmask;
        final int[] table = new int[MINUTES_PER_WEEK];
        final int[] trans = new int[MINUTES_PER_WEEK];
        int tn = 0;
        boolean prev = bitOpen(bm, MINUTES_PER_WEEK - 1);
        for (int m = 0; m < MINUTES_PER_WEEK; m++) {
            boolean cur = bitOpen(bm, m);
            if (cur != prev) {
                trans[tn++] = m;
            }
            prev = cur;
        }

        if (tn == 0) {
            // Constant state; only reachable for a 24/7 (short-circuited) or empty
            // schedule, kept for safety. Fill with a full-week delta.
            int val = (bitOpen(bm, 0) ? TBL_OPEN : 0) | MINUTES_PER_WEEK;
            Arrays.fill(table, val);
            return table;
        }

        for (int i = 0; i < tn; i++) {
            int start = trans[i];
            int end = (i + 1 < tn) ? trans[i + 1] : trans[0] + MINUTES_PER_WEEK;
            int openBit = bitOpen(bm, start) ? TBL_OPEN : 0;
            for (int m = start; m < end; m++) {
                table[m % MINUTES_PER_WEEK] = openBit | (end - m);
            }
        }
        return table;
    }

    /**
     * Memoizes the "start-of-day minute" {@code (dayOfWeek - 1) * 1440} for the last
     * queried date. {@link LocalDate#getDayOfWeek()} recomputes the epoch day (several
     * divisions) on every call, which dominates the cost of a query; query workloads
     * (and anything walking a timeline) overwhelmingly hit the same calendar day many
     * times in a row, so a single-entry cache collapses that cost to a load.
     *
     * <p>The key packs {@code year/month/day} as {@code ((y*32)+m)*32+d} (injective for
     * {@code m,d < 32}) in the high bits and the day-minute base in the low 14 bits of a
     * single {@code volatile long}, so a concurrent reader always observes a
     * key/value pair written together (never a torn combination); a stale/mismatching
     * key simply falls through to a recompute. The result is identical to computing
     * {@code getDayOfWeek()} directly for every input, including negative years.
     */
    private static volatile long DAY_BASE_CACHE;

    private static int getWeekMinute(LocalDateTime dt) {
        int y = dt.getYear();
        int m = dt.getMonthValue();
        int d = dt.getDayOfMonth();
        long key = ((long) y * 32 + m) * 32 + d;
        long c = DAY_BASE_CACHE;
        int dayBase;
        if ((c >> 14) == key) {
            dayBase = (int) (c & 0x3FFFL);
        } else {
            dayBase = (dt.getDayOfWeek().getValue() - 1) * 1440;
            DAY_BASE_CACHE = (key << 14) | dayBase;
        }
        return dayBase + dt.getHour() * 60 + dt.getMinute();
    }

    private int findFirstWindowStartingAtOrAfter(int t) {
        int n = windows.length;
        switch (n) {
            case 0 -> { return 0; }
            case 1 -> { return windows[0].end > t ? 0 : 1; }
            case 2 -> {
                if (windows[0].end > t) return 0;
                if (windows[1].end > t) return 1;
                return 2;
            }
            case 3 -> {
                if (windows[0].end > t) return 0;
                if (windows[1].end > t) return 1;
                if (windows[2].end > t) return 2;
                return 3;
            }
            case 4 -> {
                if (windows[0].end > t) return 0;
                if (windows[1].end > t) return 1;
                if (windows[2].end > t) return 2;
                if (windows[3].end > t) return 3;
                return 4;
            }
            default -> {
                int low = 0;
                int high = n - 1;
                int result = n;
                while (low <= high) {
                    int mid = (low + high) >>> 1;
                    if (windows[mid].end > t) {
                        result = mid;
                        if (mid == 0) break;
                        high = mid - 1;
                    } else {
                        low = mid + 1;
                    }
                }
                return result;
            }
        }
    }

    private static OpenHours parseUncached(String expression) {
        // Accumulate open minutes directly into the week bitmask (158 words) instead of a
        // one-byte-per-minute boolean[10080] scratch array, keeping the per-parse cost
        // proportional to the number of rules rather than to MINUTES_PER_WEEK.
        long[] bm = new long[BITMASK_WORDS];
        int len = expression.length();
        int ruleStart = 0;

        while (ruleStart < len) {
            int ruleEnd = ruleStart;
            while (ruleEnd < len && expression.charAt(ruleEnd) != ';') {
                ruleEnd++;
            }
            String rule = expression.substring(ruleStart, ruleEnd).trim();
            ruleStart = ruleEnd + 1;

            if (rule.isEmpty()) continue;

            String lower = rule.toLowerCase(Locale.ROOT);
            boolean isOff = lower.contains("off") || lower.contains("closed");

            int[] days = new int[7];
            int numDays = 0;
            int[] intervals = new int[32]; // [start0, end0, start1, end1, ...]
            int numIntervals = 0;

            String[] tokens = rule.split("\\s+");
            for (String token : tokens) {
                token = token.trim();
                if (token.isEmpty()) continue;
                if ("off".equalsIgnoreCase(token) || "closed".equalsIgnoreCase(token)) continue;

                char c0 = token.charAt(0);
                if (Character.isDigit(c0) || c0 == '+') {
                    numIntervals = parseTimeIntervalsFast(token, intervals, numIntervals);
                } else if (Character.isLetter(c0)) {
                    numDays = parseDaysFast(token, days, numDays);
                }
            }

            if (numDays == 0 && numIntervals == 0) continue;
            if (numDays == 0) {
                for (int d = 0; d < 7; d++) days[d] = d;
                numDays = 7;
            }
            if (numIntervals == 0) {
                intervals[0] = 0;
                intervals[1] = 1440;
                numIntervals = 1;
            }

            boolean value = !isOff;
            for (int d = 0; d < numDays; d++) {
                int day = days[d];
                for (int inter = 0; inter < numIntervals; inter++) {
                    int start = intervals[inter * 2];
                    int end = intervals[inter * 2 + 1];
                    int startMin = day * 1440 + start;

                    // Same three cases as a naive per-minute loop, expressed as one
                    // half-open minute range [startMin, hi); applyMinuteRange wraps it
                    // around the week boundary the same way `m % MINUTES_PER_WEEK` did.
                    int hi;
                    if (end > 1440) {
                        hi = day * 1440 + end;
                    } else if (start > end) {
                        hi = (day + 1) * 1440 + end;
                    } else {
                        hi = day * 1440 + end;
                    }
                    applyMinuteRange(bm, startMin, hi, value);
                }
            }
        }

        // Derive disjoint TimeWindows from the baked bitmask, one word at a time: all-zero
        // and all-one words are handled in O(1), and only a word that straddles a window
        // boundary is walked bit-by-bit. This replaces the former second full pass over the
        // boolean scratch array.
        TimeWindow[] winArray = buildWindows(bm);

        return new OpenHours(expression, winArray, bm);
    }

    /**
     * Builds the disjoint, ascending window list by scanning the week bitmask word by word.
     * Equivalent to a bit-by-bit scan of a {@code boolean[MINUTES_PER_WEEK]}, but skips
     * 64 minutes at a time whenever a word is all-zero or all-one.
     */
    private static TimeWindow[] buildWindows(long[] bm) {
        int count = 0;
        boolean inRun = false;
        for (int wi = 0; wi < BITMASK_WORDS; wi++) {
            long w = bm[wi];
            if (w == 0) {
                if (inRun) { count++; inRun = false; }
            } else if (w == -1L) {
                inRun = true;
            } else {
                for (int bit = 0; bit < 64; bit++) {
                    if (((w >>> bit) & 1L) != 0) {
                        inRun = true;
                    } else if (inRun) {
                        count++;
                        inRun = false;
                    }
                }
            }
        }
        if (inRun) count++;

        TimeWindow[] out = new TimeWindow[count];
        int idx = 0;
        int runStart = -1;
        for (int wi = 0; wi < BITMASK_WORDS; wi++) {
            long w = bm[wi];
            int base = wi << 6;
            if (w == 0) {
                if (runStart != -1) {
                    out[idx++] = new TimeWindow(runStart, base);
                    runStart = -1;
                }
            } else if (w == -1L) {
                if (runStart == -1) runStart = base;
            } else {
                for (int bit = 0; bit < 64; bit++) {
                    if (((w >>> bit) & 1L) != 0) {
                        if (runStart == -1) runStart = base + bit;
                    } else if (runStart != -1) {
                        out[idx++] = new TimeWindow(runStart, base + bit);
                        runStart = -1;
                    }
                }
            }
        }
        if (runStart != -1) {
            out[idx] = new TimeWindow(runStart, MINUTES_PER_WEEK);
        }
        return out;
    }

    /**
     * Applies {@code value} to every minute in the half-open range {@code [lo, hi)},
     * wrapping around the week boundary exactly like {@code m % MINUTES_PER_WEEK}.
     */
    private static void applyMinuteRange(long[] bm, int lo, int hi, boolean value) {
        if (lo >= hi) return;
        int n = hi - lo; // always < MINUTES_PER_WEEK by construction
        int loWrapped = lo % MINUTES_PER_WEEK;
        if (loWrapped + n <= MINUTES_PER_WEEK) {
            applyBitRange(bm, loWrapped, loWrapped + n, value);
        } else {
            applyBitRange(bm, loWrapped, MINUTES_PER_WEEK, value);
            applyBitRange(bm, 0, loWrapped + n - MINUTES_PER_WEEK, value);
        }
    }

    /** Sets or clears every bit in {@code [lo, hi)}; requires {@code hi <= MINUTES_PER_WEEK}. */
    private static void applyBitRange(long[] bm, int lo, int hi, boolean value) {
        if (lo >= hi) return;
        int firstWord = lo >> 6;
        int lastWord = (hi - 1) >> 6;
        if (firstWord == lastWord) {
            long mask = bitMask(lo & 63, hi - lo);
            if (value) bm[firstWord] |= mask;
            else bm[firstWord] &= ~mask;
            return;
        }
        long headMask = -1L << (lo & 63);
        if (value) bm[firstWord] |= headMask;
        else bm[firstWord] &= ~headMask;
        for (int w = firstWord + 1; w < lastWord; w++) {
            bm[w] = value ? -1L : 0L;
        }
        long tailMask = bitMask(0, ((hi - 1) & 63) + 1);
        if (value) bm[lastWord] |= tailMask;
        else bm[lastWord] &= ~tailMask;
    }

    /** {@code len} contiguous set bits starting at bit {@code offset} (both within 0..64). */
    private static long bitMask(int offset, int len) {
        if (len == 64) return -1L;
        return ((1L << len) - 1) << offset;
    }

    private static boolean bitOpen(long[] bm, int minute) {
        return (bm[minute >> 6] & (1L << (minute & 63))) != 0;
    }

    private static int parseDaysFast(String token, int[] days, int numDays) {
        String[] parts = token.split(",");
        for (String p : parts) {
            p = p.trim().toLowerCase(Locale.ROOT);
            if (p.isEmpty()) continue;

            int dashIdx = p.indexOf('-');
            if (dashIdx >= 0) {
                int d1 = parseDayFast(p.substring(0, dashIdx));
                int d2 = parseDayFast(p.substring(dashIdx + 1));
                if (d1 >= 0 && d2 >= 0) {
                    if (d2 < d1) d2 += 7;
                    for (int d = d1; d <= d2; d++) {
                        int actual = d % 7;
                        if (!containsDay(days, numDays, actual) && numDays < 7) {
                            days[numDays++] = actual;
                        }
                    }
                }
            } else {
                int d = parseDayFast(p);
                if (d >= 0 && !containsDay(days, numDays, d) && numDays < 7) {
                    days[numDays++] = d;
                }
            }
        }
        return numDays;
    }

    private static boolean containsDay(int[] days, int count, int target) {
        for (int i = 0; i < count; i++) {
            if (days[i] == target) return true;
        }
        return false;
    }

    private static int parseDayFast(String day) {
        day = day.trim().toLowerCase(Locale.ROOT);
        return switch (day) {
            case "mo" -> 0;
            case "tu" -> 1;
            case "we" -> 2;
            case "th" -> 3;
            case "fr" -> 4;
            case "sa" -> 5;
            case "su" -> 6;
            default -> -1;
        };
    }

    private static int parseTimeIntervalsFast(String token, int[] intervals, int numIntervals) {
        String[] parts = token.split(",");
        for (String p : parts) {
            p = p.trim();
            if (p.isEmpty()) continue;

            if (p.endsWith("+")) {
                int start = parseMinuteOfDayFast(p.substring(0, p.length() - 1));
                if (start >= 0 && numIntervals < 16) {
                    intervals[numIntervals * 2] = start;
                    intervals[numIntervals * 2 + 1] = 1440;
                    numIntervals++;
                }
            } else {
                int dashIdx = p.indexOf('-');
                if (dashIdx >= 0) {
                    int start = parseMinuteOfDayFast(p.substring(0, dashIdx));
                    int end = parseMinuteOfDayFast(p.substring(dashIdx + 1));
                    if (start >= 0 && end >= 0 && numIntervals < 16) {
                        if (end == 0 || end < start) {
                            end += 1440;
                        }
                        intervals[numIntervals * 2] = start;
                        intervals[numIntervals * 2 + 1] = end;
                        numIntervals++;
                    }
                }
            }
        }
        return numIntervals;
    }

    private static int parseMinuteOfDayFast(String time) {
        time = time.trim();
        int colonIdx = time.indexOf(':');
        if (colonIdx < 0) return -1;
        try {
            int h = Integer.parseInt(time.substring(0, colonIdx).trim());
            int m = Integer.parseInt(time.substring(colonIdx + 1).trim());
            if (h <= 24 && m < 60) {
                return h * 60 + m;
            }
        } catch (NumberFormatException ignored) {}
        return -1;
    }

    @Override
    public String toString() {
        return raw;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        OpenHours openHours = (OpenHours) o;
        return Objects.equals(raw, openHours.raw) && Arrays.equals(windows, openHours.windows);
    }

    @Override
    public int hashCode() {
        return Objects.hash(raw, Arrays.hashCode(windows));
    }

    public static final class Serializer extends JsonSerializer<OpenHours> {
        @Override
        public void serialize(OpenHours value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            if (value == null) {
                gen.writeNull();
            } else {
                gen.writeString(value.raw);
            }
        }
    }

    public static final class Deserializer extends JsonDeserializer<OpenHours> {
        @Override
        public OpenHours deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            // Fast path: when the (already unescaped) textual token can be inspected in
            // place, compare it against the thread-local parse cache without materializing
            // a String; on a hit the parser text is exactly the cached key, so returning
            // the cached instance is identical to `parse(getValueAsString())`.
            if (p.currentToken() == JsonToken.VALUE_STRING && p.hasTextCharacters()) {
                char[] buf = p.getTextCharacters();
                int off = p.getTextOffset();
                int len = p.getTextLength();
                Entry e = FAST_CACHE.get();
                if (e.matches(buf, off, len)) {
                    return e.val;
                }
                return OpenHours.parse(p.getText());
            }
            String expr = p.getValueAsString();
            return OpenHours.parse(expr);
        }
    }
}
