package dev.keenac.mechanics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MovementSpeedWindowTest {
    private static final long STEP_NANOS = 50_000_000L;
    private static final long WINDOW_NANOS = 12_000_000_000L;
    private static final long MINIMUM_WINDOW_NANOS = 10_000_000_000L;

    @Test
    void detectsSustainedPointOnePercentSpeedIncreaseAfterLongStableWindow() {
        MovementSpeedWindow window = new MovementSpeedWindow();
        double allowedSpeed = 4.317;
        double actualSpeed = allowedSpeed * 1.001;

        for (int tick = 0; tick <= 240; tick++) {
            double elapsed = tick * 0.05;
            window.add(actualSpeed * elapsed, 0.0, 1_000_000_000L + tick * STEP_NANOS,
                    allowedSpeed, true, 300L);
        }

        MovementSpeedWindow.Evaluation result = window.evaluate(
                13_000_000_000L, WINDOW_NANOS, MINIMUM_WINDOW_NANOS,
                120, 1.0005, 0.002, 0.995);

        assertTrue(result.ready());
        assertTrue(result.exceeded());
        assertTrue(result.speedRatio() > 1.0009);
    }

    @Test
    void doesNotFlagMovementBelowTheConfiguredPrecisionThreshold() {
        MovementSpeedWindow window = new MovementSpeedWindow();
        double allowedSpeed = 4.317;
        double actualSpeed = allowedSpeed * 1.0002;

        for (int tick = 0; tick <= 240; tick++) {
            double elapsed = tick * 0.05;
            window.add(actualSpeed * elapsed, 0.0, 1_000_000_000L + tick * STEP_NANOS,
                    allowedSpeed, true, 300L);
        }

        MovementSpeedWindow.Evaluation result = window.evaluate(
                13_000_000_000L, WINDOW_NANOS, MINIMUM_WINDOW_NANOS,
                120, 1.0005, 0.002, 0.995);

        assertTrue(result.ready());
        assertFalse(result.exceeded());
    }

    @Test
    void waitsForEnoughTimeAndSamples() {
        MovementSpeedWindow window = new MovementSpeedWindow();
        for (int tick = 0; tick < 40; tick++) {
            window.add(tick * 0.25, 0.0, 1_000_000_000L + tick * STEP_NANOS,
                    5.0, true, 300L);
        }

        MovementSpeedWindow.Evaluation result = window.evaluate(
                3_000_000_000L, WINDOW_NANOS, MINIMUM_WINDOW_NANOS,
                120, 1.0005, 0.002, 0.995);

        assertFalse(result.ready());
        assertFalse(result.exceeded());
    }

    @Test
    void resetsAcrossLongPacketGapsAndIgnoresTurns() {
        MovementSpeedWindow gapWindow = new MovementSpeedWindow();
        gapWindow.add(0.0, 0.0, 1_000_000_000L, 5.0, true, 300L);
        gapWindow.add(0.25, 0.0, 1_050_000_000L, 5.0, true, 300L);
        gapWindow.add(1.25, 0.0, 1_500_000_000L, 5.0, true, 300L);
        assertEquals(1, gapWindow.size());

        MovementSpeedWindow turningWindow = new MovementSpeedWindow();
        for (int tick = 0; tick <= 240; tick++) {
            double t = tick * 0.05;
            double x = t <= 6.0 ? t * 5.0 : 30.0 - (t - 6.0) * 5.0;
            turningWindow.add(x, 0.0, 1_000_000_000L + tick * STEP_NANOS,
                    5.0, true, 300L);
        }
        MovementSpeedWindow.Evaluation result = turningWindow.evaluate(
                13_000_000_000L, WINDOW_NANOS, MINIMUM_WINDOW_NANOS,
                120, 1.0005, 0.002, 0.995);

        assertFalse(result.ready());
    }

    @Test
    void clearsWhenARequiredSampleIsInvalid() {
        MovementSpeedWindow window = new MovementSpeedWindow();
        window.add(0.0, 0.0, 1_000_000_000L, 5.0, true, 300L);
        window.add(0.1, 0.0, 1_050_000_000L, 5.0, false, 300L);
        assertEquals(0, window.size());
    }
}
