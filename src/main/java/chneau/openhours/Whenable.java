package chneau.openhours;

import java.time.Duration;
import java.time.LocalDateTime;

/** Whenable */
public interface Whenable {
    LocalDateTime when(LocalDateTime ldt, Duration d);
}
