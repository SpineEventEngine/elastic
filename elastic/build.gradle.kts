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
    }
}

// coroutines-test and kotest pull conflicting transitive `atomicfu` versions
// (0.23.1 / 0.26.1) into the Native test klib compile, and KMP klib resolution
// fails on version conflicts rather than picking the highest. Force config's
// resolvable version across all configurations.
configurations.all {
    resolutionStrategy {
        force(AtomicFu.lib)
    }
}
