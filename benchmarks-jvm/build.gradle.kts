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

import io.spine.gradle.report.license.LicenseReporter

// The raw-JMH second benchmark tier: JVM-only benchmarks written against JMH's
// own annotations, for the multi-threaded read-scaling and mixed-load
// benchmarks that kotlinx-benchmark's common facade cannot express (it exposes
// no `@Threads`/`@Group`). Like `benchmarks`, this is a harness module, not a
// published library, so it does NOT apply the `kmp-module` convention.
// Repositories, group, version, and Dokka come from the root `allprojects`
// block (config convention). Benchmark sources live in `src/jmh/kotlin`, the
// source set provided by the `me.champeau.jmh` plugin.
plugins {
    kotlin("jvm")
    id("me.champeau.jmh") version "0.7.3"
    // Version pinned to config's Kotlin (io.spine.dependency.lib.Kotlin) — the
    // plugins block can't reference buildSrc objects.
    kotlin("plugin.allopen") version "2.3.21"
}
LicenseReporter.generateReportIn(project)

// JMH generates harness classes that subclass the `@State` classes (and pad
// them), so those must be `open`.
allOpen {
    annotation("org.openjdk.jmh.annotations.State")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // The KMP project dependency resolves to its JVM variant here.
    jmh(project(":elastic"))
}

jmh {
    warmupIterations.set(5)
    iterations.set(5)
    fork.set(3)
    warmup.set("1s")
    timeOnIteration.set("1s")
    resultFormat.set("JSON")
    resultsFile.set(layout.buildDirectory.file("reports/jmh/results.json"))
}
