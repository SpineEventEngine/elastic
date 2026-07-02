# Team memory

Durable, repo-wide notes for agents and humans. One file per fact; this index lists
them. See [README.md](README.md) for the layout and write protocol.

- [macOS build prerequisites](macos-build-prerequisites.md) — JDK 17+ to run Gradle; install the iOS simulator runtime (`xcodebuild -downloadPlatform iOS`) for the `iosSimulatorArm64` test target.
- [Pre-PR gate](pre-pr-gate.md) — `gh pr create` is blocked until `/pre-pr` writes a PASS sentinel for the current HEAD.
- [Build regenerates dependency reports](build-regenerates-dependency-reports.md) — a full `./gradlew build` regenerates `docs/dependencies/*` in the working tree (commit manually); keep that churn out of feature PRs.
- [License Reports CI gate](license-reports-ci-gate.md) — the check only needs `docs/dependencies/*` to differ from base; regenerate to satisfy it (no version bump needed).
- [detekt thresholds](detekt-thresholds.md) — effective detekt limits (11 functions/class, 60-line methods, complexity 15, ReturnCount 2, …).
- [Lincheck & Native test gotchas](lincheck-and-native-test-gotchas.md) — no commas in backticked test names on Native; Lincheck random scenarios miss narrow races (pin with `addCustomScenario`, verify with a planted mutation); Lincheck 3.x coordinates + required `force()`s.
- [Singular package names](singular-package-names.md) — packages are singular (`io.spine.elastic.benchmark`) unless a plural is explicitly approved; modules/directories are unaffected.
