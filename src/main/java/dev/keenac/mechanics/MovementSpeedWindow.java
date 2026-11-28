package dev.keenac.mechanics;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Long-window horizontal speed estimator for low-amplitude anomalies.
 *
 * It only reports a result when the sample horizon is long enough, packet
 * spacing is continuous, the movement path is nearly straight, and the
 * configured number of valid observations has been collected.
 */
public final class MovementSpeedWindow {
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final double EPSILON = 1.0E-9;
    private static final int MAX_SAMPLES = 1024;

    private final Deque<Sample> samples = new ArrayDeque<>();

    public void add(
            double x,
            double z,
            long timestampNanos,
            double allowedHorizontalSpeedBps,
            boolean eligible,
            long maximumSampleGapMillis
    ) {
        if (!eligible || !Double.isFinite(x) || !Double.isFinite(z)
                || !Double.isFinite(allowedHorizontalSpeedBps)
                || allowedHorizontalSpeedBps <= 0.0
                || maximumSampleGapMillis <= 0L) {
            clear();
            return;
        }

        Sample previous = samples.peekLast();
        if (previous != null) {
            long elapsed = timestampNanos - previous.timestampNanos();
            if (elapsed <= 0L || elapsed > maximumSampleGapMillis * NANOS_PER_MILLI) {
                clear();
            }
        }

        samples.addLast(new Sample(x, z, timestampNanos, allowedHorizontalSpeedBps));
        while (samples.size() > MAX_SAMPLES) {
            samples.removeFirst();
        }
    }

    public Evaluation evaluate(
            long nowNanos,
            long windowNanos,
            long minimumWindowNanos,
            int minimumSamples,
            double multiplier,
            double measurementSlackBlocks,
            double minimumDirectionCoherence
    ) {
        if (windowNanos <= 0L || minimumWindowNanos <= 0L
                || minimumWindowNanos > windowNanos || minimumSamples < 2
                || !Double.isFinite(multiplier) || multiplier < 1.0
                || !Double.isFinite(measurementSlackBlocks) || measurementSlackBlocks < 0.0
                || !Double.isFinite(minimumDirectionCoherence)
                || minimumDirectionCoherence < 0.0 || minimumDirectionCoherence > 1.0) {
            return Evaluation.notReady();
        }

        while (samples.size() > 2) {
            Sample second = secondSample();
            if (second == null) break;
            long sampleAgeNanos = nowNanos - second.timestampNanos();
            if (sampleAgeNanos >= 0L && sampleAgeNanos > windowNanos) {
                samples.removeFirst();
            } else {
                break;
            }
        }

        if (samples.size() < minimumSamples) return Evaluation.notReady();
        Sample first = samples.peekFirst();
        Sample last = samples.peekLast();
        if (first == null || last == null) return Evaluation.notReady();

        long durationNanos = last.timestampNanos() - first.timestampNanos();
        if (durationNanos < minimumWindowNanos) return Evaluation.notReady();

        double allowedDistance = 0.0;
        double pathDistance = 0.0;
        Sample previous = first;
        for (Sample current : samples) {
            if (current == first) continue;
            long deltaNanos = current.timestampNanos() - previous.timestampNanos();
            if (deltaNanos <= 0L || deltaNanos > windowNanos) {
                return Evaluation.notReady();
            }
            double seconds = deltaNanos / 1_000_000_000.0;
            double averageAllowedSpeed =
                    (previous.allowedHorizontalSpeedBps() + current.allowedHorizontalSpeedBps()) * 0.5;
            allowedDistance += averageAllowedSpeed * seconds;
            pathDistance += Math.hypot(
                    current.x() - previous.x(), current.z() - previous.z());
            previous = current;
        }

        double netDistance = Math.hypot(last.x() - first.x(), last.z() - first.z());
        if (!Double.isFinite(allowedDistance) || allowedDistance <= EPSILON
                || !Double.isFinite(netDistance) || !Double.isFinite(pathDistance)
                || pathDistance <= EPSILON) {
            return Evaluation.notReady();
        }

        double directionCoherence = netDistance / pathDistance;
        if (!Double.isFinite(directionCoherence)
                || directionCoherence < minimumDirectionCoherence) {
            return Evaluation.notReady();
        }

        double ratio = netDistance / allowedDistance;
        boolean exceeded = netDistance > allowedDistance * multiplier + measurementSlackBlocks;
        return new Evaluation(true, exceeded, ratio, netDistance, allowedDistance,
                samples.size(), durationNanos, directionCoherence);
    }

    public void clear() {
        samples.clear();
    }

    public int size() {
        return samples.size();
    }

    private Sample secondSample() {
        if (samples.size() < 2) return null;
        var iterator = samples.iterator();
        iterator.next();
        return iterator.next();
    }

    private record Sample(
            double x,
            double z,
            long timestampNanos,
            double allowedHorizontalSpeedBps
    ) { }

    public record Evaluation(
            boolean ready,
            boolean exceeded,
            double speedRatio,
            double actualDistance,
            double allowedDistance,
            int sampleCount,
            long durationNanos,
            double directionCoherence
    ) {
        public static Evaluation notReady() {
            return new Evaluation(false, false, 1.0, 0.0, 0.0, 0, 0L, 0.0);
        }
    }
}
