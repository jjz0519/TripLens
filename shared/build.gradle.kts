plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
    alias(libs.plugins.sqldelight)
}

kotlin {
    androidTarget {
        compilations.all { kotlinOptions { jvmTarget = "11" } }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
            implementation(libs.koin.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.sqldelight.sqlite.driver)
            implementation(libs.koin.test)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.android.driver)
            implementation(libs.kotlinx.coroutines.android)
            // Required for LocationProvider actual (FusedLocationProviderClient)
            implementation(libs.play.services.location)
        }
    }
}

android {
    namespace = "com.cooldog.triplens.shared"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        unitTests {
            // android.util.Log (and other framework stubs) return default values instead of
            // throwing RuntimeException in JVM unit tests. Required for ExportUseCase tests
            // since exportLogI/exportLogE use android.util.Log in the androidMain actual.
            isReturnDefaultValues = true
        }
    }
}

sqldelight {
    databases {
        create("TripLensDatabase") {
            packageName.set("com.cooldog.triplens.db")
            srcDirs("src/commonMain/sqldelight")
        }
    }
}
