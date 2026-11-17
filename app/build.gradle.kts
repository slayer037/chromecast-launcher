plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.majendra.tvlauncher"
    compileSdk = 35
    defaultConfig { applicationId = "com.majendra.tvlauncher.opensource"; minSdk = 26; targetSdk = 34; versionCode = 59; versionName = "1.0.0-rc59" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    // Release is non-debuggable so ART can AOT-compile it; the debug key keeps in-place upgrades (and app data) on the TV.
    buildTypes {
        release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("debug") }
        create("bedroomRelease") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
        }
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil:2.7.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // Real org.json for JVM tests; Android's copy is a stub there.
}
