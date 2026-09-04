package chneau.openhours;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import com.sun.management.ThreadMXBean;
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

        ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();

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
        long alloc0 = threadBean.getThreadAllocatedBytes(Thread.currentThread().threadId());
        long t0 = System.nanoTime();
        for (int i = 0; i < iterations * 10 * benchScale; i++) {
            oh.isOpen(start.plusMinutes(i));
        }
        long t1 = System.nanoTime();
        long alloc1 = threadBean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - alloc0;
        double d1 = (t1 - t0) / 1_000_000.0;
        double b1 = (double) alloc1 / (iterations * 10 * benchScale);
        out.printf("1. IsOpen (100k rolling calls):            %4.0f ms (%.5f us/op, %.1f B/op)%n", d1, ((t1 - t0) / 1000.0) / (iterations * 10 * benchScale), b1);

        // 2. Benchmark IsOpen (Pure 1M calls with fixed timestamp)
        alloc0 = threadBean.getThreadAllocatedBytes(Thread.currentThread().threadId());
        t0 = System.nanoTime();
        for (int i = 0; i < 1_000_000 * benchScale; i++) {
            oh.isOpen(fixedTime);
        }
        t1 = System.nanoTime();
        long alloc2 = threadBean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - alloc0;
        double d2 = (t1 - t0) / 1_000_000.0;
        double b2 = (double) alloc2 / (1_000_000 * benchScale);
        out.printf("2. IsOpen (1M pure calls):                 %4.0f ms (%.5f us/op, %.1f B/op)%n", d2, ((t1 - t0) / 1000.0) / (1_000_000 * benchScale), b2);

        // 3. Benchmark GetTimeToOpen (10k calls)
        alloc0 = threadBean.getThreadAllocatedBytes(Thread.currentThread().threadId());
        t0 = System.nanoTime();
        for (int i = 0; i < iterations * benchScale; i++) {
            oh.getTimeToOpen(start.plusHours(i % 168));
        }
        t1 = System.nanoTime();
        long alloc3 = threadBean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - alloc0;
        double d3 = (t1 - t0) / 1_000_000.0;
        double b3 = (double) alloc3 / (iterations * benchScale);
        out.printf("3. GetTimeToOpen (10k calls):              %4.0f ms (%.5f us/op, %.1f B/op)%n", d3, ((t1 - t0) / 1000.0) / (iterations * benchScale), b3);

        // 4. Benchmark GetTimeToOpenForDuration 4h (10k calls)
        alloc0 = threadBean.getThreadAllocatedBytes(Thread.currentThread().threadId());
        t0 = System.nanoTime();
        for (int i = 0; i < iterations * benchScale; i++) {
            oh.getTimeToOpenForDuration(start.plusHours(i % 168), fourHours);
        }
        t1 = System.nanoTime();
        long alloc4 = threadBean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - alloc0;
        double d4 = (t1 - t0) / 1_000_000.0;
        double b4 = (double) alloc4 / (iterations * benchScale);
        out.printf("4. GetTimeToOpenForDuration 4h (10k calls):%4.0f ms (%.5f us/op, %.1f B/op)%n", d4, ((t1 - t0) / 1000.0) / (iterations * benchScale), b4);

        // 5. Benchmark When 4h (10k calls)
        alloc0 = threadBean.getThreadAllocatedBytes(Thread.currentThread().threadId());
        t0 = System.nanoTime();
        for (int i = 0; i < iterations * benchScale; i++) {
            oh.when(start.plusHours(i % 168), fourHours);
        }
        t1 = System.nanoTime();
        long alloc5 = threadBean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - alloc0;
        double d5 = (t1 - t0) / 1_000_000.0;
        double b5 = (double) alloc5 / (iterations * benchScale);
        out.printf("5. When 4h (10k calls):                    %4.0f ms (%.5f us/op, %.1f B/op)%n", d5, ((t1 - t0) / 1000.0) / (iterations * benchScale), b5);

        // 6. Benchmark NextDur (10k calls)
        alloc0 = threadBean.getThreadAllocatedBytes(Thread.currentThread().threadId());
        t0 = System.nanoTime();
        for (int i = 0; i < iterations * benchScale; i++) {
            oh.nextDur(start.plusHours(i % 168));
        }
        t1 = System.nanoTime();
        long alloc6 = threadBean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - alloc0;
        double d6 = (t1 - t0) / 1_000_000.0;
        double b6 = (double) alloc6 / (iterations * benchScale);
        out.printf("6. NextDur (10k calls):                    %4.0f ms (%.5f us/op, %.1f B/op)%n", d6, ((t1 - t0) / 1000.0) / (iterations * benchScale), b6);

        // 7. Benchmark NextDate (10k calls)
        alloc0 = threadBean.getThreadAllocatedBytes(Thread.currentThread().threadId());
        t0 = System.nanoTime();
        for (int i = 0; i < iterations * benchScale; i++) {
            oh.nextDate(start.plusHours(i % 168));
        }
        t1 = System.nanoTime();
        long alloc7 = threadBean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - alloc0;
        double d7 = (t1 - t0) / 1_000_000.0;
        double b7 = (double) alloc7 / (iterations * benchScale);
        out.printf("7. NextDate (10k calls):                   %4.0f ms (%.5f us/op, %.1f B/op)%n", d7, ((t1 - t0) / 1000.0) / (iterations * benchScale), b7);

        // 8. Benchmark Parse Cached (1k calls)
        alloc0 = threadBean.getThreadAllocatedBytes(Thread.currentThread().threadId());
        t0 = System.nanoTime();
        for (int i = 0; i < 1000 * benchScale; i++) {
            OpenHours.parse(complexExpr);
        }
        t1 = System.nanoTime();
        long alloc8 = threadBean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - alloc0;
        double d8 = (t1 - t0) / 1_000_000.0;
        double b8 = (double) alloc8 / (1000 * benchScale);
        out.printf("8. Parse Cached (1k calls):                %4.0f ms (%.5f us/op, %.1f B/op)%n", d8, ((t1 - t0) / 1000.0) / (1000 * benchScale), b8);

        // 9. Benchmark JSON Deserialization (1k calls)
        alloc0 = threadBean.getThreadAllocatedBytes(Thread.currentThread().threadId());
        t0 = System.nanoTime();
        for (int i = 0; i < 1000 * benchScale; i++) {
            mapper.readValue(jsonStr, OpenHours.class);
        }
        t1 = System.nanoTime();
        long alloc9 = threadBean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - alloc0;
        double d9 = (t1 - t0) / 1_000_000.0;
        double b9 = (double) alloc9 / (1000 * benchScale);
        out.printf("9. JSON Deserialize (1k calls):            %4.0f ms (%.5f us/op, %.1f B/op)%n", d9, ((t1 - t0) / 1000.0) / (1000 * benchScale), b9);

        // 10. Simulation Stress Test (5,000 unique objects)
        alloc0 = threadBean.getThreadAllocatedBytes(Thread.currentThread().threadId());
        t0 = System.nanoTime();
        int stressCount = 5000 * benchScale;
        List<OpenHours> locations = new ArrayList<>(stressCount);
        for (int i = 0; i < stressCount; i++) {
            int hStart = 8 + (i % 60) / 60;
            int mStart = i % 60;
            int hEnd = 17 + (i % 60) / 60;
            int mEnd = i % 60;
            String expr = String.format("Mo-Fr %02d:%02d-%02d:%02d", hStart, mStart, hEnd, mEnd);
            locations.add(OpenHours.parse(expr));
        }
        t1 = System.nanoTime();
        long alloc10 = threadBean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - alloc0;
        double d10 = (t1 - t0) / 1_000_000.0;
        double b10 = (double) alloc10 / stressCount;
        out.printf("10. Stress Test (5,000 unique objects):    %4.0f ms (%.4f ms/obj, %.1f B/obj)%n", d10, d10 / stressCount, b10);
        out.println("========================================================");
    }
}
