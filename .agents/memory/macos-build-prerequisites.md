---
name: macos-build-prerequisites
description: A macOS workstation needs JDK 17+ to run Gradle and an installed iOS simulator runtime for the iosSimulatorArm64 test target
metadata:
  type: project
---
Building and testing `elastic` on a macOS workstation requires two prerequisites
beyond a clone:

1. **JDK 17+ to run Gradle.** Gradle 9.x refuses to start on JDK 11/8 ("Gradle
   requires JVM 17 or later"). Point `JAVA_HOME` (or `org.gradle.java.home`) at a
   JDK 17+ install before invoking `./gradlew`.
2. **An iOS simulator runtime for `iosSimulatorArm64Test`.** A full `./gradlew build`
   runs the iOS-simulator test target. With Xcode and the iPhoneSimulator SDK present
   but no installed runtime (`xcrun simctl list runtimes` is empty), the task fails
   with "Xcode does not support simulator tests for ios_simulator_arm64. Check that
   requested SDK is installed." Install the runtime once with
   `xcodebuild -downloadPlatform iOS` (~8.5 GB, no sudo required).

**Why:** the KMP targets include `iosSimulatorArm64` (see `docs/project.md`), so a
green local `build` must exercise JVM, macOS-native, and iOS-simulator tests. CI runs
the same; a missing runtime that only surfaces in CI costs a round-trip.

**How to apply:** on a fresh macOS workstation, set `JAVA_HOME` to a JDK 17+ and run
`xcodebuild -downloadPlatform iOS` before the first full `./gradlew build`.
