package chneau.openhours;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public final class OpenHours {
    private static final Map<String, Integer> weekDays = Map.ofEntries(Map.entry("su", 0), Map.entry("mo", 1),
            Map.entry("tu", 2), Map.entry("we", 3), Map.entry("th", 4), Map.entry("fr", 5), Map.entry("sa", 6));

    private final List<LocalDateTime> ldts = new ArrayList<>();

    private static String clean(String str) {
        return str.trim().toLowerCase().replaceAll(" ,", ",").replaceAll(", ", ",");
    }

    private static List<Integer> simplifyDays(String input) {
        var days = new HashSet<Integer>();
        for (String str : input.split(",")) {
            var strLen = str.length();
            switch (strLen) {
            case 2: // "mo"
                if (OpenHours.weekDays.containsKey(str)) {
                    days.add((OpenHours.weekDays.get(str)));
                }
                break;
            case 5: // "tu-fr"
                var strs = str.split("-");
                if (!OpenHours.weekDays.containsKey(strs[0])) {
                    break;
                }
                var from = OpenHours.weekDays.get(strs[0]);
                if (!OpenHours.weekDays.containsKey(strs[1])) {
                    break;
                }
                var to = OpenHours.weekDays.get(strs[1]);
                if (to < from) {
                    to += 7;
                }
                for (int i = from; i <= to; i++) {
                    days.add(i % 7);
                }
                break;
            default:
                break;
            }
        }
        var simple = new ArrayList<Integer>(days);
        Collections.sort(simple);
        return simple;
    }

    // this is needed because hour can be 24, which is handled in newDate
    private static SimpleEntry<Integer, Integer> simplifyHours(String input) {
        var strs = input.split(":");
        if (strs.length != 2) {
            throw new IllegalArgumentException("input malformed");
        }
        return new SimpleEntry<>(Integer.valueOf(strs[0]), Integer.valueOf(strs[1]));
    }

    // Set at 2017/01, because it starts a monday
    private static LocalDateTime newDate(int day, int hour, int minute) {
        if (hour == 24) {
            ++day;
            hour = 0;
        }
        return LocalDateTime.of(2017, 1, day + 1, hour, minute);
    }

    // Offset a time
    private static LocalDateTime newDateFromLDT(LocalDateTime other) {
        return newDate(other.getDayOfWeek().getValue(), other.getHour(), other.getMinute());
    }

    private void buildTimes(String input) {
        var inputLen = input.length();
        if (inputLen > 0 && input.charAt(inputLen - 1) == ';') {
            input = input.substring(0, inputLen - 1);
        }
        if (input == "") {
            input = "su-sa 00:00-24:00";
        }
        for (String str : clean(input).split(";")) {
            var strs = str.split("\\s+");
            var days = simplifyDays(strs[0]);
            for (String s : strs[1].split(",")) {
                var times = s.split("-");
                var from = simplifyHours(times[0]);
                var to = simplifyHours(times[1]);
                for (Integer day : days) {
                    this.ldts.add(newDate(day, from.getKey(), from.getValue()));
                    this.ldts.add(newDate(day, to.getKey(), to.getValue()));
                }
            }
        }
    }

    private List<LocalDateTime> merge4(LocalDateTime... dateTimes) {
        for (int i = 0; i < dateTimes.length - 1; i++) {
            if (dateTimes[i].isAfter(dateTimes[i + 1]) || dateTimes[i].equals(dateTimes[i + 1])) {
                Arrays.sort(dateTimes);
                return List.of(dateTimes[0], dateTimes[dateTimes.length - 1]);
            }
        }
        return null;
    }

    private void merge() {
        Collections.sort(ldts);
        for (int i = 0; i < ldts.size(); i += 2) {
            for (int j = i + 2; j < ldts.size(); j += 2) {
                var res = merge4(ldts.get(i), ldts.get(i + 1), ldts.get(j), ldts.get(j + 1));
                if (res == null) {
                    continue;
                }
                ldts.set(i, res.get(0));
                ldts.set(i + 1, res.get(1));
                ldts.remove(j);
                ldts.remove(j);
                break;
            }
        }
    }

    public boolean match(LocalDateTime ldt) {
        var t = newDateFromLDT(ldt);
        var i = matchIndex(t);
        return i % 2 == 1;
    }

    public Duration nextDur(LocalDateTime ldt) {
        var x = newDateFromLDT(ldt);
        var i = matchIndex(x);
        if (i == this.ldts.size()) {
            i = 0;
        }
        var xi = this.ldts.get(i);
        if (x.isAfter(xi)) {
            xi = xi.plusDays(7);
        }
        return Duration.between(xi, x);
    }

    public LocalDateTime when(LocalDateTime ldt, Duration d) {
        var x = newDateFromLDT(ldt);
        var i = matchIndex(x);
        LocalDateTime found = null;
        if (i % 2 == 1) {
            var newO = x.plus(d);
            if (newO.isBefore(this.ldts.get(i)) || newO.equals(this.ldts.get(i))) {
                found = x;
            } else {
                i += 2;
            }
        } else {
            ++i;
        }
        for (int max = i + this.ldts.size(); i < max && found == null; i += 2) {
            var newI = i % this.ldts.size();
            var newO = this.ldts.get(newI - 1).plus(d);
            if (newO.isBefore(this.ldts.get(newI)) || newO.equals(this.ldts.get(newI))) {
                found = this.ldts.get(newI - 1);
            }
        }
        if (found == null) {
            return found;
        }
        if (x.isAfter(found)) {
            found = found.plusDays(7);
        }
        return found;
    }

    public LocalDateTime nextDate(LocalDateTime ldt) {
        var dur = nextDur(ldt);
        return ldt.plus(dur);
    }

    private int matchIndex(LocalDateTime ldt) {
        var i = 0;
        for (; i < ldts.size(); i++) {
            if (ldts.get(i).isAfter(ldt)) {
                break;
            }
        }
        return i;
    }

    private OpenHours() {
    }

    public static final OpenHours Parse(String input) {
        var oh = new OpenHours();
        oh.buildTimes(input);
        oh.merge();
        return oh;
    }

    public boolean someLibraryMethod() {
        return true;
    }
}
