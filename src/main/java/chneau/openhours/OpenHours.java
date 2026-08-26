package chneau.openhours;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
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

    private record Entry(String key, OpenHours val) {}

    private final String raw;
    private final TimeWindow[] windows;
    private final long[] bitmask;

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
            FAST_CACHE.set(new Entry(trimmed, cached));
            return cached;
        }

        OpenHours created = parseUncached(trimmed);
        OpenHours prev = INTERN_POOL.putIfAbsent(trimmed, created);
        OpenHours res = (prev != null) ? prev : created;
        FAST_CACHE.set(new Entry(trimmed, res));
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
        if (windows.length == 0) return false;
        if (isAlwaysOpen()) return true;

        int weekMinute = getWeekMinute(dt);
        int word = weekMinute >> 6;
        long mask = 1L << (weekMinute & 63);
        return (bitmask[word] & mask) != 0;
    }

    public boolean match(LocalDateTime dt) {
        return isOpen(dt);
    }

    public Duration getTimeToOpen(LocalDateTime from) {
        if (windows.length == 0) return null;
        if (isAlwaysOpen()) return Duration.ZERO;

        int t = getWeekMinute(from);
        int idx = findFirstWindowStartingAtOrAfter(t);

        int diffMin;
        if (idx < windows.length) {
            TimeWindow w = windows[idx];
            if (w.start <= t) {
                return Duration.ZERO;
            }
            diffMin = w.start - t;
        } else {
            diffMin = (MINUTES_PER_WEEK - t) + windows[0].start;
        }

        int subMinuteSeconds = from.getSecond();
        int subMinuteNanos = from.getNano();
        return Duration.ofMinutes(diffMin).minusSeconds(subMinuteSeconds).minusNanos(subMinuteNanos);
    }

    public Duration getTimeToOpenForDuration(LocalDateTime from, Duration required) {
        if (windows.length == 0) return null;
        long reqNanos = required.toNanos();
        if (reqNanos <= 0) return Duration.ZERO;

        int reqMinutes = (int) ((required.toSeconds() + 59) / 60);
        if (reqMinutes > MINUTES_PER_WEEK) return null;
        if (isAlwaysOpen()) return Duration.ZERO;

        int t = getWeekMinute(from);
        long subDurNanos = from.getSecond() * 1_000_000_000L + from.getNano();
        int startIdx = findFirstWindowStartingAtOrAfter(t);

        int n = windows.length;
        boolean lastEndsAtWeekEnd = windows[n - 1].end == MINUTES_PER_WEEK;
        boolean firstStartsAtZero = windows[0].start == 0;

        for (int i = startIdx; i < n; i++) {
            TimeWindow w = windows[i];
            int effectiveEnd = (i == n - 1 && lastEndsAtWeekEnd && firstStartsAtZero)
                    ? MINUTES_PER_WEEK + windows[0].end
                    : w.end;

            if (t >= w.start) {
                long remNanos = ((long) (effectiveEnd - t)) * 60_000_000_000L - subDurNanos;
                if (remNanos >= reqNanos) {
                    return Duration.ZERO;
                }
            } else {
                if (effectiveEnd - w.start >= reqMinutes) {
                    int diffMin = w.start - t;
                    return Duration.ofMinutes(diffMin)
                            .minusSeconds(from.getSecond())
                            .minusNanos(from.getNano());
                }
            }
        }

        for (int i = 0; i < n; i++) {
            TimeWindow w = windows[i];
            int effectiveEnd = (i == n - 1 && lastEndsAtWeekEnd && firstStartsAtZero)
                    ? MINUTES_PER_WEEK + windows[0].end
                    : w.end;

            if (effectiveEnd - w.start >= reqMinutes) {
                int diffMin = (MINUTES_PER_WEEK - t) + w.start;
                return Duration.ofMinutes(diffMin)
                        .minusSeconds(from.getSecond())
                        .minusNanos(from.getNano());
            }
        }

        return null;
    }

    @Override
    public LocalDateTime when(LocalDateTime from, Duration duration) {
        Duration wait = getTimeToOpenForDuration(from, duration);
        if (wait == null) return null;
        if (wait.isZero()) return from;
        return from.plus(wait);
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

        return dt.plusMinutes(diffMin)
                .minusSeconds(dt.getSecond())
                .minusNanos(dt.getNano());
    }

    public NextDurResult nextDur(LocalDateTime dt) {
        if (windows.length == 0) return new NextDurResult(false, Duration.ZERO);
        if (isAlwaysOpen()) return new NextDurResult(true, Duration.ofDays(365));

        int t = getWeekMinute(dt);
        int idx = findFirstWindowStartingAtOrAfter(t);

        if (idx < windows.length) {
            TimeWindow w = windows[idx];
            if (w.start <= t) {
                // Currently open
                int diffMin = w.end - t;
                if (idx == windows.length - 1 && w.end == MINUTES_PER_WEEK && windows[0].start == 0) {
                    diffMin = (MINUTES_PER_WEEK - t) + windows[0].end;
                }
                Duration dur = Duration.ofMinutes(diffMin)
                        .minusSeconds(dt.getSecond())
                        .minusNanos(dt.getNano());
                return new NextDurResult(true, dur);
            }
            // Currently closed, opens at w.start
            int diffMin = w.start - t;
            Duration dur = Duration.ofMinutes(diffMin)
                    .minusSeconds(dt.getSecond())
                    .minusNanos(dt.getNano());
            return new NextDurResult(false, dur);
        }

        // Currently closed, opens at windows[0].start next week
        int diffMin = (MINUTES_PER_WEEK - t) + windows[0].start;
        Duration dur = Duration.ofMinutes(diffMin)
                .minusSeconds(dt.getSecond())
                .minusNanos(dt.getNano());
        return new NextDurResult(false, dur);
    }

    public NextDateResult nextDate(LocalDateTime dt) {
        NextDurResult res = nextDur(dt);
        LocalDateTime next = res.duration.isZero() ? dt : dt.plus(res.duration);
        return new NextDateResult(res.isOpen, next);
    }

    private static int getWeekMinute(LocalDateTime dt) {
        long localSecs = dt.toEpochSecond(java.time.ZoneOffset.UTC);
        int weekMinute = (int) (((localSecs / 60L) + 4320L) % MINUTES_PER_WEEK);
        return weekMinute >= 0 ? weekMinute : weekMinute + MINUTES_PER_WEEK;
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
        boolean[] minutes = new boolean[MINUTES_PER_WEEK];
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

            for (int d = 0; d < numDays; d++) {
                int day = days[d];
                for (int inter = 0; inter < numIntervals; inter++) {
                    int start = intervals[inter * 2];
                    int end = intervals[inter * 2 + 1];
                    int startMin = day * 1440 + start;

                    if (end > 1440) {
                        int actualEnd = day * 1440 + end;
                        for (int m = startMin; m < actualEnd; m++) {
                            minutes[m % MINUTES_PER_WEEK] = !isOff;
                        }
                    } else if (start > end) {
                        int actualEnd = (day + 1) * 1440 + end;
                        for (int m = startMin; m < actualEnd; m++) {
                            minutes[m % MINUTES_PER_WEEK] = !isOff;
                        }
                    } else {
                        int endMin = day * 1440 + end;
                        for (int m = startMin; m < endMin; m++) {
                            minutes[m % MINUTES_PER_WEEK] = !isOff;
                        }
                    }
                }
            }
        }

        // Bake into disjoint TimeWindows & Bitmask
        long[] bm = new long[BITMASK_WORDS];
        int count = 0;
        int inWindowStart = -1;

        for (int i = 0; i < MINUTES_PER_WEEK; i++) {
            if (minutes[i]) {
                bm[i >> 6] |= (1L << (i & 63));
                if (inWindowStart == -1) inWindowStart = i;
            } else {
                if (inWindowStart != -1) {
                    count++;
                    inWindowStart = -1;
                }
            }
        }
        if (inWindowStart != -1) count++;

        TimeWindow[] winArray = new TimeWindow[count];
        int idx = 0;
        inWindowStart = -1;

        for (int i = 0; i < MINUTES_PER_WEEK; i++) {
            if (minutes[i]) {
                if (inWindowStart == -1) inWindowStart = i;
            } else {
                if (inWindowStart != -1) {
                    winArray[idx++] = new TimeWindow(inWindowStart, i);
                    inWindowStart = -1;
                }
            }
        }
        if (inWindowStart != -1) {
            winArray[idx] = new TimeWindow(inWindowStart, MINUTES_PER_WEEK);
        }

        return new OpenHours(expression, winArray, bm);
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
            String expr = p.getValueAsString();
            return OpenHours.parse(expr);
        }
    }
}
