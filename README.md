# Sable: Sure Footing

A tiny NeoForge 1.21.1 mod for [Sable](https://github.com/ryanhcode/sable) (the physics engine behind Create: Aeronautics) that keeps you moving with a contraption while you jump, so you land where you took off — including on **rotating** platforms. The player and particle fixes are client-side; the entity fix is server-side, so a server wanting correct item drops on contraptions needs the mod too.

## The bug

Sable carries entities standing on a moving sub-level by warping them along with it every tick ("tracking"). Tracking is dropped as soon as no sub-level blocks are near your bounding box — which happens near the apex of a jump. The fallback is a linear velocity snapshot, which cannot represent a rotating reference frame:

- Jumping repeatedly while standing centered on a spinning swivel bearing drifts you off by a few millimeters per jump.
- Jumping while standing at radius on a rotating platform gives you no rotational carry at all — the platform turns out from under you and you fall off beside it.

## The fix

When you leave the ground while tracked, this mod simply keeps re-applying the tracking sub-level each tick until you land, so Sable's own (correct) warp keeps carrying you through the whole jump arc. It also rotates your airborne velocity with the sub-level's per-tick rotation delta — the warp carries your *position* with the rotating frame, but without this your jump *direction* stays fixed in world space and lags behind the platform's rotation. Together they give platform-frame ballistics: jumping on a spinning platform behaves like jumping on a stationary one.

No mixins — the player fix uses Sable's helper and duck interfaces and is entirely client-side (the server adopts the client's tracking state from movement packets). Note those duck interfaces are Sable-internal (`@ApiStatus.Internal`), which is why the Sable dependency range is pinned to 2.0.x rather than left open.

Carry stops when you land, get tracked by another sub-level, enter water/lava, start flying (creative or elytra), mount a vehicle, move more than a few blocks away from the contraption's bounds (configurable), or after a timeout.

Since 1.2.0 the same treatment covers more than the player:

| What | Side | How |
| --- | --- | --- |
| **You** (jumping, walking) | Client | Carry through jump arcs + frame-rotate velocity |
| **Non-player entities** (item drops, XP orbs, mobs, boats…) | Server | Same carry state machine, plus items dropped over a contraption are put into its frame on their first tick (they pop upward at spawn and would otherwise never catch a fast deck). Projectiles are excluded — Sable already gives them launch velocity, and frame-locking would bend their flight. |
| **Particles** (smoke, flames, block breaking…) | Client | Sable frame-locks particles but releases them after 0.5 blocks of drift with a one-shot linear velocity — rising smoke gets flung tangentially. We re-anchor tracked particles every tick while they stay near the contraption. |

On a server without the mod, the player and particle fixes still work for clients that have it; the entity fix needs the mod on the server.

## Config (`config/surefooting-client.toml`)

| Option | Default | Meaning |
| --- | --- | --- |
| `enabled` | `true` | Toggle the fix |
| `rotate_jump_velocity` | `true` | Rotate your airborne velocity with the sub-level's frame, so a jump aimed across a spinning platform lands where you aimed |
| `rotate_ground_velocity` | `true` | Rotate your walking momentum with the frame too, cancelling most of the sideways (Coriolis-like) pull when moving on a fast spinner |
| `jump_rotation_strength` | `1.16` | Multiplier on the airborne rotation; 1.0 aligns to the frame but trails it slightly while your velocity is applied, values above add lead so cross-jumps stay accurate at any constant spin rate (default tuned in-game) |
| `ground_rotation_strength` | `2.25` | Multiplier on the grounded rotation; higher values also compensate per-step lag on fast spinners (tuned in-game — 2.2–2.3 walks straight), lower toward 1.0 if you curl into the spin |
| `carry_timeout_ticks` | `60` | Max airborne ticks to stay in the contraption's frame |
| `exit_distance_blocks` | `4.0` | Stop carrying once this far outside the sub-level's bounds |
| `anchor_particles` | `true` | Keep particles anchored to the contraption they spawned on while they stay near it |
| `particle_exit_distance_blocks` | `4.0` | Release distance for anchored particles (separate from `exit_distance_blocks`) |
| `debug_logging` | `false` | Log carry transitions and per-jump landing offsets |

Server-side options live in `config/surefooting-server.toml`, next to the client one. (NeoForge also reads `<world>/serverconfig/surefooting-server.toml` as a per-world **override**, but only if you put a file there yourself — it is never created for you.) The options are: `carry_entities` (`true`), `rotate_entity_yaw` (`true` — mobs and armor stands turn with the deck instead of keeping a world-fixed heading), `entity_jump_rotation_strength` (`1.16`), `entity_ground_rotation_strength` (`2.25`), `carry_timeout_ticks` (`60`), `exit_distance_blocks` (`4.0`), `carry_blacklist` (entity ids that should never be carried), `item_throw_lead_ticks` (`0.0` — see Known limits), and `debug_entity_logging` (`false` — logs carried entities' positions in the contraption's own frame, for diagnosing drag; noisy, leave it off, and note it is distinct from the client's `debug_logging`, which covers your own carry). Items dropped by someone riding a contraption are placed into the contraption's reference frame on their first tick (not at spawn — that would misplace them client-side), so they land where they were dropped however fast the deck spins, and keep turning with it once they settle. `exit_distance_overrides` sets per-entity-type carry distances, e.g. `["minecraft:item=16.0", "minecraft:experience_orb=16.0"]`, letting items and XP ride further out than mobs; an item keeps riding a deck it rests on for as long as it stays inside that distance.

## Known limits

The velocity rotation compensates everything that scales linearly with spin rate; what remains is engine-side. Sable's entity collision resolves at most 8 substeps per tick for the local player, so once a platform's tangential speed at your position passes roughly **1 block/tick (~20 m/s)**, collision itself becomes unreliable — expect phantom horizontal pushes from fast-sweeping blocks and the occasional "snowplow" shove. No client-side companion mod can fix that layer.

On a turning contraption a dropped item lands away from where you aimed, further the faster the spin — about 29° of miss per tick of lag on a deck at 96 RPM. This is not the throw and not the flight: the item leaves exactly along where the server has you facing (measured at 0.0° mean over 156 drops, inside vanilla's own ±3.8° randomisation) and then flies a straight line in the contraption's frame (heading constant to ±0.3° across every flight sampled). What lags is the picture you aim with. Sable renders sub-levels from a delayed snapshot (`sub_level_snapshot_interpolation_delay_ticks`, default `1.5`), and your client and connection add more on top, so your crosshair trails the deck and your aim trails with it. Raising Sable's delay visibly moves where items land, which is what confirms it.

If it bothers you, `item_throw_lead_ticks` (server config, default `0.0` = off) aims drops that many ticks ahead of the deck's rotation. One value works at every spin rate, because the correction is that value times the deck's per-tick rotation and that already scales with RPM; around 12–13 suited a local singleplayer client. It is a tuned number rather than a derived one — the server cannot see a client's render delay — so on a shared server no single value suits everyone.

`exit_distance_blocks` is measured from the contraption's *axis-aligned* bounding box, not its hull, so a long ship at 45° yaw releases you further out than the number suggests. It is also slightly wider for players than for entities: Sable reports a swept box (last tick ∪ this tick) client-side and an instantaneous one server-side, so with the same setting a player releases marginally later than an item does.

## Building

```
gradlew build
```

The jar lands in `build/libs/`. Requires Java 21.

## License

MIT. Sable itself is licensed under the Polyform Shield License 1.0.0; this mod compiles against its published jar and contains no Sable code.
