# AGENT.md — `:dsl`

The entire vocabulary a player can see. Nothing else is on their classpath.

## What it is

Player code is the **body** of a function. The game supplies the signature; the player
supplies what is inside it. So this module documents four things across three files:

- `Replikube` — the contract: `fun block(x: Int, y: Int, z: Int): Int?`, plus the `Level`
  constants (`sizeX`, `minY`, …) injected per level
- `Palette` — 17 ids, `EMPTY` (0) meaning no cube, re-exported as top-level constants so a
  solution writes `RED` rather than `Palette.RED`
- `Geometry` — `chebyshev`, `manhattan`, `dist`, `inCube`, `inDiamond`, `inSphere`,
  `parity`, `isEven`, `isOdd`, `clamp`

```kotlin
when (y) {
    1 -> RED
    0 -> YELLOW
    else -> null
}
```

## The one rule

**This module has zero dependencies. Not one.**

That is the sandbox. Player code is compiled against `:dsl` and the Kotlin stdlib, so
game internals — `:core`, `:app`, `:scripting` — are unreachable from a solution even by
fully-qualified name. Any dependency added here is added to the player's world and can
only be removed by breaking existing solutions.

`testImplementation(kotlin("test"))` is the exception that proves the rule: it is not on
the compile classpath and not transitive, so it is invisible to everything that compiles
player code. It exists to test this module in place rather than from `:core`.

## Why a body, not a declaration

The signature is the one part of a solution that is never the player's. It is identical
for all twenty levels, it says nothing about the puzzle, and typing it is a way to fail
that has nothing to do with the shape being built. Removing it leaves exactly what is
theirs.

## Why `null` and not 0

`block` returns `Int?` and `null` means "leave this empty". `null` is the one value in
Kotlin that cannot be confused with a colour, so `if (r <= 2) RED else null` reads as "a
cube, or nothing" without the player having to remember which number `EMPTY` is.

The folding of `null` → `Palette.EMPTY` happens in exactly one place in the whole project:
`Replikube.asVoxelProgram()` in `:core`. Do not add a second.

`EMPTY` (0) still works and still means empty, so code written against the older contract
keeps running.

## Things to know before editing

- **`Palette` indices are a frozen file format.** Levels and any shared solution depend on
  them. `EMPTY` is 0, `BROWN` is 16, `MAX` is 16. `nameOf` is the reverse lookup and
  `isValid` is the range check the loader uses; `:app`'s syntax highlighter reads names
  from `nameOf`, so renaming a constant renames it in the editor too — which is a breaking
  change for every solution that uses it.
- **Every constant is aliased at file scope** (`const val RED = Palette.RED`). Redeclaring
  them outside the object is what makes the unqualified name visible to player code; an
  import of `Palette.*` would also work, but the generated source does not have one.
- **`Level` in this file is documentation.** The real `Level` object is emitted per level
  by `:scripting` with literal constants baked in, so no state is shared between runs.
  Changing these constants does not change what players see; it changes what the KDoc says.
- **A `when` used as an expression still needs an `else`.** Kotlin 2.4 rejects a
  non-exhaustive one, so the empty case is `else -> null`. This is worth remembering when
  writing a solution that "should" work without it.
- **Coordinates are centred on the origin.** For a grid of size `(sx, sy, sz)`, each axis
  runs over `[-n/2, (n-1)/2]`. Negative `y` is the bottom layer. This is the single most
  common source of player confusion; `Level.minY` and friends exist so nobody has to
  compute it.

## Tests

`./gradlew :dsl:test` — geometry and palette. 12 tests, fast.

There is a round-trip test in `:scripting` that proves a body written against this
contract still compiles and runs, which is the test that would catch a breaking change
here.
