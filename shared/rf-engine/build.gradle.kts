import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
plugins { kotlin("multiplatform") version "2.4.20" }
repositories { mavenCentral() }
kotlin {
    val framework = XCFramework("NodeScopeRF")
    listOf(iosArm64(), iosSimulatorArm64(), macosArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "NodeScopeRF"
            isStatic = true
            binaryOption("bundleId", "org.nodescope.rf")
            framework.add(this)
        }
    }
    sourceSets {
        commonMain { kotlin.srcDir("vendor/kp1812/commonMain/kotlin") }
        commonTest {
            kotlin.srcDir("vendor/kp1812/commonTest/kotlin")
            dependencies { implementation(kotlin("test")) }
        }
    }
}
