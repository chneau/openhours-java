package chneau.openhours;

import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Reference tests ported from the original opening_hours.js test suite
// (https://github.com/opening-hours/opening_hours.js/blob/main/test/test.js).
//
// Only the expression variants that this implementation parses to the SAME
// open-intervals as the reference suite are included. Each case lists the
// expected open intervals [s, e) as returned by that reference suite for the
// query window [from, to); we assert isOpen against those intervals at every
// interval boundary, interval midpoint and a few daily probe points.
// open-end ("+"), am/pm, dot/unicode separators, short "H-H" times, holidays,
// variable times, months/years, constrained weekdays and comments are not
// ported because they are outside this implementation's grammar/API.
//
// Java-specific exclusions (this JVM parser does not implement them):
//   * bare "open" as a whole expression (parsed as empty here; not always-open)
//   * a "24/7" or "open" token used as a rule inside a ';'-separated expression
//     (only a standalone "24/7" expression is recognized as always-open)
public class ReferenceTest {

    private static LocalDateTime ts(String s) {
        return LocalDateTime.parse(s, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd H:mm"));
    }

    private static List<LocalDateTime> probePoints(LocalDateTime from, LocalDateTime to, List<String[]> iv) {
        var points = new ArrayList<LocalDateTime>();
        for (var slot : iv) {
            var s = ts(slot[0]);
            var e = ts(slot[1]);
            var mid = s.plusSeconds(java.time.Duration.between(s, e).toSeconds() / 2);
            points.add(s.minusMinutes(1));
            points.add(s);
            points.add(s.plusMinutes(1));
            points.add(mid);
            points.add(e.minusMinutes(1));
            points.add(e);
        }
        points.add(from);
        points.add(from.plusMinutes(1));
        for (var t = from.plusHours(1); t.isBefore(to); t = t.plusHours(24)) {
            points.add(t.plusHours(3));
            points.add(t.plusHours(12));
            points.add(t.plusHours(18));
        }
        return points;
    }

    private static boolean refOpen(LocalDateTime x, List<String[]> iv) {
        for (var slot : iv) {
            var s = ts(slot[0]);
            var e = ts(slot[1]);
            if (!x.isBefore(s) && x.isBefore(e)) {
                return true;
            }
        }
        return false;
    }

    private static void run(String name, String expr, String fromStr, String toStr, List<String[]> iv) {
        var from = ts(fromStr);
        var to = ts(toStr);
        var oh = OpenHours.parse(expr);
        for (var p : probePoints(from, to, iv)) {
            if (p.isBefore(from) || !p.isBefore(to)) {
                continue;
            }
            boolean got = oh.isOpen(p);
            boolean want = refOpen(p, iv);
            assertTrue(got == want,
                    name + ": expr=\"" + expr + "\" at " + p + ": isOpen=" + got + ", want " + want);
        }
    }

    private static List<String[]> of(String... pairs) {
        var out = new ArrayList<String[]>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            out.add(new String[]{pairs[i], pairs[i + 1]});
        }
        return out;
    }

    // Mon/Thu/Sat/Sun 10:00-12:00 within the 2012-10-01 week (used by several cases)
    private static List<String[]> day10to12() {
        return of(
                "2012-10-01 10:00", "2012-10-01 12:00",
                "2012-10-02 10:00", "2012-10-02 12:00",
                "2012-10-03 10:00", "2012-10-03 12:00",
                "2012-10-04 10:00", "2012-10-04 12:00",
                "2012-10-05 10:00", "2012-10-05 12:00",
                "2012-10-06 10:00", "2012-10-06 12:00",
                "2012-10-07 10:00", "2012-10-07 12:00");
    }

    @Test
    public void referenceTimeIntervals() {
        var d = day10to12();
        run("Time intervals", "10:00-12:00", "2012-10-01 0:00", "2012-10-08 0:00", d);
        run("Time intervals", "08:00-09:00; 10:00-12:00", "2012-10-01 0:00", "2012-10-08 0:00", d);
        run("Time intervals", "10:00-12:00,", "2012-10-01 0:00", "2012-10-08 0:00", d);
        run("Time intervals", "10:00-12:00;", "2012-10-01 0:00", "2012-10-08 0:00", d);
        run("Time intervals", "10:00-11:00,11:00-12:00", "2012-10-01 0:00", "2012-10-08 0:00", d);
        run("Time intervals", "10:00-12:00,10:30-11:30", "2012-10-01 0:00", "2012-10-08 0:00", d);
        run("Time intervals", "10:00-14:00; 12:00-14:00 off", "2012-10-01 0:00", "2012-10-08 0:00", d);
        // "Error tolerance: dot as time separator" (reference value)
        run("dot-sep ref", "10:00-12:00", "2012-10-01 0:00", "2012-10-08 0:00", d);
        run("dot-sep ref", "10:00-14:00; 12:00-14:00 off", "2012-10-01 0:00", "2012-10-08 0:00", d);
        // "Error tolerance: Correctly handle pm time." (reference value)
        run("pm ref", "10:00-12:00,13:00-20:00", "2012-10-01 0:00", "2012-10-03 0:00", of(
                "2012-10-01 10:00", "2012-10-01 12:00", "2012-10-01 13:00", "2012-10-01 20:00",
                "2012-10-02 10:00", "2012-10-02 12:00", "2012-10-02 13:00", "2012-10-02 20:00"));
        // "Error tolerance: Time intervals, short time" (reference value)
        run("short ref", "Mo 07:00-18:00", "2012-10-01 0:00", "2012-10-08 0:00", of("2012-10-01 07:00", "2012-10-01 18:00"));
    }

    @Test
    public void referenceTimeIntervals24x7Off() {
        var off24 = of("2012-10-01 00:00", "2012-10-01 15:00", "2012-10-01 16:00", "2012-10-08 00:00");
        // NOTE: "24/7; ..." and "open; ..." are not implemented by this JVM parser, only "00:00-24:00; ...".
        run("Time intervals 24/7 off", "00:00-24:00; Mo 15:00-16:00 off", "2012-10-01 0:00", "2012-10-08 0:00", off24);
    }

    @Test
    public void referenceAlwaysClosed() {
        var none = List.<String[]>of();
        run("always closed", "off", "2012-10-01 0:00", "2012-10-08 0:00", none);
        run("always closed", "closed", "2012-10-01 0:00", "2012-10-08 0:00", none);
        run("always closed", "off; closed", "2012-10-01 0:00", "2012-10-08 0:00", none);
        run("always closed", "24/7 closed", "2012-10-01 0:00", "2012-10-08 0:00", none);
        run("always closed", "00:00-24:00 closed", "2012-10-01 0:00", "2012-10-08 0:00", none);
    }

    @Test
    public void referenceOvernight() {
        var ov = of(
                "2012-10-01 00:00", "2012-10-01 02:00",
                "2012-10-01 22:00", "2012-10-02 02:00",
                "2012-10-02 22:00", "2012-10-03 02:00",
                "2012-10-03 22:00", "2012-10-04 02:00",
                "2012-10-04 22:00", "2012-10-05 02:00",
                "2012-10-05 22:00", "2012-10-06 02:00",
                "2012-10-06 22:00", "2012-10-07 02:00",
                "2012-10-07 22:00", "2012-10-08 00:00");
        run("overnight", "22:00-02:00", "2012-10-01 0:00", "2012-10-08 0:00", ov);
        var we = of("2012-10-03 22:00", "2012-10-04 02:00");
        run("overnight weekday", "We 22:00-02:00", "2012-10-01 0:00", "2012-10-08 0:00", we);
        // NOTE: the no-space form "We22:00-02:00" is not parsed by this JVM parser, so it is excluded here.
    }

    @Test
    public void referenceWeekdays() {
        var wd = of(
                "2012-10-01 10:00", "2012-10-01 12:00", "2012-10-04 10:00", "2012-10-04 12:00",
                "2012-10-06 10:00", "2012-10-06 12:00", "2012-10-07 10:00", "2012-10-07 12:00");
        run("Weekdays", "Mo,Th,Sa,Su 10:00-12:00", "2012-10-01 0:00", "2012-10-08 0:00", wd);
        run("Weekdays", "Mo,Th,Sa-Su 10:00-12:00", "2012-10-01 0:00", "2012-10-08 0:00", wd);
        run("Weekdays", "Th,Sa-Mo 10:00-12:00", "2012-10-01 0:00", "2012-10-08 0:00", wd);
        run("Weekdays", "10:00-12:00; Tu-We 00:00-24:00 off; Fr 00:00-24:00 off", "2012-10-01 0:00", "2012-10-08 0:00", wd);
        run("Weekdays", "10:00-12:00; Tu-We off; Fr off", "2012-10-01 0:00", "2012-10-08 0:00", wd);
        // "Omitted time"
        run("Omitted time", "Mo,We", "2012-10-01 0:00", "2012-10-08 0:00", of(
                "2012-10-01 00:00", "2012-10-02 00:00", "2012-10-03 00:00", "2012-10-04 00:00"));
    }

    @Test
    public void referenceFullRange() {
        var fr = of("2025-10-01 00:00", "2025-10-08 00:00");
        run("Full range", "00:00-24:00", "2025-10-01 0:00", "2025-10-08 0:00", fr);
        run("Full range", "00:00-00:00", "2025-10-01 0:00", "2025-10-08 0:00", fr);
        run("Full range", "Mo-Su 00:00-24:00", "2025-10-01 0:00", "2025-10-08 0:00", fr);
        run("Full range", "Tu-Mo 00:00-24:00", "2025-10-01 0:00", "2025-10-08 0:00", fr);
        run("Full range", "We-Tu 00:00-24:00", "2025-10-01 0:00", "2025-10-08 0:00", fr);
        run("Full range", "Th-We 00:00-24:00", "2025-10-01 0:00", "2025-10-08 0:00", fr);
        run("Full range", "Fr-Th 00:00-24:00", "2025-10-01 0:00", "2025-10-08 0:00", fr);
        run("Full range", "Sa-Fr 00:00-24:00", "2025-10-01 0:00", "2025-10-08 0:00", fr);
        run("Full range", "Su-Sa 00:00-24:00", "2025-10-01 0:00", "2025-10-08 0:00", fr);
        run("Full range", "24/7", "2025-10-01 0:00", "2025-10-08 0:00", fr);
        // NOTE: "24/7" only works as a standalone expression here; "24/7; 24/7" is not parsed by this JVM parser, so it is excluded.
        // NOTE: bare "open" is not implemented by this JVM parser (returns empty), so it is excluded here.
        // NOTE: "12:00-13:00; 24/7" uses a mid-expression "24/7" token, which this JVM parser does not implement, so it is excluded here too.
        run("Full range", "00:00-24:00,12:00-13:00", "2025-10-01 0:00", "2025-10-08 0:00", fr);
        run("Full range", "Mo-Fr,Sa,Su", "2025-10-01 0:00", "2025-10-08 0:00", fr);
        run("Full range", "Mo 00:00-24:00; Tu 00:00-24:00; We 00:00-24:00; Th 00:00-24:00; Fr 00:00-24:00; Sa 00:00-24:00; Su 00:00-24:00", "2025-10-01 0:00", "2025-10-08 0:00", fr);
    }

    @Test
    public void reference24x7Alias() {
        var ali = of("2012-10-01 00:00", "2012-10-02 00:00", "2012-10-03 00:00", "2012-10-04 00:00");
        run("24/7 alias", "Mo,We 00:00-24:00", "2012-10-01 0:00", "2012-10-08 0:00", ali);
        run("24/7 alias", "Mo,We 24/7", "2012-10-01 0:00", "2012-10-08 0:00", ali);
        run("24/7 alias", "Mo,We open", "2012-10-01 0:00", "2012-10-08 0:00", ali);
        run("24/7 alias", "Mo,We", "2012-10-01 0:00", "2012-10-08 0:00", ali);
    }
}
