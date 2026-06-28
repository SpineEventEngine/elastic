/*
 * Copyright 2026, TeamDev. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Redistribution and use in source and/or binary forms, with or without
 * modification, must retain the above copyright notice and the following
 * disclaimer.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

@file:Suppress("unused") // Source set `val`s are used implicitly.

import io.spine.dependency.kotlinx.AtomicFu
import io.spine.dependency.kotlinx.Coroutines
import io.spine.dependency.local.Base
import io.spine.dependency.test.Jol
import io.spine.dependency.test.Kotest
import io.spine.gradle.report.license.LicenseReporter

plugins {
    `kmp-module`
}
LicenseReporter.generateReportIn(project)

kotlin {
    // `kmp-module` configures the JVM target plus the common/JVM test stack
    // (`kotlin.test` + Kotest). Add the Native targets on top (decision DP-2).
    // KSP (DP-7) is re-applied in Phase 1 when the generator module exists.
    macosArm64()
    linuxX64()
    linuxArm64()
    mingwX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        val commonTest by getting {
            dependencies {
                // DP-5: tests use the `kotlin.test` runner with Kotest assertions.
                // `kmp-module` wires the Kotest framework engine, so add the
                // multiplatform `kotlin.test` artifact for the `@Test` runner.
                implementation(kotlin("test"))
                implementation("io.kotest:kotest-property:${Kotest.version}")
                implementation(
                    "org.jetbrains.kotlinx:kotlinx-coroutines-test:${Coroutines.version}"
                )
            }
        }
        val jvmTest by getting {
            dependencies {
                // Bind `kotlin.test` to JUnit 5 on the JVM so `kotlin.test.Test`
                // resolves at compile time regardless of the framework
                // auto-detection (which resolves locally but not in CI's clean
                // environment); kmp-module already wires the JUnit 5 engine.
                implementation(kotlin("test-junit5"))
                // JOL (Java Object Layout) — used only by the retained-footprint
                // measurement (`MemoryFootprintSpec`) to size each map's object
                // graph exactly, on the JVM. Test-scoped; not a published dependency.
                implementation(Jol.lib)
            }
        }
    }
}

// Pin transitive versions that dependency resolution would otherwise get wrong:
//
//  * coroutines-test and kotest pull conflicting transitive `atomicfu` versions
//    (0.23.1 / 0.26.1) into the Native test klib compile, and KMP klib
//    resolution fails on version conflicts rather than picking the highest.
//  * `base-testlib` (added to `jvmTest` by `kmp-module`) drags in a stale
//    `spine-annotations` via its POM; pin it to the current Base-family version
//    so it matches `spine-base` and resolves like the rest of the Spine stack.
//
// Force config's resolvable versions across all configurations.
configurations.all {
    resolutionStrategy {
        force(AtomicFu.lib)
        force(Base.annotations)
    }
}

// `kmp-module` wires the JUnit 5 engine for the JVM target; select the JUnit
// Platform so the JVM test task runs it (and `kotlin.test` binds to JUnit 5).
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
