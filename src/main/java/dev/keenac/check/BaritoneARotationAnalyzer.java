package dev.keenac.check;

import java.util.List;

/** Pure helpers for the rotation-snap-density signal used by Baritone (A). */
public final class BaritoneARotationAnalyzer {
    private BaritoneARotationAnalyzer() { }

    public record RotationSnap(
            double yawDeltaDegrees,
            double pitchDeltaDegrees,
            long timestampNanos
    ) { }

    public static boolean isRotationSnap(
            double yawDeltaDegrees,
            double pitchDeltaDegrees,
            double minimumYawStepDegrees,
            double maximumYawStepDegrees,
            double maximumPitchStepDegrees
    ) {
        if (!Double.isFinite(yawDeltaDegrees) || !Double.isFinite(pitchDeltaDegrees)) {
            return false;
        }
        double yawStep = Math.abs(yawDeltaDegrees);
        double pitchStep = Math.abs(pitchDeltaDegrees);
        return yawStep >= minimumYawStepDegrees
                && yawStep <= maximumYawStepDegrees
                && pitchStep <= maximumPitchStepDegrees;
    }

    public static boolean hasSnapDensity(
            List<RotationSnap> samples,
            int requiredCount,
            double maximumAveragePitchStepDegrees,
            double travelledDistance,
            double minimumTravelDistance
    ) {
        if (samples == null || requiredCount < 1 || samples.size() < requiredCount
                || !Double.isFinite(travelledDistance)
                || travelledDistance < minimumTravelDistance) {
            return false;
        }

        double pitchTotal = 0.0;
        int first = samples.size() - requiredCount;
        for (int i = first; i < samples.size(); i++) {
            RotationSnap sample = samples.get(i);
            if (sample == null || !Double.isFinite(sample.yawDeltaDegrees())
                    || !Double.isFinite(sample.pitchDeltaDegrees())) {
                return false;
            }
            pitchTotal += Math.abs(sample.pitchDeltaDegrees());
        }
        return pitchTotal / requiredCount <= maximumAveragePitchStepDegrees;
    }
}
