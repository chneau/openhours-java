# openhours-java

A high-performance, hardware-accelerated Java 26+ parser and interval-math evaluator for OpenStreetMap [`opening_hours`](https://wiki.openstreetmap.org/wiki/Key:opening_hours) specifications.

[![Java 26](https://img.shields.io/badge/Java-26-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

---

## ⚡ Features & Performance

- **$O(1)$ Hardware-Accelerated Bitmask Table**: Evaluates `isOpen` in **~11 nanoseconds** via bit-parallel testing and `Long.numberOfTrailingZeros` (`TZCNT`/`BSF` instruction).
- **Zero-Allocation Interval Math**: High-speed point-in-time and interval calculations with zero intermediate heap allocations on query paths (`isOpen`, `getTimeToOpen`, `getTimeToOpenForDuration`, `when`, `nextDur`, `nextDate`).
- **Concurrent Lock-Free Interning**: Automatic thread-safe caching and deduplication of parsed instances (`ConcurrentHashMap`).
- **Overnight Shifts**: Full support for shifts spanning midnight (e.g. `Mo 22:00-04:00`, `Su 22:00-04:00`).
- **Overrides & Exclusions**: Handles `off` / `closed` rules overriding previous rules (e.g. `Mo-Su 00:00-24:00; Tu 12:00-13:00 off`).
- **Duration Availability**: Find wait times for continuous tasks of duration $D$ (`getTimeToOpenForDuration` / `when`).
- **Jackson JSON Support**: Built-in Jackson serializer and deserializer (`@JsonSerialize` / `@JsonDeserialize`).

---

## 🚀 Quick Start

### Usage Example

```java
import chneau.openhours.OpenHours;
import java.time.Duration;
import java.time.LocalDateTime;

public class Main {
    public static void main(String[] args) {
        // 1. Parse an OSM opening_hours string
        var oh = OpenHours.parse("Mo-Fr 08:00-12:00, 13:00-17:00; Sa 08:00-12:00");

        var monday10am = LocalDateTime.of(2026, 5, 18, 10, 0, 0);

        // 2. Fast point-in-time check (11 ns/op)
        boolean isOpen = oh.isOpen(monday10am); // true

        // 3. Current shift end
        LocalDateTime shiftEnd = oh.getCurrentShiftEnd(monday10am); // 2026-05-18T12:00:00

        // 4. Time to next open
        var tuesdayLunch = LocalDateTime.of(2026, 5, 19, 12, 30, 0);
        Duration timeToOpen = oh.getTimeToOpen(tuesdayLunch); // PT30M (opens at 13:00)

        // 5. Find when a 3-hour job can be serviced
        Duration waitFor3h = oh.getTimeToOpenForDuration(tuesdayLunch, Duration.ofHours(3));
        LocalDateTime whenCanStart = oh.when(tuesdayLunch, Duration.ofHours(3)); // 2026-05-19T13:00:00

        // 6. Next state transitions
        var nextDurResult = oh.nextDur(monday10am);
        var nextDateResult = oh.nextDate(monday10am); // 2026-05-18T12:00:00
    }
}
```

---

## 📊 Benchmark Suite (Java 26 on AMD Ryzen 9)

| # | Workload | Calls | Latency / Op |
| :--- | :--- | :--- | :--- |
| **1** | **`isOpen` (Rolling timeline)** | 100,000 | **0.25 µs** |
| **2** | **`isOpen` (Pure call)** | 1,000,000 | **11.0 ns** |
| **3** | **`getTimeToOpen`** | 10,000 | **160 ns** |
| **4** | **`getTimeToOpenForDuration` 4h** | 10,000 | **4.8 µs** |
| **5** | **`when` 4h** | 10,000 | **4.4 µs** |
| **6** | **`nextDur`** | 10,000 | **345 ns** |
| **7** | **`nextDate`** | 10,000 | **406 ns** |
| **8** | **`parse` (Cached)** | 1,000 | **21.0 ns** |
| **9** | **`JSON Deserialize`** | 1,000 | **250 ns** |
| **10** | **Stress Test (5,000 unique objects)** | 5,000 | **0.015 ms/obj** |

---

## 🛠️ Development & Testing

```bash
# Run unit tests
./gradlew test

# Run benchmark suite
./gradlew bench -q

# Build JAR
./gradlew build
```

---

## 📄 License

MIT License. Copyright (c) 2026 chneau.
