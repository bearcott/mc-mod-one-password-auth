package net.bearcott.passwordmod;

/**
 * A position in the player's current world. Serializes to {"x":..,"y":..,"z":..}, the same shape
 * vanilla's Vec3 had in the sessions file, so existing files load unchanged.
 */
public record Pos(double x, double y, double z) {

    public double distanceToSqr(Pos other) {
        double dx = x - other.x, dy = y - other.y, dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }
}
