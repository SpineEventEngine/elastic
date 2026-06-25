plugins {
    kotlin("multiplatform") version "2.2.20" apply false
}

allprojects {
    apply(from = "$rootDir/version.gradle.kts")
    group = "io.spine"
    version = rootProject.extra["versionToPublish"]!!

    repositories {
        mavenCentral()
    }
}
