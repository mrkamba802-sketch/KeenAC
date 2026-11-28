package dev.keenac.mechanics;

/**
 * Last known client movement input. PlayerInput is stateful: clients send it
 * when controls change, so the latest values remain active until a new packet.
 */
public record MovementInput(
        boolean forward,
        boolean backward,
        boolean left,
        boolean right,
        boolean jump,
        boolean sneak,
        boolean sprint,
        long receivedAtNanos
) {
    public double forwardAxis() {
        return (forward ? 1.0 : 0.0) - (backward ? 1.0 : 0.0);
    }

    /** Positive values point to the player's right. */
    public double strafeAxis() {
        return (right ? 1.0 : 0.0) - (left ? 1.0 : 0.0);
    }

    public boolean hasDirectionalInput() {
        return forward != backward || left != right;
    }
}
