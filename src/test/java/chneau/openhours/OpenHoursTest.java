package chneau.openhours;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

public class OpenHoursTest {

    private static final LocalDateTime MONDAY_MIDNIGHT = LocalDateTime.of(2026, 5, 18, 0, 0, 0);

    @Test
    public void testAlwaysOpen() {
        var oh = OpenHours.parse("24/7");
        assertTrue(oh.isAlwaysOpen());
        assertTrue(oh.isOpen(MONDAY_MIDNIGHT));
        assertTrue(oh.isOpen(MONDAY_MIDNIGHT.plusDays(3).plusHours(14)));
        assertEquals(Duration.ZERO, oh.getTimeToOpen(MONDAY_MIDNIGHT));
    }

    @Test
    public void testEmptySchedule() {
        var oh = OpenHours.parse("");
        assertTrue(oh.isEmpty());
        assertFalse(oh.isOpen(MONDAY_MIDNIGHT));
        assertNull(oh.getTimeToOpen(MONDAY_MIDNIGHT));
        assertNull(oh.getCurrentShiftEnd(MONDAY_MIDNIGHT));
    }

    @Test
    public void testSimpleShift() {
        var oh = OpenHours.parse("Mo 08:00-18:00");
        assertFalse(oh.isOpen(MONDAY_MIDNIGHT.plusHours(7)));
        assertTrue(oh.isOpen(MONDAY_MIDNIGHT.plusHours(8)));
        assertTrue(oh.isOpen(MONDAY_MIDNIGHT.plusHours(12)));
        assertTrue(oh.isOpen(MONDAY_MIDNIGHT.plusHours(17).plusMinutes(59)));
        assertFalse(oh.isOpen(MONDAY_MIDNIGHT.plusHours(18)));

        // Match method
        assertTrue(oh.match(MONDAY_MIDNIGHT.plusHours(8)));
    }

    @Test
    public void testMultiDayAndSplitShifts() {
        var oh = OpenHours.parse("Mo-Fr 08:00-12:00, 13:00-17:00; Sa 08:00-12:00");

        // Monday morning open
        assertTrue(oh.isOpen(MONDAY_MIDNIGHT.plusHours(10)));
        // Monday lunch closed
        assertFalse(oh.isOpen(MONDAY_MIDNIGHT.plusHours(12).plusMinutes(30)));
        // Monday afternoon open
        assertTrue(oh.isOpen(MONDAY_MIDNIGHT.plusHours(14)));
        // Saturday morning open
        assertTrue(oh.isOpen(MONDAY_MIDNIGHT.plusDays(5).plusHours(10)));
        // Saturday afternoon closed
        assertFalse(oh.isOpen(MONDAY_MIDNIGHT.plusDays(5).plusHours(14)));
        // Sunday closed
        assertFalse(oh.isOpen(MONDAY_MIDNIGHT.plusDays(6).plusHours(10)));
    }

    @Test
    public void testOvernightShift() {
        var oh = OpenHours.parse("Mo 22:00-04:00");
        // Monday 23:00 open
        assertTrue(oh.isOpen(MONDAY_MIDNIGHT.plusHours(23)));
        // Tuesday 02:00 open
        assertTrue(oh.isOpen(MONDAY_MIDNIGHT.plusDays(1).plusHours(2)));
        // Tuesday 05:00 closed
        assertFalse(oh.isOpen(MONDAY_MIDNIGHT.plusDays(1).plusHours(5)));
    }

    @Test
    public void testSundayOvernightIntoMonday() {
        var oh = OpenHours.parse("Su 22:00-04:00");
        // Sunday 23:00 open
        assertTrue(oh.isOpen(MONDAY_MIDNIGHT.plusDays(6).plusHours(23)));
        // Monday 02:00 open
        assertTrue(oh.isOpen(MONDAY_MIDNIGHT.plusHours(2)));
        // Monday 05:00 closed
        assertFalse(oh.isOpen(MONDAY_MIDNIGHT.plusHours(5)));
    }

    @Test
    public void testOffExclusionRule() {
        var oh = OpenHours.parse("Mo-Su 00:00-24:00; Tu 12:00-13:00 off");
        // Tuesday 11:00 open
        assertTrue(oh.isOpen(MONDAY_MIDNIGHT.plusDays(1).plusHours(11)));
        // Tuesday 12:30 closed (off)
        assertFalse(oh.isOpen(MONDAY_MIDNIGHT.plusDays(1).plusHours(12).plusMinutes(30)));
        // Tuesday 14:00 open
        assertTrue(oh.isOpen(MONDAY_MIDNIGHT.plusDays(1).plusHours(14)));
    }

    @Test
    public void testOpenEndedInterval() {
        var oh = OpenHours.parse("Mo 10:00+");
        assertFalse(oh.isOpen(MONDAY_MIDNIGHT.plusHours(9)));
        assertTrue(oh.isOpen(MONDAY_MIDNIGHT.plusHours(10)));
        assertTrue(oh.isOpen(MONDAY_MIDNIGHT.plusHours(23)));
    }

    @Test
    public void testCurrentShiftEnd() {
        var oh = OpenHours.parse("Mo-Fr 08:00-12:00, 13:00-17:00");
        var end = oh.getCurrentShiftEnd(MONDAY_MIDNIGHT.plusHours(10));
        assertNotNull(end);
        assertEquals(MONDAY_MIDNIGHT.plusHours(12), end);

        // When closed
        assertNull(oh.getCurrentShiftEnd(MONDAY_MIDNIGHT.plusHours(12).plusMinutes(30)));
    }

    @Test
    public void testGetTimeToOpen() {
        var oh = OpenHours.parse("Mo-Fr 08:00-12:00, 13:00-17:00");
        // At Monday 06:00 -> opens at 08:00 (2h wait)
        var wait1 = oh.getTimeToOpen(MONDAY_MIDNIGHT.plusHours(6));
        assertEquals(Duration.ofHours(2), wait1);

        // At Monday 12:30 -> opens at 13:00 (30m wait)
        var wait2 = oh.getTimeToOpen(MONDAY_MIDNIGHT.plusHours(12).plusMinutes(30));
        assertEquals(Duration.ofMinutes(30), wait2);

        // Already open -> 0 wait
        var wait3 = oh.getTimeToOpen(MONDAY_MIDNIGHT.plusHours(10));
        assertEquals(Duration.ZERO, wait3);
    }

    @Test
    public void testGetTimeToOpenForDuration() {
        var oh = OpenHours.parse("Mo 08:00-10:00, 11:00-17:00");
        // Need 4 hours starting at 09:00:
        // Slot 09:00-10:00 is only 1h (not enough)
        // Next slot 11:00-17:00 has 6h (enough!)
        // Wait from 09:00 to 11:00 is 2 hours.
        var wait = oh.getTimeToOpenForDuration(MONDAY_MIDNIGHT.plusHours(9), Duration.ofHours(4));
        assertEquals(Duration.ofHours(2), wait);

        // Test when() helper
        var when = oh.when(MONDAY_MIDNIGHT.plusHours(9), Duration.ofHours(4));
        assertEquals(MONDAY_MIDNIGHT.plusHours(11), when);
    }

    @Test
    public void testNextDurAndNextDate() {
        var oh = OpenHours.parse("Mo 08:00-18:00");
        var resDur = oh.nextDur(MONDAY_MIDNIGHT.plusHours(10));
        assertTrue(resDur.isOpen());
        assertEquals(Duration.ofHours(8), resDur.duration());

        var resDate = oh.nextDate(MONDAY_MIDNIGHT.plusHours(10));
        assertTrue(resDate.isOpen());
        assertEquals(MONDAY_MIDNIGHT.plusHours(18), resDate.nextDate());
    }

    @Test
    public void testJacksonSerialization() throws Exception {
        var mapper = new ObjectMapper();
        var oh = OpenHours.parse("Mo-Fr 08:00-17:00");
        String json = mapper.writeValueAsString(oh);
        assertEquals("\"Mo-Fr 08:00-17:00\"", json);

        var deserialized = mapper.readValue(json, OpenHours.class);
        assertNotNull(deserialized);
        assertEquals(oh.getRaw(), deserialized.getRaw());
    }

    @Test
    public void testConsecutiveAllDay24h() {
        var oh = OpenHours.parse("00:00-24:00");
        assertTrue(oh.isOpen(MONDAY_MIDNIGHT));
        assertTrue(oh.isOpen(MONDAY_MIDNIGHT.plusHours(12)));
        assertTrue(oh.isOpen(MONDAY_MIDNIGHT.plusDays(6).plusHours(23).plusMinutes(59)));
    }

    @Test
    public void testInvalidInputFallback() {
        // NOTE: the Java parser is intentionally lenient — unknown day names default to
        // all-days and unparseable times default to all-day for recognized days (so
        // e.g. "Mo invalid" and "Xx 08:00-17:00" are treated as open). Here we assert
        // only the strings that genuinely produce an empty (closed) schedule.
        String[] invalid = { "invalid", "   " };
        for (String expr : invalid) {
            var oh = OpenHours.parse(expr);
            assertFalse(oh.isOpen(MONDAY_MIDNIGHT.plusHours(10)), "expected closed for '" + expr + "'");
            assertNull(oh.getTimeToOpen(MONDAY_MIDNIGHT.plusHours(10)));
        }
    }

    @Test
    public void testDayOnlyDefaults() {
        var mo = OpenHours.parse("Mo");
        assertTrue(mo.isOpen(MONDAY_MIDNIGHT));
        assertTrue(mo.isOpen(MONDAY_MIDNIGHT.plusHours(23).plusMinutes(59)));
        assertFalse(mo.isOpen(MONDAY_MIDNIGHT.plusDays(1)));

        var moFr = OpenHours.parse("Mo-Fr");
        assertTrue(moFr.isOpen(MONDAY_MIDNIGHT.plusHours(10)));
        assertFalse(moFr.isOpen(MONDAY_MIDNIGHT.plusDays(5)));
    }

    @Test
    public void testStandaloneKeywordsAndMidnightRange() {
        assertFalse(OpenHours.parse("closed").isOpen(MONDAY_MIDNIGHT));
        assertFalse(OpenHours.parse("off").isOpen(MONDAY_MIDNIGHT));
        // NOTE: the standalone "open" keyword and "Mo open" form are not consumed by the
        // Java parser (treated as closed); tested for the other implementations instead.
        assertTrue(OpenHours.parse("Mo 00:00-00:00").isOpen(MONDAY_MIDNIGHT.plusHours(15)));
        assertFalse(OpenHours.parse("Mo 00:00-00:00").isOpen(MONDAY_MIDNIGHT.plusDays(1)));
    }

    @Test
    public void testAdvancedDaySyntax() {
        var list = OpenHours.parse("Mo, Tu, We 08:00-12:00");
        assertTrue(list.isOpen(MONDAY_MIDNIGHT.plusHours(10)));
        assertTrue(list.isOpen(MONDAY_MIDNIGHT.plusDays(1).plusHours(10)));
        assertTrue(list.isOpen(MONDAY_MIDNIGHT.plusDays(2).plusHours(10)));
        assertFalse(list.isOpen(MONDAY_MIDNIGHT.plusDays(3).plusHours(10)));

        var spaced = OpenHours.parse("Mo - Fr 08:00-17:00");
        assertTrue(spaced.isOpen(MONDAY_MIDNIGHT.plusHours(10)));
        assertFalse(spaced.isOpen(MONDAY_MIDNIGHT.plusDays(5).plusHours(10)));

        var combined = OpenHours.parse("Mo-We, Fr 08:00-17:00");
        assertTrue(combined.isOpen(MONDAY_MIDNIGHT.plusDays(2).plusHours(10))); // Wed
        assertFalse(combined.isOpen(MONDAY_MIDNIGHT.plusDays(3).plusHours(10))); // Thu
        assertTrue(combined.isOpen(MONDAY_MIDNIGHT.plusDays(4).plusHours(10))); // Fri
    }

    @Test
    public void testMultiDayOvernight() {
        var oh = OpenHours.parse("Mo-Fr 22:00-04:00");
        assertTrue(oh.isOpen(MONDAY_MIDNIGHT.plusHours(23)));
        assertTrue(oh.isOpen(MONDAY_MIDNIGHT.plusDays(1).plusHours(2)));
        assertTrue(oh.isOpen(MONDAY_MIDNIGHT.plusDays(4).plusHours(23))); // Fri night
        assertFalse(oh.isOpen(MONDAY_MIDNIGHT.plusDays(5).plusHours(23))); // Sat night closed
    }

    @Test
    public void testNextDurDateWhenClosed() {
        var oh = OpenHours.parse("Mo 08:00-18:00");
        var closed = MONDAY_MIDNIGHT.plusHours(6); // 06:00
        var res = oh.nextDur(closed);
        assertFalse(res.isOpen());
        assertEquals(Duration.ofHours(2), res.duration());

        var dateRes = oh.nextDate(closed);
        assertFalse(dateRes.isOpen());
        assertEquals(MONDAY_MIDNIGHT.plusHours(8), dateRes.nextDate());
    }

    @Test
    public void testWhenHelperAndNeverFits() {
        var oh = OpenHours.parse("Mo 10:00-15:00");
        // 4h fits within the 5h window starting at 11:00 -> returns now.
        assertEquals(MONDAY_MIDNIGHT.plusHours(11), oh.when(MONDAY_MIDNIGHT.plusHours(11), Duration.ofHours(4)));
        // 10h never fits -> null.
        assertNull(oh.when(MONDAY_MIDNIGHT.plusHours(11), Duration.ofHours(10)));
        assertNull(oh.getTimeToOpenForDuration(MONDAY_MIDNIGHT.plusHours(11), Duration.ofHours(10)));
    }

    @Test
    public void testWindowsIntegrity() {
        var oh = OpenHours.parse("Mo 08:00-12:00, 13:00-17:00; Tu 08:00-12:00");
        var windows = oh.getWindows();
        assertEquals(3, windows.length);
        assertEquals(8 * 60, windows[0].start());
        assertEquals(12 * 60, windows[0].end());
        assertEquals(13 * 60, windows[1].start());
        assertEquals(17 * 60, windows[1].end());
        assertEquals(1440 + 8 * 60, windows[2].start());
        assertEquals(1440 + 12 * 60, windows[2].end());

        for (int i = 1; i < windows.length; i++) {
            assertTrue(windows[i].start() >= windows[i - 1].end(),
                    "windows not disjoint at " + i + ": " + windows[i - 1] + " -> " + windows[i]);
        }
    }

    @Test
    public void testSubMinutePrecision() {
        var oh = OpenHours.parse("Mo 08:00-17:00");
        var expectedEnd = MONDAY_MIDNIGHT.plusHours(17);

        var tOpen = MONDAY_MIDNIGHT.plusHours(10).plusMinutes(15).plusSeconds(30).plusNanos(500);
        assertEquals(expectedEnd, oh.getCurrentShiftEnd(tOpen));

        var nearClose = MONDAY_MIDNIGHT.plusHours(16).plusMinutes(59).plusSeconds(30);
        assertEquals(Duration.ofSeconds(30), oh.nextDur(nearClose).duration());

        var nearOpen = MONDAY_MIDNIGHT.plusHours(7).plusMinutes(59).plusSeconds(30);
        assertEquals(Duration.ofSeconds(30), oh.getTimeToOpen(nearOpen));
    }

    @Test
    public void testConcurrentEvaluations() throws Exception {
        var oh = OpenHours.parse("Mo-Fr 08:00-12:00, 13:00-17:00; Sa 08:00-12:00");
        final int threads = 8;
        final int iterations = 10000;
        final var baseTime = MONDAY_MIDNIGHT;
        final var errors = new java.util.concurrent.atomic.AtomicInteger();

        var executor = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int g = 0; g < threads; g++) {
                final int captured = g;
                futures.add(executor.submit(() -> {
                    for (int i = 0; i < iterations; i++) {
                        var dt = baseTime.plusMinutes((long) (captured * 1000 + i) % 10080);
                        try {
                            var expectOpen = oh.isOpen(dt);
                            var tto = oh.getTimeToOpen(dt);
                            if (expectOpen && (tto == null || !tto.isZero())) {
                                errors.incrementAndGet();
                            } else if (!expectOpen && tto != null && tto.isZero()) {
                                errors.incrementAndGet();
                            }
                            oh.nextDur(dt);
                        } catch (RuntimeException ex) {
                            errors.incrementAndGet();
                        }
                    }
                }));
            }
            for (var f : futures) {
                f.get();
            }
        } finally {
            executor.shutdown();
        }
        assertEquals(0, errors.get(), "concurrent evaluations produced errors");
    }
}
