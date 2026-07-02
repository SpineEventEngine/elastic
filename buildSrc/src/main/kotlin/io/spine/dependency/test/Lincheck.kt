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

package io.spine.dependency.test

/**
 * The dependency on Lincheck, the JetBrains framework for testing concurrent
 * data structures on the JVM, used for the linearizability tests of the
 * single-writer / multi-reader maps. Test-scoped only; not published.
 *
 * The 3.x line lives under the `org.jetbrains.lincheck` group; the former
 * `org.jetbrains.kotlinx:lincheck` coordinates are the frozen 2.x legacy.
 *
 * @see <a href="https://github.com/JetBrains/lincheck">Lincheck</a>
 */
@Suppress("unused", "ConstPropertyName")
object Lincheck {

    // https://github.com/JetBrains/lincheck/releases
    private const val version = "3.6"
    const val lib = "org.jetbrains.lincheck:lincheck:$version"

    /**
     * The Byte Buddy version Lincheck 3.6 depends upon.
     *
     * Declared here so consumers can `force()` it where an older Byte Buddy
     * arrives transitively from another test library and the build fails on
     * the version conflict.
     */
    private const val byteBuddyVersion = "1.14.12"
    const val byteBuddy = "net.bytebuddy:byte-buddy:$byteBuddyVersion"
    const val byteBuddyAgent = "net.bytebuddy:byte-buddy-agent:$byteBuddyVersion"
}
