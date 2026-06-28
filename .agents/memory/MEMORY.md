# Team memory

Durable, repo-wide notes for agents and humans. One file per fact; this index lists
them. See [README.md](README.md) for the layout and write protocol.

- [macOS build prerequisites](macos-build-prerequisites.md) — JDK 17+ to run Gradle; install the iOS simulator runtime (`xcodebuild -downloadPlatform iOS`) for the `iosSimulatorArm64` test target.
- [Pre-PR gate](pre-pr-gate.md) — `gh pr create` is blocked until `/pre-pr` writes a PASS sentinel for the current HEAD.
- [Build auto-commits dependency reports](build-auto-commits-dependency-reports.md) — a full `./gradlew build` regenerates and commits `docs/dependencies/*`; keep that churn out of feature PRs.
- [detekt thresholds](detekt-thresholds.md) — effective detekt limits (11 functions/class, 60-line methods, complexity 15, ReturnCount 2, …).
