package chneau.openhours;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.PrintStream;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class OpenHoursBenchmarks {

    public static void main(String[] args) throws Exception {
        runBenchmarks(System.out);
    }

    public static void runBenchmarks(PrintStream out) throws Exception {
        out.println("========================================================");
        out.println("Running OpenHours Benchmarks (Java 26 Standard Suite)");
        out.println("========================================================");

        String complexExpr = "Mo-Fr 08:00-12:00, 13:00-17:00; Sa 08:00-12:00";
        var oh = OpenHours.parse(complexExpr);
        var start = LocalDateTime.of(2026, 5, 18, 0, 0, 0);
        var fixedTime = LocalDateTime.of(2026, 5, 18, 10, 0, 0);
        int iterations = 10000;
        var fourHours = Duration.ofHours(4);
        var mapper = new ObjectMapper();
        // Multiply iteration counts by this factor so benchmarks run longer and
        // yield more stable per-op timings across all workloads.
        final int benchScale = 10;

        // Warm-up C2 JIT Compilation
        String jsonStr = "\"" + complexExpr + "\"";
        for (int i = 0; i < 20000; i++) {
            oh.isOpen(start.plusMinutes(i % 168));
            oh.getTimeToOpen(start.plusHours(i % 168));
            OpenHours.parse(complexExpr);
            mapper.readValue(jsonStr, OpenHours.class);
        }

        // 1. Benchmark IsOpen (Rolling 100k calls)
        long t0 = System.nanoTime();
        for (int i = 0; i < iterations * 10 * benchScale; i++) {
            oh.isOpen(start.plusMinutes(i));
        }
        long t1 = System.nanoTime();
        double d1 = (t1 - t0) / 1_000_000.0;
        out.printf("1. IsOpen (100k rolling calls):            %4.0f ms (%.3f us/op)%n", d1, ((t1 - t0) / 1000.0) / (iterations * 10 * benchScale));

        // 2. Benchmark IsOpen (Pure 1M calls with fixed timestamp)
        t0 = System.nanoTime();
        for (int i = 0; i < 1_000_000 * benchScale; i++) {
            oh.isOpen(fixedTime);
        }
        t1 = System.nanoTime();
        double d2 = (t1 - t0) / 1_000_000.0;
        out.printf("2. IsOpen (1M pure calls):                 %4.0f ms (%.3f us/op)%n", d2, ((t1 - t0) / 1000.0) / (1_000_000 * benchScale));

        // 3. Benchmark GetTimeToOpen (10k calls)
        t0 = System.nanoTime();
        for (int i = 0; i < iterations * benchScale; i++) {
            oh.getTimeToOpen(start.plusHours(i % 168));
        }
        t1 = System.nanoTime();
        double d3 = (t1 - t0) / 1_000_000.0;
        out.printf("3. GetTimeToOpen (10k calls):              %4.0f ms (%.3f us/op)%n", d3, ((t1 - t0) / 1000.0) / (iterations * benchScale));

        // 4. Benchmark GetTimeToOpenForDuration 4h (10k calls)
        t0 = System.nanoTime();
        for (int i = 0; i < iterations * benchScale; i++) {
            oh.getTimeToOpenForDuration(start.plusHours(i % 168), fourHours);
        }
        t1 = System.nanoTime();
        double d4 = (t1 - t0) / 1_000_000.0;
        out.printf("4. GetTimeToOpenForDuration 4h (10k calls):%4.0f ms (%.3f us/op)%n", d4, ((t1 - t0) / 1000.0) / (iterations * benchScale));

        // 5. Benchmark When 4h (10k calls)
        t0 = System.nanoTime();
        for (int i = 0; i < iterations * benchScale; i++) {
            oh.when(start.plusHours(i % 168), fourHours);
        }
        t1 = System.nanoTime();
        double d5 = (t1 - t0) / 1_000_000.0;
        out.printf("5. When 4h (10k calls):                    %4.0f ms (%.3f us/op)%n", d5, ((t1 - t0) / 1000.0) / (iterations * benchScale));

        // 6. Benchmark NextDur (10k calls)
        t0 = System.nanoTime();
        for (int i = 0; i < iterations * benchScale; i++) {
            oh.nextDur(start.plusHours(i % 168));
        }
        t1 = System.nanoTime();
        double d6 = (t1 - t0) / 1_000_000.0;
        out.printf("6. NextDur (10k calls):                    %4.0f ms (%.3f us/op)%n", d6, ((t1 - t0) / 1000.0) / (iterations * benchScale));

        // 7. Benchmark NextDate (10k calls)
        t0 = System.nanoTime();
        for (int i = 0; i < iterations * benchScale; i++) {
            oh.nextDate(start.plusHours(i % 168));
        }
        t1 = System.nanoTime();
        double d7 = (t1 - t0) / 1_000_000.0;
        out.printf("7. NextDate (10k calls):                   %4.0f ms (%.3f us/op)%n", d7, ((t1 - t0) / 1000.0) / (iterations * benchScale));

        // 8. Benchmark Parse Cached (1k calls)
        t0 = System.nanoTime();
        for (int i = 0; i < 1000 * benchScale; i++) {
            OpenHours.parse(complexExpr);
        }
        t1 = System.nanoTime();
        double d8 = (t1 - t0) / 1_000_000.0;
        out.printf("8. Parse Cached (1k calls):                %4.0f ms (%.3f us/op)%n", d8, ((t1 - t0) / 1000.0) / (1000 * benchScale));

        // 9. Benchmark JSON Deserialization (1k calls)
        t0 = System.nanoTime();
        for (int i = 0; i < 1000 * benchScale; i++) {
            mapper.readValue(jsonStr, OpenHours.class);
        }
        t1 = System.nanoTime();
        double d9 = (t1 - t0) / 1_000_000.0;
        out.printf("9. JSON Deserialize (1k calls):            %4.0f ms (%.3f us/op)%n", d9, ((t1 - t0) / 1000.0) / (1000 * benchScale));

        // 10. Simulation Stress Test (5,000 unique objects)
        t0 = System.nanoTime();
        List<OpenHours> locations = new ArrayList<>(5000 * benchScale);
        for (int i = 0; i < 5000 * benchScale; i++) {
            int hStart = 8 + (i % 60) / 60;
            int mStart = i % 60;
            int hEnd = 17 + (i % 60) / 60;
            int mEnd = i % 60;
            String expr = String.format("Mo-Fr %02d:%02d-%02d:%02d", hStart, mStart, hEnd, mEnd);
            locations.add(OpenHours.parse(expr));
        }
        t1 = System.nanoTime();
        double d10 = (t1 - t0) / 1_000_000.0;
        out.printf("10. Stress Test (5,000 unique objects):    %4.0f ms (%.4f ms/obj)%n", d10, d10 / (5000 * benchScale));
        out.println("========================================================");
    }
}
