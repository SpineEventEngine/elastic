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

import kotlinx.benchmark.gradle.BenchmarkConfiguration

plugins {
    kotlin("multiplatform")
    alias(libs.plugins.benchmark)
    alias(libs.plugins.kotlin.allopen)
}

// `kotlinx-benchmark` generates JMH harness classes that subclass the `@State`
// classes, so those must be `open`. On the JVM `kotlinx.benchmark.State` is a
// typealias to JMH's annotation, so both names must be opened.
allOpen {
    annotation("kotlinx.benchmark.State")
    annotation("org.openjdk.jmh.annotations.State")
}

kotlin {
    jvm()
    jvmToolchain(17)

    // Benchmark on the JVM (authoritative, JMH) and the host plus the primary
    // server target. The raw-JMH tier for GC/alloc profiling is a separate
    // module added when first needed (plan DP-4).
    macosArm64()
    linuxX64()

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":elastic"))
                implementation(libs.kotlinx.benchmark.runtime)
            }
        }
    }
}

benchmark {
    targets {
        register("jvm")
        register("macosArm64")
        register("linuxX64")
    }
    configurations {
        named("main") {
            commonSettings()
            warmups = 5
            iterations = 5
            iterationTime = 1
            iterationTimeUnit = "s"
        }
        // A fast configuration for verifying the harness end-to-end in CI/dev.
        register("smoke") {
            commonSettings()
            warmups = 1
            iterations = 1
            iterationTime = 200
            iterationTimeUnit = "ms"
        }
    }
}

fun BenchmarkConfiguration.commonSettings() {
    reportFormat = "json"
}
