package com.aquariuspilot.highways;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The bundled 2b2t nether-highway map, loaded once from {@code highways/nether_highways.json} on the
 * classpath. Ported verbatim (package rename only) from AquariusProxy's
 * {@code com.aquarius.feature.highways.HighwayNetwork}.
 */
public final class HighwayNetwork {

    public static final String RESOURCE = "highways/nether_highways.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static volatile HighwayNetwork instance;

    private final int netherWorldBorder;
    private final int defaultYLevel;
    private final List<Highway> roads;

    private HighwayNetwork(int netherWorldBorder, int defaultYLevel, List<Highway> roads) {
        this.netherWorldBorder = netherWorldBorder;
        this.defaultYLevel = defaultYLevel;
        this.roads = roads;
    }

    public static HighwayNetwork get() {
        HighwayNetwork local = instance;
        if (local == null) {
            synchronized (HighwayNetwork.class) {
                local = instance;
                if (local == null) {
                    instance = local = load();
                }
            }
        }
        return local;
    }

    private static HighwayNetwork load() {
        try (InputStream in = HighwayNetwork.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("nether highway map resource not found: " + RESOURCE);
            }
            JsonNode root = MAPPER.readTree(in);
            int wb = root.path("netherWorldBorder").asInt(30_000_000);
            int defY = root.path("defaultYLevel").asInt(120);
            List<Highway> roads = new ArrayList<>();
            for (JsonNode r : root.path("roads")) {
                List<Highway.Segment> segs = new ArrayList<>();
                for (JsonNode s : r.path("segments")) {
                    segs.add(new Highway.Segment(
                        s.get(0).asInt(), s.get(1).asInt(), s.get(2).asInt(), s.get(3).asInt()));
                }
                roads.add(new Highway(
                    r.path("name").asString(),
                    r.path("category").asString(),
                    r.path("surface").asString(),
                    r.hasNonNull("dim") ? r.get("dim").asString() : null,
                    r.hasNonNull("radius") ? r.get("radius").asInt() : null,
                    r.hasNonNull("yLevel") ? r.get("yLevel").asInt() : null,
                    List.copyOf(segs)));
            }
            return new HighwayNetwork(wb, defY, List.copyOf(roads));
        } catch (Exception e) {
            throw new IllegalStateException("failed to load nether highway map", e);
        }
    }

    public int netherWorldBorder() { return netherWorldBorder; }
    public int defaultYLevel() { return defaultYLevel; }
    public List<Highway> roads() { return roads; }

    public int yLevelFor(Highway road) {
        Integer y = road.yLevel();
        return y != null ? y : defaultYLevel;
    }

    public Snap nearest(double x, double z) {
        return nearest(x, z, false);
    }

    public Snap nearestUsable(double x, double z) {
        return nearest(x, z, true);
    }

    private Snap nearest(double x, double z, boolean usableOnly) {
        Snap best = null;
        for (Highway road : roads) {
            if (usableOnly && !road.usable()) {
                continue;
            }
            for (Highway.Segment seg : road.segments()) {
                double[] c = seg.closestPoint(x, z);
                double d = Math.hypot(x - c[0], z - c[1]);
                if (best == null || d < best.distance()) {
                    best = new Snap(road, seg, c[0], c[1], d);
                }
            }
        }
        return best;
    }

    public record Snap(Highway road, Highway.Segment segment, double x, double z, double distance) {}
}
