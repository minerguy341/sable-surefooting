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
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.Map;
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

    private final Map<Entity, CarryState> states = new WeakHashMap<>();

    @SubscribeEvent
    public void onEntityJoin(final EntityJoinLevelEvent event) {
        final Entity entity = event.getEntity();

        if (event.getLevel().isClientSide || !this.isConfigOn()
                || !(entity instanceof final ItemEntity item) || !this.isEligible(entity)) {
            return;
        }

        // Items dropped by someone riding a contraption spawn with the dropper's throw velocity but
        // none of the contraption's motion, so a fast deck races out from under them. Seed them with
        // the sub-level's point velocity — NOT with tracking: tracking an airborne item at spawn
        // flips Sable's networking/interpolation into the relative frame, which misplaces the item
        // client-side. With plain velocity the item flies in the contraption's frame, stays globally
        // networked, and Sable tracks it naturally on touchdown (the same path as items dropped onto
        // the contraption from outside, which already behaves).
        final Entity owner = item.getOwner();
        if (owner == null) {
            return;
        }

        final SubLevel ownerSubLevel = Sable.HELPER.getTrackingSubLevel(owner);
        if (ownerSubLevel == null || ownerSubLevel.isRemoved() || ownerSubLevel.getLevel() != item.level()) {
            return;
        }

        final Vec3 pointVelocity = Sable.HELPER.getVelocity(item.level(), ownerSubLevel, item.position()).scale(1.0 / 20.0);
        item.setDeltaMovement(item.getDeltaMovement().add(pointVelocity));
    }

    @SubscribeEvent
    public void onEntityTick(final EntityTickEvent.Post event) {
        final Entity entity = event.getEntity();

        if (entity.level().isClientSide || !this.isEligible(entity)) {
            return;
        }

        final EntityMovementExtension extension = (EntityMovementExtension) entity;
        SubLevel current = extension.sable$getTrackingSubLevel();

        if (!this.isConfigOn()) {
            this.states.remove(entity);
            return;
        }

        CarryState state = this.states.get(entity);
        if (current == null && state == null) {
            return; // fast path: entity has nothing to do with sub-levels
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
            state.carryTicks = 0;
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

        final BoundingBox3dc bounds = subLevel.boundingBox();
        final double margin = SureFootingServerConfig.EXIT_DISTANCE.get();
        final Vec3 pos = entity.position();

        return pos.x >= bounds.minX() - margin && pos.x <= bounds.maxX() + margin
                && pos.y >= bounds.minY() - margin && pos.y <= bounds.maxY() + margin
                && pos.z >= bounds.minZ() - margin && pos.z <= bounds.maxZ() + margin;
    }
}
