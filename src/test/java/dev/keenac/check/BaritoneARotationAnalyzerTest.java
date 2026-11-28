package dev.keenac.check;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BaritoneARotationAnalyzerTest {
    @Test
    void acceptsHorizontalYawSnapWithStablePitch() {
        assertTrue(BaritoneARotationAnalyzer.isRotationSnap(
                35.0, 0.2, 12.0, 150.0, 0.75));
    }

    @Test
    void rejectsSmallYawChangesAndLargePitchChanges() {
        assertFalse(BaritoneARotationAnalyzer.isRotationSnap(
                4.0, 0.0, 12.0, 150.0, 0.75));
        assertFalse(BaritoneARotationAnalyzer.isRotationSnap(
                35.0, 2.0, 12.0, 150.0, 0.75));
    }

    @Test
    void requiresRepeatedSnapsAndActualTravel() {
        List<BaritoneARotationAnalyzer.RotationSnap> samples = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            samples.add(new BaritoneARotationAnalyzer.RotationSnap(30.0, 0.1, i + 1L));
        }

        assertFalse(BaritoneARotationAnalyzer.hasSnapDensity(samples, 7, 0.45, 6.0, 4.0));
        samples.add(new BaritoneARotationAnalyzer.RotationSnap(-30.0, 0.1, 7L));
        assertFalse(BaritoneARotationAnalyzer.hasSnapDensity(samples, 7, 0.45, 3.9, 4.0));
        assertTrue(BaritoneARotationAnalyzer.hasSnapDensity(samples, 7, 0.45, 4.0, 4.0));
    }

    @Test
    void rejectsASequenceWithExcessivePitchMovement() {
        List<BaritoneARotationAnalyzer.RotationSnap> samples = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            samples.add(new BaritoneARotationAnalyzer.RotationSnap(30.0, 0.7, i + 1L));
        }

        assertFalse(BaritoneARotationAnalyzer.hasSnapDensity(samples, 7, 0.45, 6.0, 4.0));
    }
}
