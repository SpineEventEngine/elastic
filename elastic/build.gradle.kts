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

plugins {
    kotlin("multiplatform")
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
}

detekt {
    // Lexical/AST analysis only (no type-resolution classpath) to stay decoupled
    // from the KMP compile outputs and the tooling-version lag (see plan DP-3).
    buildUponDefaultConfig = true
    parallel = true
    config.setFrom(rootProject.file("gradle/detekt.yml"))
    // The default source roots are the JVM-style `src/main`/`src/test`, which a
    // KMP module does not use; point detekt at the multiplatform source sets.
    source.setFrom(
        "src/commonMain/kotlin",
        "src/commonTest/kotlin",
        "src/jvmMain/kotlin",
        "src/jvmTest/kotlin",
        "src/nativeMain/kotlin",
        "src/nativeTest/kotlin",
    )
}

kotlin {
    jvm()
    jvmToolchain(17)

    // Native targets (DP-2: JVM + Native). The Kotlin default hierarchy template
    // creates the shared `nativeMain`/`nativeTest` (and `appleMain`) source sets
    // used for the `expect`/`actual` seams introduced in later phases.
    macosArm64()
    linuxX64()
    linuxArm64()
    mingwX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotest.assertions.core)
                implementation(libs.kotest.property)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
