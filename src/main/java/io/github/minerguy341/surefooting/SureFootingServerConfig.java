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
    public static final ModConfigSpec.ConfigValue<List<? extends String>> CARRY_BLACKLIST;

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
                .comment("Multiplier on the airborne velocity rotation for carried entities. Mirrors the " +
                        "client's jump_rotation_strength.")
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
                .comment("Stop carrying an entity once it is this many blocks outside the sub-level's bounding box.")
                .defineInRange("exit_distance_blocks", 4.0, 0.0, 64.0);
        CARRY_BLACKLIST = builder
                .comment("Entity type ids that should never be carried, e.g. [\"minecraft:boat\", \"examplemod:drone\"].")
                .defineListAllowEmpty("carry_blacklist", List.of(), () -> "", o -> o instanceof String);

        SPEC = builder.build();
    }

    private SureFootingServerConfig() {
    }
}
