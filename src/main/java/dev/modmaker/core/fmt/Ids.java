package dev.modmaker.core.fmt;

import java.util.concurrent.atomic.AtomicLong;

/** Short, stable-ish identifiers for project records, nodes and edges. */
public final class Ids {
    private static final AtomicLong COUNTER = new AtomicLong();

    private Ids() {
    }

    public static String uid(String prefix) {
        return prefix + Long.toString(System.nanoTime(), 36) + Long.toString(COUNTER.incrementAndGet(), 36);
    }
}
