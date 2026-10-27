import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTargetWithSimulatorTests
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

// The code the Android and iPhone apps share. commonMain is plain Kotlin that
// compiles for both; androidMain and iosMain hold each phone's version of the
// few things that differ, behind the interfaces in com.example.platform. The
// Android app depends on this module; the iPhone app will link the Shared
// framework built from it.
//
// This starts deliberately small — the platform boundary and the pure-Kotlin
// replacements for the JVM APIs that have no iPhone equivalent, and nothing
// else. Compose and Room arrive with the code that needs them, so that a
// failure in this file is a failure of the multiplatform setup itself and not
// of something being carried on top of it.
plugins {
  alias(libs.plugins.kotlin.multiplatform)
  // AGP 9's Android target for multiplatform modules; the older
  // com.android.library cannot be combined with Kotlin Multiplatform.
  alias(libs.plugins.android.kotlin.multiplatform.library)
  // Room's compiler, run once per platform (see dependencies below), and
  // Room's own plugin, which is what knows where to put the exported schema
  // when there is more than one target writing one.
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.androidx.room)
}

// The exported schema, committed and checked in CI. It moved here with Room's
// compiler: the schema is written by KSP, so it is written wherever KSP runs.
room {
  schemaDirectory("$projectDir/schemas")
}

kotlin {
  android {
    // Must differ from the app's com.example; the Kotlin packages don't change.
    namespace = "io.github.inspectorgad.kuwbb.shared"
    compileSdk = 37
    minSdk = 24
    // commonTest runs on the JVM too (testAndroidHostTest), so the answers the
    // iPhone must match are checked where they were recorded.
    withHostTest {}
  }

  listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
    target.binaries.framework {
      baseName = "Shared"
      isStatic = true
    }
  }

  sourceSets {
    commonMain.dependencies {
      // api, not implementation: the entities and the DAO are part of this
      // module's surface, and the app builds the database from them.
      api(libs.androidx.room.runtime)
      implementation(libs.kotlinx.coroutines.core)
      implementation(libs.kotlinx.datetime)
      implementation(libs.kotlinx.serialization.json)
    }
    androidMain.dependencies {
      implementation(libs.okhttp)
    }
    iosMain.dependencies {
      // The SQLite Room talks to on iPhone, compiled into the app so every
      // install has the same engine.
      implementation(libs.androidx.sqlite.bundled)
    }
    commonTest.dependencies {
      implementation(kotlin("test"))
    }
    getByName("androidHostTest").dependencies {
      implementation(kotlin("test-junit"))
    }
  }

  // The simulator iosSimulatorArm64Test runs on: CI picks one the runner has
  // and passes its id, since the default device name changes between Xcodes.
  targets.withType<KotlinNativeTargetWithSimulatorTests>().configureEach {
    providers.environmentVariable("IOS_SIMULATOR").orNull?.let { testRuns.getByName("test").deviceId = it }
  }
}

// List every test with its result, on the JVM and the iPhone simulator alike,
// so the CI log shows the cross-platform checks ran, and how any failed.
tasks.withType<AbstractTestTask>().configureEach {
  testLogging {
    events("passed", "skipped", "failed")
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
  }
}

// The app compiles for Java 11; code inlined into it must not be newer.
tasks.withType<KotlinJvmCompile>().configureEach {
  compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
}

// Room generates the database and DAO code separately for each platform.
dependencies {
  add("kspAndroid", libs.androidx.room.compiler)
  add("kspIosArm64", libs.androidx.room.compiler)
  add("kspIosSimulatorArm64", libs.androidx.room.compiler)
}
