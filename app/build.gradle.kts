plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.moon.eatandsport"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.moon.eatandsport"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.2.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.8.7")
    implementation("androidx.lifecycle:lifecycle-livedata:2.8.7")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "1536m"
    maxParallelForks = 1
}
