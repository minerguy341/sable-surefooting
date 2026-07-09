# Sable: Sure Footing

A tiny client-side NeoForge 1.21.1 mod for [Sable](https://github.com/ryanhcode/sable) (the physics engine behind Create: Aeronautics) that keeps you moving with a contraption while you jump, so you land where you took off — including on **rotating** platforms.

## The bug

Sable carries entities standing on a moving sub-level by warping them along with it every tick ("tracking"). Tracking is dropped as soon as no sub-level blocks are near your bounding box — which happens near the apex of a jump. The fallback is a linear velocity snapshot, which cannot represent a rotating reference frame:

- Jumping repeatedly while standing centered on a spinning swivel bearing drifts you off by a few millimeters per jump.
- Jumping while standing at radius on a rotating platform gives you no rotational carry at all — the platform turns out from under you and you fall off beside it.

## The fix

When you leave the ground while tracked, this mod simply keeps re-applying the tracking sub-level each tick until you land, so Sable's own (correct) warp keeps carrying you through the whole jump arc. It also rotates your airborne velocity with the sub-level's per-tick rotation delta — the warp carries your *position* with the rotating frame, but without this your jump *direction* stays fixed in world space and lags behind the platform's rotation. Together they give platform-frame ballistics: jumping on a spinning platform behaves like jumping on a stationary one.

No mixins — it only uses Sable's public helper and duck interfaces, entirely client-side (the server adopts the client's tracking state from movement packets).

Carry stops when you land, get tracked by another sub-level, enter water/lava, start flying (creative or elytra), mount a vehicle, move more than a few blocks away from the contraption's bounds (configurable), or after a timeout.

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
| `debug_logging` | `false` | Log carry transitions and per-jump landing offsets |

## Known limits

The velocity rotation compensates everything that scales linearly with spin rate; what remains is engine-side. Sable's entity collision resolves at most 8 substeps per tick for the local player, so once a platform's tangential speed at your position passes roughly **1 block/tick (~20 m/s)**, collision itself becomes unreliable — expect phantom horizontal pushes from fast-sweeping blocks and the occasional "snowplow" shove. No client-side companion mod can fix that layer.

## Building

```
gradlew build
```

The jar lands in `build/libs/`. Requires Java 21.

## License

MIT. Sable itself is licensed under the Polyform Shield License 1.0.0; this mod compiles against its published jar and contains no Sable code.
