plugins { id("com.android.application") }

android {
    namespace = "com.bravo.ondevicestt"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.bravo.ondevicestt"
        minSdk = 31
        targetSdk = 37
        versionCode = 9
        versionName = "0.7.2"
    }
}

dependencies {
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("com.google.mlkit:genai-speech-recognition:1.0.0-alpha1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
