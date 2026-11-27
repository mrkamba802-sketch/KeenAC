package dev.keenac.engine.core.util;
public final class PacketClock {
    private static final ThreadLocal<Long> EVENT_TIME_MILLIS = new ThreadLocal<>();
    private PacketClock() { }
    public static void enter(long packetTimestampMillis) {
        EVENT_TIME_MILLIS.set(packetTimestampMillis);
    }
    public static void exit() {
        EVENT_TIME_MILLIS.remove();
    }
    public static long currentTimeMillis() {
        Long timestamp = EVENT_TIME_MILLIS.get();
        return timestamp != null ? timestamp : System.currentTimeMillis();
    }
    public static long nanoTime() {
        Long timestamp = EVENT_TIME_MILLIS.get();
        return timestamp != null ? timestamp * 1_000_000L : System.currentTimeMillis() * 1_000_000L;
    }
}
