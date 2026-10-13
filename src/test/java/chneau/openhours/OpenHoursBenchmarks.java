package chneau.openhours;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import com.sun.management.ThreadMXBean;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.function.IntConsumer;

public class OpenHoursBenchmarks {

    private static final double TARGET_MS = 500.0;

    private record Measurement(double ms, long alloc, long total) {}

    // Runs op(0..baseCount-1) repeatedly until a batch runs for at least
    // TARGET_MS, then keeps taking batches until the measured region reaches
    // TARGET_MS. Returns measured ms, bytes allocated, and total op count, so
    // per-op numbers are not dominated by clock granularity.
    private static Measurement measure(int baseCount, ThreadMXBean threadBean, IntConsumer op) {
        long reps = 1;
        while (true) {
            long t0 = System.nanoTime();
            for (long r = 0; r < reps; r++) {
                for (int i = 0; i < baseCount; i++) {
                    op.accept(i);
                }
            }
            double el = (System.nanoTime() - t0) / 1_000_000.0;
            if (el >= TARGET_MS || reps > (1L << 34) / baseCount) {
                break;
            }
            long next = (long) (reps * TARGET_MS / el) + 1;
            if (next <= reps) {
                next = reps + 1;
            }
            reps = next;
        }

        long allocBefore = threadBean.getThreadAllocatedBytes(Thread.currentThread().threadId());
        long t0 = System.nanoTime();
        long total = 0;
        while (true) {
            for (long r = 0; r < reps; r++) {
                for (int i = 0; i < baseCount; i++) {
                    op.accept(i);
                }
            }
            total += reps * baseCount;
            if ((System.nanoTime() - t0) / 1_000_000.0 >= TARGET_MS) {
                break;
            }
        }
        double ms = (System.nanoTime() - t0) / 1_000_000.0;
        long allocAfter = threadBean.getThreadAllocatedBytes(Thread.currentThread().threadId());
        return new Measurement(ms, allocAfter - allocBefore, total);
    }

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
        // Base workload sizes; each step is repeated until it runs for TARGET_MS.
        final int benchScale = 10;

        // Warm-up C2 JIT Compilation
        String jsonStr = "\"" + complexExpr + "\"";
        for (int i = 0; i < 20000; i++) {
            oh.isOpen(start.plusMinutes(i % 168));
            oh.getTimeToOpen(start.plusHours(i % 168));
            OpenHours.parse(complexExpr);
            mapper.readValue(jsonStr, OpenHours.class);
        }

        // Precompute the rolling timelines once, OUTSIDE the measured region
        // (matching the Go/Rust/C++/Zig harnesses). LocalDateTime is immutable,
        // so building it inside the timed loop would charge java.time object
        // allocation to the library; the queries must be measured on their own.
        final int rollingCount = iterations * 10 * benchScale;
        LocalDateTime[] minuteTimeline = new LocalDateTime[rollingCount];
        for (int i = 0; i < rollingCount; i++) {
            minuteTimeline[i] = start.plusMinutes(i);
        }
        LocalDateTime[] hourTimeline = new LocalDateTime[168];
        for (int i = 0; i < 168; i++) {
            hourTimeline[i] = start.plusHours(i);
        }

        // 1. Benchmark IsOpen (Rolling 100k calls)
        Measurement m1 = measure(rollingCount, threadBean, i -> oh.isOpen(minuteTimeline[i]));
        out.printf("1. IsOpen (100k rolling calls):            %4.0f ms (%.5f us/op, %.1f B/op)%n",
                m1.ms(), m1.ms() * 1000.0 / m1.total(), (double) m1.alloc() / m1.total());

        // 2. Benchmark IsOpen (Pure 1M calls with fixed timestamp)
        Measurement m2 = measure(1_000_000 * benchScale, threadBean, i -> oh.isOpen(fixedTime));
        out.printf("2. IsOpen (1M pure calls):                 %4.0f ms (%.5f us/op, %.1f B/op)%n",
                m2.ms(), m2.ms() * 1000.0 / m2.total(), (double) m2.alloc() / m2.total());

        // 3. Benchmark GetTimeToOpen (10k calls)
        Measurement m3 = measure(iterations * benchScale, threadBean, i -> oh.getTimeToOpen(hourTimeline[i % 168]));
        out.printf("3. GetTimeToOpen (10k calls):              %4.0f ms (%.5f us/op, %.1f B/op)%n",
                m3.ms(), m3.ms() * 1000.0 / m3.total(), (double) m3.alloc() / m3.total());

        // 4. Benchmark GetTimeToOpenForDuration 4h (10k calls)
        Measurement m4 = measure(iterations * benchScale, threadBean, i -> oh.getTimeToOpenForDuration(hourTimeline[i % 168], fourHours));
        out.printf("4. GetTimeToOpenForDuration 4h (10k calls):%4.0f ms (%.5f us/op, %.1f B/op)%n",
                m4.ms(), m4.ms() * 1000.0 / m4.total(), (double) m4.alloc() / m4.total());

        // 5. Benchmark When 4h (10k calls)
        Measurement m5 = measure(iterations * benchScale, threadBean, i -> oh.when(hourTimeline[i % 168], fourHours));
        out.printf("5. When 4h (10k calls):                    %4.0f ms (%.5f us/op, %.1f B/op)%n",
                m5.ms(), m5.ms() * 1000.0 / m5.total(), (double) m5.alloc() / m5.total());

        // 6. Benchmark NextDur (10k calls)
        Measurement m6 = measure(iterations * benchScale, threadBean, i -> oh.nextDur(hourTimeline[i % 168]));
        out.printf("6. NextDur (10k calls):                    %4.0f ms (%.5f us/op, %.1f B/op)%n",
                m6.ms(), m6.ms() * 1000.0 / m6.total(), (double) m6.alloc() / m6.total());

        // 7. Benchmark NextDate (10k calls)
        Measurement m7 = measure(iterations * benchScale, threadBean, i -> oh.nextDate(hourTimeline[i % 168]));
        out.printf("7. NextDate (10k calls):                   %4.0f ms (%.5f us/op, %.1f B/op)%n",
                m7.ms(), m7.ms() * 1000.0 / m7.total(), (double) m7.alloc() / m7.total());

        // 8. Benchmark Parse Cached (1k calls)
        Measurement m8 = measure(1000 * benchScale, threadBean, i -> OpenHours.parse(complexExpr));
        out.printf("8. Parse Cached (1k calls):                %4.0f ms (%.5f us/op, %.1f B/op)%n",
                m8.ms(), m8.ms() * 1000.0 / m8.total(), (double) m8.alloc() / m8.total());

        // 9. Benchmark JSON Deserialization (1k calls)
        Measurement m9 = measure(1000 * benchScale, threadBean, i -> {
            try {
                mapper.readValue(jsonStr, OpenHours.class);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        out.printf("9. JSON Deserialize (1k calls):            %4.0f ms (%.5f us/op, %.1f B/op)%n",
                m9.ms(), m9.ms() * 1000.0 / m9.total(), (double) m9.alloc() / m9.total());

        // 10. Simulation Stress Test (5,000 unique objects)
        Measurement m10 = measure(5000 * benchScale, threadBean, i -> {
            int hStart = 8 + (i % 60) / 60;
            int mStart = i % 60;
            int hEnd = 17 + (i % 60) / 60;
            int mEnd = i % 60;
            String expr = String.format("Mo-Fr %02d:%02d-%02d:%02d", hStart, mStart, hEnd, mEnd);
            OpenHours.parse(expr);
        });
        out.printf("10. Stress Test (5,000 unique objects):    %4.0f ms (%.4f ms/obj, %.1f B/obj)%n",
                m10.ms(), m10.ms() / m10.total(), (double) m10.alloc() / m10.total());
        out.println("========================================================");
    }
}
