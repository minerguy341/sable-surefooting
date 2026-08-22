package io.github.minerguy341.surefooting;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/**
 * Server-side settings: carrying non-player entities is server-authoritative, so these live in a
 * SERVER config (per-world, synced) rather than the client config.
 */
public final class SureFootingServerConfig {

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue CARRY_ENTITIES;
    public static final ModConfigSpec.DoubleValue ENTITY_JUMP_ROTATION_STRENGTH;
    public static final ModConfigSpec.DoubleValue ENTITY_GROUND_ROTATION_STRENGTH;
    public static final ModConfigSpec.BooleanValue ROTATE_ENTITY_YAW;
    public static final ModConfigSpec.IntValue CARRY_TIMEOUT_TICKS;
    public static final ModConfigSpec.DoubleValue EXIT_DISTANCE;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> EXIT_DISTANCE_OVERRIDES;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> CARRY_BLACKLIST;
    public static final ModConfigSpec.DoubleValue ITEM_THROW_LEAD_TICKS;
    public static final ModConfigSpec.BooleanValue DEBUG_ENTITY_LOGGING;

    static {
        final ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        CARRY_ENTITIES = builder
                .comment("Keep non-player entities (item drops, XP orbs, mobs, boats, ...) moving with the " +
                        "Sable sub-level they are on while they are airborne, so they don't get left behind " +
                        "or flung off fast contraptions. Players are handled client-side; projectiles are " +
                        "excluded (Sable already gives them launch velocity, and locking them to the frame " +
                        "would bend their trajectories).")
                .define("carry_entities", true);
        ENTITY_JUMP_ROTATION_STRENGTH = builder
                .comment("Multiplier on the airborne velocity rotation for carried entities. Same default and " +
                        "meaning as the client's jump_rotation_strength, but this one also accepts 0.0 to " +
                        "switch the rotation off (the client has no such option and floors at 0.5).")
                .defineInRange("entity_jump_rotation_strength", 1.16, 0.0, 3.0);
        ENTITY_GROUND_ROTATION_STRENGTH = builder
                .comment("Multiplier on the grounded velocity rotation for carried entities (mobs walking on " +
                        "spinning platforms). Mirrors the client's ground_rotation_strength.")
                .defineInRange("entity_ground_rotation_strength", 2.25, 0.0, 3.0);
        ROTATE_ENTITY_YAW = builder
                .comment("Turn carried entities (mobs, armor stands, ...) with the sub-level so they keep " +
                        "facing the same direction relative to it, instead of keeping a world-fixed heading " +
                        "while the deck rotates under them.")
                .define("rotate_entity_yaw", true);
        CARRY_TIMEOUT_TICKS = builder
                .comment("Maximum number of ticks an entity stays in a sub-level's reference frame while airborne.")
                .defineInRange("carry_timeout_ticks", 60, 1, 1200);
        EXIT_DISTANCE = builder
                .comment("Default: stop carrying an entity once it is this many blocks outside the sub-level's " +
                        "bounding box. Used for any entity type not listed in exit_distance_overrides. " +
                        "(Players have their own client-side exit_distance_blocks — set that to 0 to drop off " +
                        "the moment you step off an edge.)")
                .defineInRange("exit_distance_blocks", 4.0, 0.0, 64.0);
        EXIT_DISTANCE_OVERRIDES = builder
                .comment("Per-entity-type carry distances, overriding the default above. Each entry is " +
                        "\"<entity id>=<distance>\", e.g. [\"minecraft:item=16.0\", \"minecraft:experience_orb=16.0\"] " +
                        "to let dropped items and XP ride much further from the contraption than mobs do. " +
                        "Items also keep riding a deck they are resting on for as long as they stay inside " +
                        "this distance, so raising it keeps them aboard further out.")
                .defineListAllowEmpty("exit_distance_overrides",
                        List.of("minecraft:item=16.0", "minecraft:experience_orb=16.0"),
                        () -> "minecraft:item=16.0", SureFootingServerConfig::isValidOverride);
        CARRY_BLACKLIST = builder
                .comment("Entity type ids that should never be carried, e.g. [\"minecraft:boat\", \"examplemod:drone\"].")
                .defineListAllowEmpty("carry_blacklist", List.of(), () -> "", o -> o instanceof String);
        ITEM_THROW_LEAD_TICKS = builder
                .comment("Aim a dropped item this many ticks ahead of the contraption's rotation, to cancel " +
                        "the lag between what you see and where the server has you pointing. 0 disables it.\n" +
                        "You aim using a view rendered from a delayed snapshot (Sable's own " +
                        "sub_level_snapshot_interpolation_delay_ticks, plus whatever your client and connection " +
                        "add), so on a turning deck your crosshair trails the contraption. The item itself " +
                        "leaves exactly along where the server has you facing and then flies straight in the " +
                        "contraption's frame -- both measured -- so the miss is the stale view, and it grows " +
                        "with spin rate: a deck at 96 RPM turns 28.8 degrees per tick, so each tick of lag is " +
                        "28.8 degrees of miss.\n" +
                        "The correction applied is this value times the deck's rotation per tick, so ONE value " +
                        "works at every speed -- the per-tick rotation already scales with RPM. Tune it by " +
                        "raising it until drops stop landing behind you and lowering it if they start landing " +
                        "ahead. Around 12-13 suited a local singleplayer client.\n" +
                        "Two things to know. On a fast deck the total can exceed a full turn (12.7 x 28.8 = 366 " +
                        "degrees at 96 RPM); that still corrects correctly, because the error wraps by the same " +
                        "amount, but near a multiple of 360 the result is very sensitive to small changes in " +
                        "this value or in RPM. And it is a tuned number, not a derived one: the server cannot " +
                        "see a client's render delay, so on a shared server one value cannot suit everyone.")
                .defineInRange("item_throw_lead_ticks", 0.0, 0.0, 20.0);
        DEBUG_ENTITY_LOGGING = builder
                .comment("Log carried entities' positions in the contraption's own reference frame, for " +
                        "diagnosing drag. A local position that holds steady means the entity is glued to " +
                        "the deck and anything you see moving is client-side rendering; one that drifts means " +
                        "the carry is not holding it. One line per carried entity per tick while it settles, " +
                        "so leave this off unless you are chasing something.")
                .define("debug_entity_logging", false);

        SPEC = builder.build();
    }

    /** Accepts "&lt;id&gt;=&lt;number&gt;" entries for the exit-distance override list. */
    private static boolean isValidOverride(final Object o) {
        if (!(o instanceof final String s)) {
            return false;
        }

        final int eq = s.lastIndexOf('=');
        if (eq <= 0 || eq == s.length() - 1) {
            return false;
        }

        try {
            Double.parseDouble(s.substring(eq + 1).trim());
            return true;
        } catch (final NumberFormatException e) {
            return false;
        }
    }

    private SureFootingServerConfig() {
    }
}
