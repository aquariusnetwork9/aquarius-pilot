package com.aquariuspilot.highways;

import java.util.List;

/**
 * One 2b2t <b>nether</b> road from the bundled highway map ({@code highways/nether_highways.json}). Ported
 * verbatim (package rename only) from AquariusProxy's {@code com.aquarius.feature.highways.Highway}.
 *
 * @param name     human label from the map
 * @param category {@code axis} (cardinals + diagonals), {@code ring}, {@code diamond}, or {@code grid}
 * @param surface  {@code paved}, {@code dug}, {@code planned}, or {@code unknown}
 * @param dim      tunnel profile such as {@code "6x4"}, or {@code null} if unknown
 * @param radius   ring/diamond radius in blocks, or {@code null} for {@code axis}/{@code grid}
 * @param yLevel   road Y if pinned, else {@code null} -> network default
 * @param segments the road's straight runs, in map order
 */
public record Highway(
    String name,
    String category,
    String surface,
    String dim,
    Integer radius,
    Integer yLevel,
    List<Segment> segments
) {
    public boolean usable() {
        return !"planned".equals(surface);
    }

    public record Segment(int x1, int z1, int x2, int z2) {
        public double[] closestPoint(double px, double pz) {
            double dx = (double) x2 - x1;
            double dz = (double) z2 - z1;
            double len2 = dx * dx + dz * dz;
            if (len2 == 0.0) {
                return new double[] {x1, z1};
            }
            double t = ((px - x1) * dx + (pz - z1) * dz) / len2;
            t = Math.max(0.0, Math.min(1.0, t));
            return new double[] {x1 + t * dx, z1 + t * dz};
        }

        public double distanceTo(double px, double pz) {
            double[] c = closestPoint(px, pz);
            return Math.hypot(px - c[0], pz - c[1]);
        }
    }
}
