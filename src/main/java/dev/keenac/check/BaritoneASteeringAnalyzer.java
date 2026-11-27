package dev.keenac.check;

import java.util.List;

/** Pure steering-pattern helpers shared with the Baritone (A) regression tests. */
public final class BaritoneASteeringAnalyzer {
    private BaritoneASteeringAnalyzer() { }

    public record TurnSample(
            double directionTurnDegrees,
            double yawTurnDegrees,
            double segmentDistance
    ) { }

    public static boolean matchesTurn(
            double directionTurnDegrees,
            double yawTurnDegrees,
            double segmentDistance,
            double minimumTurnDegrees,
            double maximumTurnDegrees,
            double minimumSegmentDistance,
            double maximumSegmentDistance,
            double yawToleranceDegrees
    ) {
        if (!Double.isFinite(directionTurnDegrees)
                || !Double.isFinite(yawTurnDegrees)
                || !Double.isFinite(segmentDistance)) {
            return false;
        }

        double turn = Math.abs(directionTurnDegrees);
        if (turn < minimumTurnDegrees || turn > maximumTurnDegrees
                || segmentDistance < minimumSegmentDistance
                || segmentDistance > maximumSegmentDistance) {
            return false;
        }

        return Math.abs(wrapDegrees(yawTurnDegrees - directionTurnDegrees))
                <= yawToleranceDegrees;
    }

    /**
     * Confirms a run of independent turns whose camera rotation closely follows
     * the measured travel direction. The caller supplies only turns that have
     * already passed movement-distance and yaw-correlation bounds.
     */
    public static boolean hasConsistentSteering(
            List<TurnSample> turns,
            int requiredTurns,
            double maximumAverageYawErrorDegrees
    ) {
        if (turns == null || requiredTurns < 1 || turns.size() < requiredTurns) {
            return false;
        }

        double totalYawError = 0.0;
        int start = turns.size() - requiredTurns;
        for (int i = start; i < turns.size(); i++) {
            TurnSample sample = turns.get(i);
            if (sample == null
                    || !Double.isFinite(sample.directionTurnDegrees())
                    || !Double.isFinite(sample.yawTurnDegrees())
                    || !Double.isFinite(sample.segmentDistance())
                    || sample.segmentDistance() <= 0.0) {
                return false;
            }
            double error = Math.abs(wrapDegrees(
                    sample.yawTurnDegrees() - sample.directionTurnDegrees()));
            totalYawError += error;
        }

        return totalYawError / requiredTurns <= maximumAverageYawErrorDegrees;
    }

    public static double wrapDegrees(double angle) {
        double wrapped = angle % 360.0;
        if (wrapped > 180.0) wrapped -= 360.0;
        if (wrapped < -180.0) wrapped += 360.0;
        return wrapped;
    }
}
