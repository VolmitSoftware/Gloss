# Real drops: scripted presentation and physics format

This is the authoritative shape of the `physics` and `script` blocks on the real-drops document,
written for the editor implementation. The Java parser (`RealDropSettingsDoc`) and the compiler
(`RealDropScriptPlan`) are the runtime authority; everything below describes what they actually do.

- Document path: `plugins/Gloss/real-drops/default.json`
- Document kind: `real-drops`, shipped default id `default`
- JSON schema: `schema/gloss-real-drops.schema.json` (every property carries the user-facing
  `description` the inspector renders)
- Restore the shipped document with `/gloss drops reset` (permission `gloss.drops.reset`)

Both blocks are additive and disabled by default. They live inside each complete `presentation`,
including the fallback presentation and every conditional variant.

---

## 1. Document shape

```json
{
  "schemaVersion": 3,
  "revision": 1,
  "presentation": {
    "limits":   { "...": "unchanged" },
    "scale":    { "...": "unchanged" },
    "motion":   { "...": "unchanged" },
    "landing":  { "...": "unchanged" },
    "labels":   { "...": "unchanged" },
    "filters":  { "...": "unchanged" },
    "physics": {
      "enabled": false,
      "gravityMultiplier": 1.0,
      "bounce": 0.0,
      "waterBuoyancy": 0.0,
      "waterDrag": 0.0
    },
    "script": {
      "enabled": false,
      "vars":     { "<name>": "<expression>" },
      "offset":   { "x": "0", "y": "0", "z": "0" },
      "rotation": { "x": "0", "y": "0", "z": "0" },
      "scale":    { "x": "1", "y": "1", "z": "1" },
      "glow":     "",
      "visible":  "true"
    }
  },
  "variants": [],
  "audience": { "when": "true" }
}
```

Every key in both blocks is optional. Omitting a block, an axis object, or a single axis falls back
to the neutral value shown above. An axis given as `""` or whitespace is treated as omitted.

### `physics` fields

| Field | Type | Default | Range | Meaning |
|---|---|---|---|---|
| `enabled` | boolean | `false` | | Master switch. While false Gloss never touches the item entity's velocity or gravity flag. |
| `gravityMultiplier` | number | `1.0` | 0 – 4 | Scales how hard the item falls. |
| `bounce` | number | `0.0` | 0 – 0.9 | Restitution on landing. |
| `waterBuoyancy` | number | `0.0` | 0 – 1 | Upward velocity added per update while submerged. |
| `waterDrag` | number | `0.0` | 0 – 1 | Fraction of velocity removed per update while submerged. |

Out-of-range numbers are clamped silently at load, they are not errors.

### `script` fields

| Field | Type | Default | Meaning |
|---|---|---|---|
| `enabled` | boolean | `false` | Master switch for evaluation. Expressions are compiled and validated either way. |
| `vars` | object of name to expression | `{}` | Author-defined intermediates, evaluated first in file order. Max 32. |
| `offset` | axis object | `{"x":"0","y":"0","z":"0"}` | Extra display displacement in blocks, added to the offset the document already computed. |
| `rotation` | axis object | `{"x":"0","y":"0","z":"0"}` | Extra rotation in degrees, composed onto the existing pose in X then Y then Z order. |
| `scale` | axis object | `{"x":"1","y":"1","z":"1"}` | Multiplier on the resolved scale family, per axis. |
| `glow` | expression string | `""` | Glow colour. Empty string means the feature is off entirely. |
| `visible` | expression string | `"true"` | Boolean gate. Must produce `true`/`false`, not a number. |

An axis object is `{ "x": <expression>, "y": <expression>, "z": <expression> }`. X is east, Y is up,
Z is south, matching world space.

Result clamps applied every evaluation:

| Field | Clamp | Fallback on a runtime failure |
|---|---|---|
| `offset.*` | -16 to 16 blocks | `0` |
| `rotation.*` | -3600 to 3600 degrees | `0` |
| `scale.*` | 0 to 16 | `1` |
| `glow` | none | `0` (no glow) |
| `visible` | none | `true` |

---

## 2. What is real and what is only a picture

Be precise about this in the editor's copy; the distinction is load-bearing.

**The `physics` block moves the actual `Item` entity.** Gloss writes to the entity's velocity and
gravity flag through Bukkit, so the item's real position, its collision and its pickup radius all
follow. These are genuine physics changes, not illusions:

- `gravityMultiplier` — at exactly `1` nothing is touched. Above or below `1`, Gloss adds a
  corrective vertical velocity of `0.04 * (multiplier - 1) * updateIntervalTicks` per update while
  the item is airborne, on top of vanilla gravity. At exactly `0` the entity's gravity flag is
  cleared and the item hangs in place; the flag is restored when the presentation ends. Because the
  correction lands once per update rather than once per tick, the effective acceleration is an
  approximation, and it gets coarser as `limits.updateIntervalTicks` rises. At exactly `0`, Gloss
  also clears existing vertical velocity and restores the entity's original gravity flag later.
- `bounce` — vanilla items do not bounce at all. Gloss detects the landing tick (the item is on the
  ground now, was not last update, and was approaching faster than 0.08 blocks per tick) and
  rewrites the vertical velocity to `-approachSpeed * bounce`. The item really leaves the ground.
  Each bounce increments `bounces` and, when `motion.changeOnBounce` is on, re-rolls the tumble.
- `waterBuoyancy` — adds `0.02 * buoyancy * updateIntervalTicks` to the vertical velocity each
  update while `isInWater()`. A buoyant item genuinely rises through the water column.
- `waterDrag` — multiplies all three velocity components by `(1 - drag) ^ updateIntervalTicks` each
  update while submerged. At `1` the item stops dead on entering water.

While physics is enabled and the item is in water, the settle detection is bypassed for the polling
rate: the update interval stays at `limits.updateIntervalTicks` instead of dropping to
`limits.settledPollIntervalTicks`, because a buoyant item is never really at rest.

Physics only applies to items that currently have a Gloss presentation. A drop excluded by
`filters`, or one that lost its slot to `limits.maxVisualsPerChunk`, falls exactly as vanilla does.

**The `script` block moves the picture only.** `offset`, `rotation`, `scale`, `glow` and `visible`
drive the `ItemDisplay` or `BlockDisplay` entities that stand in for the item. The item entity itself does not move,
so:

- A scripted bob or a large `offset` visually separates the model from the thing a player walks over
  to pick up. The pickup radius stays where Minecraft put the item.
- `scale` changes the model's size, not the item's hitbox.
- `visible: false` is implemented by driving that display's view range to zero so clients stop
  rendering it. The item is still there and still pickupable — this hides the model, it does not
  remove the drop.
- `glow` sets the display's glow colour override and turns glowing on. Only the red, green and blue
  channels reach the client; any alpha in the value is discarded. The outline is drawn by the client
  and is visible through blocks. It applies to the item models, not to the floating name label.

If you need the item itself to bob, bounce, float or fall differently, that is the `physics` block.
The `script` block cannot do it and should not be presented as if it can.

---

## 3. Evaluation model

1. The document loads. Every expression in `script` is parsed and validated, **whether or not
   `script.enabled` is true**. A bad expression refuses the whole document (see section 6), so an
   operator finds out immediately rather than when they flip the switch.
2. If `script.enabled` is false, nothing else happens. The presentation is what it was before the
   block existed.
3. If it is true, a plan that does not reference `index` is evaluated once per stack and
   shared by its displays. Per-model plans evaluate with `index` running from 0 to `count - 1`.
4. Within one evaluation: `vars` are evaluated first in declaration order, then `offset`, `rotation`,
   `scale`, `glow`, `visible`.
5. The results compose onto what the document already computed. They never replace it:
   - final display offset = model offset for this index + `offset`, with the resting-height
     correction recomputed from the final pose so a rotated block does not sink into the floor
   - final pose = tumble/landing pose for this index, then `rotation` applied in X, Y, Z order
   - final scale = the resolved scale family value, multiplied per axis by `scale`

`vars` values must be numbers. Encode a flag as `1` and `0` and test it with a comparison, for
example `"lit": "materialMatches('*_TORCH') ? 1 : 0"` then `lit > 0` later.

Environment probing (`height`, `blockLight`, `skyLight`) costs block lookups, so it is only
performed when at least one expression in the document actually references one of those three names.
A document that never mentions them pays nothing. Static settled plans also stop reevaluating until
the animation changes; plans referencing time, motion, or environment inputs retain sparse updates.

---

## 4. Variables

All variables are read-only. Numbers are doubles; there are no integers in the language.

| Name | Type | Meaning |
|---|---|---|
| `t` | number | Seconds since Gloss began presenting this stack. Starts at 0 and never resets. |
| `age` | number | Ticks the item entity has been alive (`Item.getTicksLived()`). Larger than `t * 20` for an item that existed before Gloss picked it up. |
| `index` | number | Which display in the stack this evaluation is for, starting at 0. |
| `count` | number | How many displays this stack currently has, 1 to 5. |
| `amount` | number | Items in the stack. |
| `onGround` | boolean | The item entity reports standing on a block. |
| `settled` | boolean | The item has come to rest and landing detection has finished; it is now polled at the slow interval. |
| `phase` | string | Current unified animation phase: `AIRBORNE`, `REBOUNDING`, `ROLLING`, `SETTLING`, `SETTLED`, or `SUBMERGED`. |
| `stateTime` | number | Seconds spent in the current animation phase. Resets when `phase` changes. |
| `impactSpeed` | number | Downward approach speed captured at the latest impact. |
| `inWater` | boolean | `Item.isInWater()`. |
| `inLava` | boolean | The block at the item's position is lava. |
| `bounces` | number | Landings counted for this item so far, whether or not `motion.changeOnBounce` is on. |
| `velocityX` | number | Velocity along X, blocks per tick. |
| `velocityY` | number | Velocity along Y, blocks per tick. Negative when falling. |
| `velocityZ` | number | Velocity along Z, blocks per tick. |
| `speed` | number | Magnitude of that velocity vector. |
| `height` | number | Blocks between the item and the collision-shape surface below it, probed up to 32 blocks down. Slabs, stairs, snow and other partial blocks use their real collision boxes. Only computed if referenced. |
| `blockLight` | number | Block light level at the item's position, 0 to 15. Only computed if referenced. |
| `skyLight` | number | Sky light level at the item's position, 0 to 15. Only computed if referenced. |
| `random` | number | A value in `[0, 1)` derived from the item's UUID. Fixed for the lifetime of the item, so a value built on it never flickers. |
| `material` | string | The item's material name, upper case, no namespace, e.g. `"REDSTONE_TORCH"`. |
| `isBlock` | boolean | This item resolved to the full-block scale family (`scale.defaultScale`). |
| `isFlat` | boolean | This item resolved to the non-block ItemDisplay scale family (`scale.flatItems`). Placeable materials use BlockDisplay regardless of their inventory icon. |
| `isThin` | boolean | This item resolved to the thin scale family (`scale.thinBlocks`): slabs, carpets, pressure plates and snow. |
| `pi` | number | 3.14159... |

Exactly one of `isBlock`, `isFlat`, `isThin` is true for any item.

A `vars` name must be a plain identifier (`[A-Za-z_][A-Za-z0-9_]*`), must not repeat, and must not
shadow any name in the table above. A `vars` expression can read every variable above and every
`vars` entry declared **before** it — not itself, and not one declared later.

---

## 5. Functions

### Drop-specific

| Call | Returns | Meaning |
|---|---|---|
| `materialIs(name)` | boolean | Exact material match, case-insensitive. A `minecraft:` prefix on the argument is stripped, and spaces and hyphens are normalised to underscores. `materialIs('torch')` is true for `TORCH` and false for `REDSTONE_TORCH`. |
| `materialMatches(glob)` | boolean | Glob match against the material name, case-insensitive, same normalisation. `*` matches any run of characters, `?` matches exactly one. `materialMatches('*_TORCH')` covers `REDSTONE_TORCH`, `SOUL_TORCH` and `WALL_TORCH`. |

### Standard library

Shared with every other Gloss expression surface (holograms, bubbles, container previews).

| Call | Returns | Notes |
|---|---|---|
| `clamp(x, lo, hi)` | number | |
| `lerp(a, b, t)` | number | `t` is not clamped. |
| `min(a, b)` | number | |
| `max(a, b)` | number | |
| `floor(x)` | number | |
| `ceil(x)` | number | |
| `round(x)` | number | Java rounding: `round(-2.5)` is `-2`, not `-3`. |
| `abs(x)` | number | |
| `mod(a, b)` | number | Floored modulo: `mod(-1, 3)` is `2`. Throws on a zero divisor. |
| `pow(a, b)` | number | Throws if the result is not finite. |
| `smoothstep(edge0, edge1, x)` | number | Throws if the edges are equal. |
| `sin(x)` | number | Radians. |
| `cos(x)` | number | Radians. |
| `rgb(r, g, b)` | number | Packs an opaque ARGB colour, channels clamped to 0-255. |
| `argb(a, r, g, b)` | number | Packs ARGB with an explicit alpha. |
| `alpha(color, a)` | number | Replaces the alpha channel of a colour. |
| `mix(c1, c2, t)` | number | Per-channel linear blend, `t` clamped to 0-1. |
| `palette(list, i)` | number | Wrapping index into a numeric array literal. |
| `select(list, i)` | any | Wrapping index into any array literal. |
| `number(x)` | number | Parses the first number out of a string, ignoring colour codes and commas. |
| `bar(value, max, width, filled, empty)` | string | |
| `hex(color)` | string | Renders `[RRGGBB]`. |
| `str(x)` | string | Integral doubles render without a decimal point: `54.0` becomes `"54"`. |
| `fixed(x, digits)` | string | `digits` must be a whole number 0-20. |
| `plain(text)` | string | Strips legacy `&x` colour codes. |
| `readable(text)` | string | `IRON_ORE` becomes `"Iron Ore"`. |
| `align(text, width, mode)` | string | Pads visible character cells with `left`, `center`/`middle`, or `right`; longer text is never truncated. |

Only `glow` has any use for the string- and colour-returning functions; `offset`, `rotation` and
`scale` must produce numbers and `visible` must produce a boolean.

### Grammar

Numbers; single- or double-quoted strings with the escapes `\\` `\'` `\"` `\n`; colour literals
`#RGB`, `#RRGGBB`, `#AARRGGBB` (the three- and six-digit forms are opaque, only the eight-digit form
carries its own alpha); `true` / `false`; variables; array literals `[a, b, c]` for `palette` and
`select`; calls; unary `!` and `-`; then `*` `/` `%`, `+` `-`, `<` `<=` `>` `>=`, `==` `!=`, `&&`,
`||`, and `a ? b : c`.

`&&` and `||` short-circuit, so `onGround && height < 1` never evaluates the right side when the
item is airborne. `+` concatenates when either side is a string, otherwise it adds. `%` is Java's
truncating remainder (`-1 % 3` is `-1`) while `mod()` floors; both throw on a zero divisor. `==` and
`!=` compare two numbers, two strings, or two booleans — mixing types is an error.

Each expression is capped at 512 characters and 256 levels of nesting.

### Colours for `glow`

`glow` accepts either form and an empty source turns the feature off:

- a **number**, which is an ARGB colour: a `#RRGGBB` literal, or `rgb()` / `argb()` / `mix()` /
  `palette()`. A result of exactly `0` means no glow — this is how a conditional glow is written.
- a **string**, which must be `#RRGGBB` or `#AARRGGBB`. An empty string means no glow.

Only the red, green and blue channels are used.

---

## 6. Validation and failure

**At load**, a problem in any expression refuses the entire document with a message that names the
file, the field and, where the parser knows it, the character position. The previously loaded
document stays in force and the server keeps running. Messages the editor should expect to surface:

```
default.json script.offset.y: unclosed paren at position 5
default.json script.offset.z: unknown variable 'wobble' at position 4
default.json script.offset.x: unknown function 'wiggle' at position 0
default.json script.offset.x must evaluate to a number, got string
default.json script.visible must evaluate to true or false, got number
default.json script.glow string must be #RRGGBB or #AARRGGBB, got 'not a colour'
default.json script.vars.speed shadows the built-in variable speed
default.json script.vars.mine is not a valid name; use letters, digits and underscores starting with a letter or underscore
default.json script.vars.mine is declared twice
default.json script.vars.mine must be a non-blank expression
default.json script.vars declares an entry with no name
default.json script.vars declares 33 variables; the limit is 32
default.json script.offset.y exceeds 512 characters
default.json script.offset.y result must be finite
```

Type checking is done by evaluating every expression against four synthetic sample contexts that
span airborne, grounded, settled-in-water and airborne-in-lava states with a mix of materials and
shape families. An expression that throws for any of them — including a division by zero that only
happens when `speed` is 0 — is refused at load rather than silently degrading later.

**At runtime**, a failure in one field does not break the drop. The field falls back to its neutral
value from the table in section 1, and the plan logs one warning for the whole document rather than
spamming the console every tick.

---

## 7. Worked examples

### 7.1 A torch that glows

Any torch variant gets a warm orange outline; everything else is untouched. The two tests together
cover plain `TORCH` and the underscore variants (`REDSTONE_TORCH`, `SOUL_TORCH`, `WALL_TORCH`)
without also catching `TORCHFLOWER`.

```json
{
  "schemaVersion": 3,
  "revision": 2,
  "presentation": {
    "script": {
      "enabled": true,
      "glow": "materialIs('torch') || materialMatches('*_TORCH') ? #FFAA55 : 0"
    }
  },
  "variants": [],
  "audience": { "when": "true" }
}
```

With a named intermediate, so the same test can drive more than one field — here the torch also
sits slightly higher and a touch larger:

```json
{
  "schemaVersion": 3,
  "revision": 3,
  "presentation": {
    "script": {
      "enabled": true,
      "vars": {
        "isTorch": "materialIs('torch') || materialMatches('*_TORCH') ? 1 : 0",
        "isLit": "isTorch > 0 && blockLight > 8 ? 1 : 0"
      },
      "offset": { "y": "isTorch * 0.08" },
      "scale":  { "x": "1 + isTorch * 0.15", "y": "1 + isTorch * 0.15", "z": "1 + isTorch * 0.15" },
      "glow":   "isLit > 0 ? #FFCC66 : (isTorch > 0 ? #FFAA55 : 0)"
    }
  },
  "variants": [],
  "audience": { "when": "true" }
}
```

`isLit` reads `isTorch`, which is legal because `isTorch` is declared first. Referencing
`blockLight` is what makes Gloss probe the light level at all.

### 7.2 A stack that bobs in water

Real buoyancy on the item, plus a visual bob and a slow spin so the floating stack reads as
floating. The two halves are independent: `physics` lifts the item, `script` animates the model.

```json
{
  "schemaVersion": 3,
  "revision": 4,
  "presentation": {
    "physics": {
      "enabled": true,
      "waterBuoyancy": 0.35,
      "waterDrag": 0.12
    },
    "script": {
      "enabled": true,
      "vars": {
        "wavePhase": "t * 2 + index * 1.2 + random * 6.283",
        "bob": "inWater ? sin(wavePhase) * 0.09 : 0"
      },
      "offset":   { "y": "bob" },
      "rotation": { "z": "inWater ? sin(wavePhase) * 6 : 0", "y": "inWater ? t * 20 : 0" }
    }
  },
  "variants": [],
  "audience": { "when": "true" }
}
```

`random` shifts each item's phase so a pile of drops does not bob in lockstep, and `index` offsets
the models within one stack from each other. Both are stable, so nothing flickers between updates.

### 7.3 Resize on bounce

Bouncing is a real physics change; the squash is the visual reaction to it. The model pops out and
settles back over roughly half a second after each landing, and shrinks a little for every bounce
it has already taken so a long tumble visibly loses energy.

```json
{
  "schemaVersion": 3,
  "revision": 5,
  "presentation": {
    "physics": {
      "enabled": true,
      "bounce": 0.45
    },
    "script": {
      "enabled": true,
      "vars": {
        "energy": "clamp(1 - bounces * 0.08, 0.6, 1)",
        "sinceBounce": "clamp(t - floor(t), 0, 1)",
        "pop": "onGround ? 0 : (1 - smoothstep(0, 0.5, sinceBounce)) * 0.35"
      },
      "scale": {
        "x": "energy * (1 + pop * 0.5)",
        "y": "energy * (1 - pop)",
        "z": "energy * (1 + pop * 0.5)"
      },
      "rotation": { "x": "pop * 25" }
    }
  },
  "variants": [],
  "audience": { "when": "true" }
}
```

Note the honest limitation: the language has no memory between evaluations, so there is no
"seconds since the last bounce" variable. The example above approximates it from `t`. If the editor
wants a true bounce clock it has to come from a new runtime variable, not from a document.

---

## 8. Editor notes

- Both blocks are optional inside a presentation. A conditional variant carries a complete
  presentation instead of inheriting or merging fields from the fallback.
- `vars` is an ordered map. Declaration order is semantically significant and must be preserved on
  read, on edit, and on write. Do not sort it.
- Every clamp in section 1 is applied by the server after the document loads, so the editor can
  show the clamped result but must send what the user typed.
- The inspector help text comes from the `description` fields in
  `schema/gloss-real-drops.schema.json`. That file is the single source for user-facing wording.
- `RealDropModel` — the presentation maths ported to Dart in the editor — is **unchanged** by this
  feature. The script layer composes on top of its output and never alters it.
