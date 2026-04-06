import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    // Required for @Serializable route objects used by Navigation Compose 2.8 type-safe navigation.
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    sourceSets {
        androidMain.dependencies {
            implementation(project(":shared"))
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.activity.compose)
            implementation(libs.navigation.compose)
            implementation(libs.play.services.location)
            implementation(libs.koin.android)
            // koin-androidx-compose provides koinViewModel() for use inside Composables.
            // koin-android alone only provides inject() for Activities/Services/Fragments.
            implementation(libs.koin.androidx.compose)
            implementation(libs.coil.compose)
            implementation(libs.coil.video)
            implementation(libs.datastore.preferences)
            implementation(libs.maplibre.android)
            implementation(libs.kotlinx.coroutines.android)
        }
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            // Extended icon set for Map, Mic, Settings icons used in BottomNavBar.
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.kotlinx.coroutines.core)
            // kotlinx-serialization-json is needed for @Serializable on Navigation route objects.
            // :shared declares it as 'implementation' (not 'api') so it is not exposed transitively.
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        androidUnitTest.dependencies {
            // JUnit 4 runner for Android JVM unit tests (no emulator required).
            implementation(libs.junit)
            // Coroutine testing utilities: runTest, StandardTestDispatcher, advanceUntilIdle.
            implementation(libs.kotlinx.coroutines.test)
        }
        androidInstrumentedTest.dependencies {
            implementation(libs.androidx.test.junit)
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.rules)
            implementation(libs.androidx.test.core)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// compose.uiTooling must be debugImplementation via the Android dependency block,
// not a KMP source set — KMP has no androidDebug source set concept.
dependencies {
    debugImplementation(compose.uiTooling)
}

android {
    namespace = "com.cooldog.triplens"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.cooldog.triplens"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        unitTests {
            // android.util.Log (and other framework stubs) return default values instead of
            // throwing RuntimeException in JVM unit tests. Without this, any class that calls
            // Log.d/i/e fails immediately — including ViewModels that log in their init block.
            isReturnDefaultValues = true
        }
    }
}
