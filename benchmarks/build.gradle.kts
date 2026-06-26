plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm()

    jvmToolchain(17)

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":elastic"))
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
