# Changelog

Each section is paste-ready for the matching Modrinth version's changelog field.

## 1.2.1 — 2026-07-10

**Critical fix — update from 1.1.0/1.2.0.**

- Fixed a crash on **non-rotating** contraptions: standing or jumping on a parked ship or a resting free-floating platform could poison the player's velocity with NaN, freezing them in place and eventually crashing the client. The velocity rotation now skips identity frame-deltas and never writes non-finite velocity.

## 1.2.0 — 2026-07-09

Now covers more than the player:

- **Non-player entities** (item drops, XP orbs, mobs, boats, …) are carried through airborne arcs on moving/rotating contraptions, server-side. Projectiles are deliberately excluded — Sable already gives them launch velocity, and frame-locking would bend their flight.
- **Items dropped while riding a contraption** inherit its velocity at spawn and land where you dropped them.
- **Mobs and armor stands turn with the deck** (`rotate_entity_yaw`) instead of keeping a world-fixed heading while the platform rotates under them.
- **Particles** (smoke, flames, block-break debris) stay anchored to the contraption they spawned on instead of being flung tangentially after half a block of drift.
- New per-world **server config** (`serverconfig/surefooting-server.toml`): carry toggle, rotation strengths, timeout, exit distance, entity blacklist. New `anchor_particles` client option.

The player and particle fixes work client-side even on servers without the mod; the entity carry needs the mod on the server.

## 1.1.0 — 2026-07-09

- New `jump_rotation_strength` option (default 1.16, tuned in-game): compensates the half-tick phase lag of the airborne velocity rotation, keeping cross-jumps accurate however fast the platform spins.
- README: documented engine-side limits (Sable's collision substeps cap reliable tangential speed at ~1 block/tick).

## 1.0.0 — 2026-07-08

Initial release for NeoForge 1.21.1 + Sable 2.0.x.

- Keeps you tracked through jump/fall arcs on moving Sable contraptions, so you land where you took off — including on **rotating** platforms (Sable's stock behavior drops tracking mid-jump and its linear fallback can't represent a rotating frame).
- Rotates your airborne velocity with the contraption's frame: jumps aimed across a spinner go where you aimed (`rotate_jump_velocity`).
- Compensates the sideways (Coriolis-like) pull while walking on fast spinners (`rotate_ground_velocity`, tunable `ground_rotation_strength`).
- Client-side only, no mixins; in-game config screen via the Mods menu.
