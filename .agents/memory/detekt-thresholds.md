---
name: detekt-thresholds
description: Effective detekt limits to design production Kotlin around in this repo (TooManyFunctions 11, LongMethod 60, complexity 15, ReturnCount 2)
metadata:
  type: reference
---
detekt runs from the config-distributed `buildSrc/quality/detekt-config.yml`, which
overrides only a few rules (`MagicNumber` ignores -1/0/1/2/3; `MaxLineLength` 100; test
files excused from `LongMethod` / `TooManyFunctions` / ticked-name naming). Everything
else uses detekt defaults, so the effective limits for production Kotlin are:

- `TooManyFunctions`: **11 functions per class** (a 12th trips it; nested classes count
  separately; property getters are not counted).
- `LongMethod`: 60 lines.
- `CyclomaticComplexMethod`: complexity 15.
- `ReturnCount`: 2 returns per function.
- `NestedBlockDepth`: 4.
- `LoopWithTooManyJumpStatements`: 1 break/continue/return per loop.

Suppress with a one-line justifying comment where the algorithm genuinely needs it (as
the open-addressing maps in `io.spine.elastic` do), or refactor to fit. The `benchmarks`
module has no `detekt` task.
