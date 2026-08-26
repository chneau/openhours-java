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
}
