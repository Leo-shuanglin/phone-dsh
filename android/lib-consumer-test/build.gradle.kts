// SPDX-License-Identifier: MIT
// The consumer probe: an app that depends on the *published AAR file*, never on
// project(":lib").
//
// Why this module exists. `:lib` is the deliverable (plan §176: the
// composition-root app consumes the AAR), but until this module landed nothing
// proved the artifact worked for anyone but the tree that produced it. Building
// `:app` against `project(":lib")` proves the sources compile together; it says
// nothing about whether `lib-release.aar` + a consumer's own dependency list is
// a usable package.
//
// The dependency is deliberately `files(...)`, not `project(":lib")`: a project
// dependency would leak this build's variant attributes, compile classpath and
// transitive `api` edges into the probe, and then the probe would pass even when
// the AAR alone does not. A file dependency carries nothing but the bytes, which
// is exactly the question being asked.
//
// Two consumption modes are tested here, and they are deliberately different:
//
//   debug   — the AAR file ALONE. This is the delivery claim ("someone can take
//             lib-release.aar and build against it"). It must keep passing on
//             its own; adding a helper artifact here to make it pass would hide
//             the finding this module exists to produce.
//   release — the AAR file plus the sibling artifacts its classes reference.
//             A `files(...)` dependency has no POM, so :lib's `api` edges do
//             not arrive. R8 names exactly which classes that costs you when
//             they are absent, so the release variant supplies them by hand and
//             then asks the one question a minifying consumer asks: does the
//             AAR's own consumer-rules.pro survive R8 and keep what it claims?
//
// What the debug half proves when it compiles:
//   * the public classes the SDK promises (`PdshNavHost` / `PdshNavFacts` /
//     `PdshNavState` / `PdshRoute` / `PdshTheme`) really are in classes.jar and
//     really are `public` from outside the module;
//   * the AAR's public signatures do not need a sibling module's class to be
//     *compiled* (what fails first when a page parameter takes a `:domain` value
//     type without publishing it);
//   * the manifest/resource merge of an AAR that declares a FileProvider works
//     for an app that is not the demo app.
//
// What it does NOT prove (and cannot, by construction): that the AAR alone is
// enough at *runtime*. It is not — see the release half and
// docs/design/android-aar-consumer-api-2026-11-02.md §5.
//
// The AAR is a build product with no Gradle producer edge behind `files(...)`,
// so the build asks for it explicitly. Without that line a clean checkout fails
// in `compileDebugKotlin` with "file ... lib-release.aar does not exist", which
// is a confusing way to learn that a producer is missing.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val androidRoot: File = rootProject.layout.projectDirectory.asFile
val libAar: File = File(androidRoot, "lib/build/outputs/aar/lib-release.aar")

// The hand-supplied POM closure. These are the siblings whose classes the AAR's
// reachable code names, in the exact form R8 reports them when absent:
//   :core      com.phonedsh.agentcore.*              (the chat page's canonical JSON)
//   :domain    com.phonedsh.domain.connector.*/.device.*  (the device pages' value types)
//   :platform  the SPI the two above compile against (JVM jar)
//   :net       the loopback/gateway types :controller re-exports (JVM jar)
// A real embedding app adds :dsh as well (it owns the engine); this probe never
// renders host code, so R8 never reaches it. The *supported* way to get all of
// them is the published POM, which does not exist yet — that is the gap.
val pomClosure = files(
    File(androidRoot, "core/build/libs/core-0.0.0-SNAPSHOT.jar"),
    File(androidRoot, "platform/build/libs/platform-0.0.0-SNAPSHOT.jar"),
    File(androidRoot, "net/build/libs/net-0.0.0-SNAPSHOT.jar"),
    File(androidRoot, "domain/build/outputs/aar/domain-release.aar"),
)

android {
    namespace = "com.phonedsh.consumer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.phonedsh.consumer"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.0"
    }

    buildTypes {
        release {
            // The R8 half of the probe: a consumer that minifies is the normal
            // case (:app does), so the AAR's rules must be read here rather
            // than assumed. `proguard-rules.pro` holds no product rules on
            // purpose — if the build needed one to keep an SDK class alive,
            // that rule would belong in :lib's consumer-rules.pro.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

tasks.named("preBuild") {
    dependsOn(":lib:assembleRelease")
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(":core:jar", ":platform:jar", ":net:jar", ":domain:assembleRelease")
}

dependencies {
    // The published artifact, by path. Nothing about :lib's project is visible
    // to this module's debug compile classpath.
    implementation(files(libAar))
    // The debug-only rule above is enforced by Gradle, not by convention: the
    // closure is on the release compile classpath and nowhere else.
    releaseImplementation(pomClosure)

    // What a consumer of the AAR must supply itself, because a file dependency
    // brings no POM and therefore none of :lib's `api` edges:
    //   * the Compose runtime and the five artifacts :lib declares as `api`
    //     (ui, foundation, ui-text, animation, material3), version-locked by the
    //     same BOM :lib compiled against;
    //   * androidx.core, which the AAR's manifest needs at runtime for its
    //     FileProvider and which no classpath entry provides for a file dep;
    //   * activity-compose, for the host Activity the AAR does not ship.
    implementation(platform(libs.androidx.compose.bom))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-text")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
}
