package dev.keenac.check;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BaritoneASteeringAnalyzerTest {
    @Test
    void acceptsDirectionAndYawTurnAcrossAngleWrapBoundary() {
        assertTrue(BaritoneASteeringAnalyzer.matchesTurn(
                -179.0, 179.0, 1.5,
                18.0, 150.0, 0.6, 10.0, 5.0));
    }

    @Test
    void rejectsTurnWithoutMatchingCameraRotation() {
        assertFalse(BaritoneASteeringAnalyzer.matchesTurn(
                45.0, 5.0, 1.5,
                18.0, 150.0, 0.6, 10.0, 12.0));
    }

    @Test
    void rejectsTinyTurnsAndInvalidSegmentDistances() {
        assertFalse(BaritoneASteeringAnalyzer.matchesTurn(
                8.0, 8.0, 1.5,
                18.0, 150.0, 0.6, 10.0, 12.0));
        assertFalse(BaritoneASteeringAnalyzer.matchesTurn(
                45.0, 45.0, 0.2,
                18.0, 150.0, 0.6, 10.0, 12.0));
    }

    @Test
    void requiresEnoughConsistentTurns() {
        List<BaritoneASteeringAnalyzer.TurnSample> turns = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            turns.add(new BaritoneASteeringAnalyzer.TurnSample(45.0, 48.0, 2.0));
        }

        assertFalse(BaritoneASteeringAnalyzer.hasConsistentSteering(turns, 8, 8.0));
        turns.add(new BaritoneASteeringAnalyzer.TurnSample(-45.0, -48.0, 2.5));
        assertTrue(BaritoneASteeringAnalyzer.hasConsistentSteering(turns, 8, 8.0));
    }

    @Test
    void rejectsUnusuallyLargeAverageYawError() {
        List<BaritoneASteeringAnalyzer.TurnSample> turns = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            turns.add(new BaritoneASteeringAnalyzer.TurnSample(45.0, 55.0, 2.0));
        }

        assertFalse(BaritoneASteeringAnalyzer.hasConsistentSteering(turns, 8, 8.0));
    }
}
