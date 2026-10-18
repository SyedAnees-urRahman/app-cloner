import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Release keystore credentials live in keystore/keystore.properties (local only,
// never committed).
val keystoreProps = Properties()
rootProject.file("keystore/keystore.properties").takeIf { it.exists() }?.let {
    FileInputStream(it).use { stream -> keystoreProps.load(stream) }
}

android {
    namespace = "com.cloner.multiinstance"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.cloner.multiinstance"
        minSdk = 26
        targetSdk = 35
        versionCode = 37
        versionName = "2.30"
        // English-only UI: strip all other language resources.
        resourceConfigurations += "en"
    }

    signingConfigs {
        create("release") {
            keystoreProps.getProperty("storeFile")?.let { storeFile = file(it) }
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
    }

    lint {
        // Offline build env: lint-gradle isn't in the local repo, so the
        // release "vital" lint gate can't run here. CI/Play pre-launch
        // report still covers lint before any store upload.
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += "META-INF/*.version"
            excludes += "META-INF/*.kotlin_module"
            excludes += "**/*.knm"
            excludes += "META-INF/kotlin-project-structure-metadata.json"
            excludes += "nonJvmMain/**"
            excludes += "commonMain/**"
            excludes += "jvmMain/**"
            excludes += "androidMain/**"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

configurations.all {
    // collection-ktx 1.2.0 (pulled in transitively) duplicates classes now in collection-jvm
    exclude(group = "androidx.collection", module = "collection-ktx")
    exclude(group = "androidx.lifecycle", module = "lifecycle-viewmodel-ktx")
    exclude(group = "androidx.lifecycle", module = "lifecycle-runtime-ktx")
    exclude(group = "androidx.lifecycle", module = "lifecycle-livedata-ktx")
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.3")

    val composeBom = platform("androidx.compose:compose-bom:2024.10.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
