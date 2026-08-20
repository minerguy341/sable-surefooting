package io.github.minerguy341.surefooting;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Server-side counterpart of {@link JumpCarryHandler} for non-player entities.
 * <p>
 * Sable's collision passes an inherited velocity only for living entities — item drops, XP orbs
 * and other non-living entities get {@code Vec3.ZERO}, i.e. no airborne carry at all — and every
 * non-player entity loses tracking the moment it leaves the sub-level surface, exactly like
 * players did before this mod. Items even spawn with a random upward pop, so on a fast deck they
 * often never establish tracking in the first place.
 * <p>
 * This handler seeds tracking for entities that spawn over a sub-level, keeps entities tracked
 * through airborne arcs (so Sable's warp carries them), and rotates their velocity with the
 * sub-level's frame. Players are handled client-side; projectiles are excluded because Sable
 * already applies launch velocity and frame-locking would bend their trajectories.
 */
public final class EntityCarryHandler {

    private static final class CarryState {
        SubLevel lastTracked;
        SubLevel carrying;
        int carryTicks;
        final FrameRotation.Anchor orientationAnchor = new FrameRotation.Anchor();
    }

    /**
     * Highest spawn seed we will hand an entity, in blocks/tick. Sable's substep collision is
     * already unreliable past ~1 block/tick, and a swept bounding box grows with the cube of the
     * motion: an entity carrying a hundred blocks/tick of velocity makes
     * {@code SubLevelEntityCollision} walk millions of block positions in a single tick, or skip
     * collision entirely ("Enormous local sub-level collision bounds"). Above this we drop the
     * seed rather than risk the tick — the item is left behind, which is wrong but survivable.
     */
    private static final double MAX_SEED_SPEED = 4.0;

    /**
     * Absolute ceiling, in blocks/tick, on any point velocity we will write to an entity — in
     * either direction. {@link #MAX_SEED_SPEED} is a policy about how much velocity we are willing
     * to <em>hand</em> an entity and so belongs to the seeding site only; the handoff below has to
     * subtract the real deck speed however fast the deck is, or it leaves the double-count it
     * exists to remove. But "however fast" still needs a sane bound: a solver spike that produces a
     * finite-but-absurd velocity would otherwise reach {@code deltaMovement} through the subtract
     * path and stall the tick inside collision — the same cliff {@code MAX_SEED_SPEED} guards on
     * the add path. Ten times the seed cap is far above any real contraption.
     */
    private static final double MAX_SANE_SPEED = MAX_SEED_SPEED * 10.0;

    private final Map<Entity, CarryState> states = new WeakHashMap<>();

    /**
     * Items handed a spawn seed that have not yet been picked up by Sable's frame tracking.
     * The seed is world-space, so it has to be taken back off at the handoff — see
     * {@link #onEntityTick}.
     */
    private final Set<Entity> seeded = Collections.newSetFromMap(new WeakHashMap<>());

    @SubscribeEvent
    public void onEntityJoin(final EntityJoinLevelEvent event) {
        final Entity entity = event.getEntity();

        // loadedFromDisk: only genuinely spawning items get a seed. A chunk load re-fires this
        // event for every item already lying in it, and ItemEntity persists its thrower UUID
        // (re-resolved lazily by getOwner), so a disk-loaded stack whose thrower happens to be
        // riding a contraption would otherwise be seeded where it lies — see pointVelocity's note
        // on why a bogus radius is dangerous.
        if (event.getLevel().isClientSide || event.loadedFromDisk() || !this.isConfigOn()
                || !(entity instanceof final ItemEntity item) || !this.isEligible(entity)) {
            return;
        }

        // Items dropped by someone riding a contraption spawn with the dropper's throw velocity but
        // none of the contraption's motion, so a fast deck races out from under them. Seed them with
        // the sub-level's point velocity — NOT with tracking: tracking an airborne item at spawn
        // flips Sable's networking/interpolation into the relative frame, which misplaces the item
        // client-side. With plain velocity the item flies in the contraption's frame and stays
        // globally networked until Sable picks it up, which happens as soon as deck blocks are near
        // its bounds — often on the first tick, not on touchdown. onEntityTick takes the seed back
        // off at that handoff, so it is carry while the item is loose and nothing once it is tracked.
        final Entity owner = item.getOwner();
        if (owner == null) {
            return;
        }

        final SubLevel ownerSubLevel = Sable.HELPER.getTrackingSubLevel(owner);
        if (ownerSubLevel == null || ownerSubLevel.isRemoved() || ownerSubLevel.getLevel() != item.level()) {
            return;
        }

        // The owner riding a contraption does not make THIS item's position meaningful on it. The
        // point velocity is angularVelocity x r, with r taken from the item's own position, so an
        // item far from the deck gets a radius that is really just its distance from the deck.
        // Only seed items that are actually on (or just above) the contraption.
        if (!withinBounds(item, ownerSubLevel)) {
            return;
        }

        final Vec3 pointVelocity = pointVelocity(ownerSubLevel, item);
        if (pointVelocity == null || pointVelocity.lengthSqr() > MAX_SEED_SPEED * MAX_SEED_SPEED) {
            return;
        }

        item.setDeltaMovement(item.getDeltaMovement().add(pointVelocity));
        this.seeded.add(item);
    }

    /**
     * The sub-level's velocity at an entity's position, in blocks/tick, or null if the physics
     * state is degenerate. The {@link #MAX_SEED_SPEED} cap is deliberately <em>not</em> applied
     * here: it is a policy about how much velocity we are willing to hand an entity, so it belongs
     * to the seeding call site. The handoff below subtracts velocity to cancel the tracking warp's
     * double-count, and capping that would leave the double-count in place.
     * <p>
     * {@code Sable.HELPER.getVelocity} takes a <em>sub-level-local</em> position: it returns
     * {@code angularVelocity × r + linearVelocity}, deriving {@code r} from the argument. Handing
     * it a world position makes {@code r} the distance to the world origin instead of the radius
     * on the deck, inflating the tangential term by orders of magnitude — the cause of issue #1,
     * where items dropped on a levitating (never quite still, so never quite zero angular
     * velocity) contraption were launched through the deck and stalled the server thread for
     * tens of seconds inside collision. Every other sub-level-space call in this mod converts
     * with {@code transformPositionInverse} first; so does this one.
     */
    private static Vec3 pointVelocity(final SubLevel subLevel, final Entity entity) {
        // getVelocity casts the level to ServerLevel and reads the sub-level's physics body
        // without checking it is still live, so establish that here rather than relying on the
        // caller — every other sub-level use in this class makes the same two checks.
        if (subLevel.isRemoved() || subLevel.getLevel() != entity.level()) {
            return null;
        }

        final Vec3 local = subLevel.logicalPose().transformPositionInverse(entity.position());
        final Vec3 velocity = Sable.HELPER.getVelocity(entity.level(), subLevel, local).scale(1.0 / 20.0);

        if (!Double.isFinite(velocity.x) || !Double.isFinite(velocity.y) || !Double.isFinite(velocity.z)) {
            return null; // a physics blow-up must never reach an entity's delta movement
        }

        return velocity.lengthSqr() > MAX_SANE_SPEED * MAX_SANE_SPEED ? null : velocity;
    }

    /** Whether the entity is inside the sub-level's world-space bounds, plus the exit margin. */
    private static boolean withinBounds(final Entity entity, final SubLevel subLevel) {
        final BoundingBox3dc bounds = subLevel.boundingBox();
        final double margin = SureFootingServerConfig.EXIT_DISTANCE.get();
        final Vec3 pos = entity.position();

        return pos.x >= bounds.minX() - margin && pos.x <= bounds.maxX() + margin
                && pos.y >= bounds.minY() - margin && pos.y <= bounds.maxY() + margin
                && pos.z >= bounds.minZ() - margin && pos.z <= bounds.maxZ() + margin;
    }

    @SubscribeEvent
    public void onEntityTick(final EntityTickEvent.Post event) {
        final Entity entity = event.getEntity();

        if (entity.level().isClientSide) {
            return;
        }

        // Eligibility can flip mid-carry (a blacklist edit, or the entity mounting something).
        // Drop any state rather than returning past the cleanup: a stale entry would otherwise sit
        // there with its carrying/carryTicks frozen and resume from it if eligibility came back.
        if (!this.isEligible(entity)) {
            this.states.remove(entity);
            this.seeded.remove(entity);
            return;
        }

        final EntityMovementExtension extension = (EntityMovementExtension) entity;
        SubLevel current = extension.sable$getTrackingSubLevel();

        if (!this.isConfigOn()) {
            this.states.remove(entity);
            this.seeded.remove(entity);
            return;
        }

        CarryState state = this.states.get(entity);
        if (current == null && state == null) {
            return; // fast path: entity has nothing to do with sub-levels
        }

        // Sable has taken a seeded item into its frame: from here the tracking warp carries the
        // item with the deck, so the world-space spawn seed is no longer carry — it is drift
        // relative to the deck, and would slide the item off (an item's ground drag bleeds it off
        // over ~2.4x its own length in blocks). Re-express the item in the sub-level's frame by
        // taking the seed back off at the handoff.
        if (current != null && this.seeded.contains(entity)) {
            final Vec3 pointVelocity = pointVelocity(current, entity);

            // Only forget the seed once it has actually come back off. Consuming the record on a
            // failed conversion would leave the item double-counting the deck's motion for the
            // rest of its life — the exact drift this handoff exists to remove — with no second
            // chance. A degenerate physics tick is transient, so retry on the next one.
            if (pointVelocity != null) {
                entity.setDeltaMovement(entity.getDeltaMovement().subtract(pointVelocity));
                this.seeded.remove(entity);
            }
        }

        if (state == null) {
            state = new CarryState();
            this.states.put(entity, state);
        }

        if (state.carrying != null) {
            if (current != null) {
                state.carrying = null; // Sable re-established tracking itself (landed)
            } else if (this.shouldStopCarry(entity, state)) {
                state.carrying = null;
            } else {
                extension.sable$setTrackingSubLevel(state.carrying);
                current = state.carrying;
                state.carryTicks++;
            }
        } else if (state.lastTracked != null && current == null && this.shouldStartCarry(entity, state.lastTracked)) {
            state.carrying = state.lastTracked;
            state.carryTicks = 1; // this tick is the first carried one
            extension.sable$setTrackingSubLevel(state.carrying);
            current = state.carrying;
        }

        if (current != null) {
            final boolean grounded = entity.onGround();
            final double strength = grounded
                    ? SureFootingServerConfig.ENTITY_GROUND_ROTATION_STRENGTH.get()
                    : SureFootingServerConfig.ENTITY_JUMP_ROTATION_STRENGTH.get();
            // Item yaw is meaningless (their visual spin is a client render animation); everything
            // else turns with the frame so it keeps facing the same way relative to the deck.
            final boolean rotateYaw = !(entity instanceof ItemEntity)
                    && SureFootingServerConfig.ROTATE_ENTITY_YAW.get();
            FrameRotation.rotateWithFrame(state.orientationAnchor, entity, current, strength, grounded, rotateYaw);
        } else {
            state.orientationAnchor.reset();
        }

        state.lastTracked = current;

        if (current == null && state.carrying == null) {
            this.states.remove(entity); // nothing left to remember
        }
    }

    /**
     * Drops all carry state when a level unloads.
     * <p>
     * {@link CarryState} holds {@link SubLevel}s, and a {@code SubLevel} holds its {@code Level},
     * which holds every entity in it — including the very entity used as the weak key here. That
     * cycle makes the key strongly reachable, so {@link WeakHashMap} can never expunge the entry
     * and the whole unloaded level is retained. Clearing on unload breaks it. Clearing everything
     * rather than filtering by level is deliberate: the state is per-tick and rebuilds itself
     * immediately for any entity still being carried in a level that is staying.
     */
    @SubscribeEvent
    public void onLevelUnload(final LevelEvent.Unload event) {
        if (!event.getLevel().isClientSide()) {
            this.states.clear();
            this.seeded.clear();
        }
    }

    private boolean isConfigOn() {
        return SureFootingServerConfig.SPEC.isLoaded() && SureFootingServerConfig.CARRY_ENTITIES.get();
    }

    private boolean isEligible(final Entity entity) {
        if (entity instanceof Player || entity instanceof Projectile || entity.isPassenger()) {
            return false;
        }

        return !SureFootingServerConfig.SPEC.isLoaded()
                || SureFootingServerConfig.CARRY_BLACKLIST.get().isEmpty()
                || !SureFootingServerConfig.CARRY_BLACKLIST.get().contains(EntityType.getKey(entity.getType()).toString());
    }

    private boolean shouldStartCarry(final Entity entity, final SubLevel subLevel) {
        if (entity.onGround() || entity.verticalCollision) {
            return false; // tracking ended by landing, not by going airborne
        }

        return this.canCarry(entity, subLevel);
    }

    private boolean shouldStopCarry(final Entity entity, final CarryState state) {
        return entity.onGround()
                || state.carryTicks >= SureFootingServerConfig.CARRY_TIMEOUT_TICKS.get()
                || !this.canCarry(entity, state.carrying);
    }

    private boolean canCarry(final Entity entity, final SubLevel subLevel) {
        if (subLevel.isRemoved() || subLevel.getLevel() != entity.level()) {
            return false;
        }

        if (entity.isInWater() || entity.isInLava()
                || (entity instanceof final LivingEntity living && living.isFallFlying())) {
            return false;
        }

        return withinBounds(entity, subLevel);
    }
}
