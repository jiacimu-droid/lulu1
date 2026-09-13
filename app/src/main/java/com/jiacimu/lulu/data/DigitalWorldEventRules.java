package com.jiacimu.lulu.data;

import java.util.Arrays;

/** Environmental scheduling and outcomes depend on saved state, not model call count. */
public final class DigitalWorldEventRules {
    private DigitalWorldEventRules() {}

    public static long opportunitySlot(long epochSecond) {
        return Math.floorDiv(epochSecond, 3600L);
    }

    public static boolean hasNewOpportunity(long epochSecond, long consumedSlot, long latestEventSlot) {
        return opportunitySlot(epochSecond) > Math.max(consumedSlot, latestEventSlot);
    }

    public static boolean canEvolve(long now, long updatedAt) {
        return now >= updatedAt && now - updatedAt >= 900L;
    }

    public static boolean naturallyEnds(String kind, long now, long createdAt) {
        return now >= createdAt && now - createdAt >= 1800L && Arrays.asList(
            "glimmer_mote", "odd_sound", "surface_vibration", "cold_patch", "warm_pulse",
            "cloud_ripple", "static_cluster", "warm_current", "stray_pixel", "arrival_echo",
            "portal_flicker", "floating_dust", "gentle_light", "soft_breeze"
        ).contains(kind);
    }

    /** Stable key for the same type at the same place/entity. */
    public static String noveltyKey(String kind, String locationCode, String anchorItemId) {
        String anchor = anchorItemId == null || anchorItemId.isBlank() ? "place" : anchorItemId;
        return kind + "|" + locationCode + "|" + anchor;
    }

    /** Exact type+place+entity cooldown. Persistent nuisances deliberately cool down longer. */
    public static long noveltyCooldownSeconds(String kind) {
        return isPersistentNuisance(kind) ? 48L * 3600L : 8L * 3600L;
    }

    /** Same type in one location also gets a shorter location-level cooldown. */
    public static long kindLocationCooldownSeconds(String kind) {
        return isPersistentNuisance(kind) ? 12L * 3600L : 3L * 3600L;
    }

    public static boolean coolingDown(long now, long lastAt, long cooldownSeconds) {
        return lastAt > 0L && now >= lastAt && now - lastAt < cooldownSeconds;
    }

    public static boolean passive(String approach) {
        return Arrays.asList("observe", "wait", "avoid").contains(approach);
    }

    public static int correctionChance(String kind, String approach) {
        if (Arrays.asList("clean", "reset", "straighten", "tend", "drive_out").contains(approach)) return 76;
        if (approach.equals("touch") && Arrays.asList("static_cluster", "glimmer_mote").contains(kind)) return 48;
        return 0;
    }

    private static boolean isPersistentNuisance(String kind) {
        return Arrays.asList(
            "roach", "dust_layer", "surface_ripple", "cold_patch", "sinking_seam",
            "light_flicker", "warm_pulse", "plant_droop", "screen_static", "mirror_afterimage",
            "clock_desync", "rug_wrinkle", "shelf_tilt", "drawer_jam", "frame_tilt",
            "surface_vibration", "glimmer_mote", "odd_sound", "cloud_ripple", "static_cluster",
            "warm_current", "stray_pixel", "arrival_echo", "portal_flicker"
        ).contains(kind);
    }
}
