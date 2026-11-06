plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
fun javaString(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
android {
    namespace = "com.agentdeck.hotel"
    compileSdk = 34
    // Private same-signer instrumentation is used only for disposable release-upgrade tests.
    testBuildType = if (project.hasProperty("upgradeFixture")) "release" else "debug"
    defaultConfig {
        fun injected(name: String): String {
            val env = System.getenv(name)
            if (!env.isNullOrBlank()) return env
            val prop = project.findProperty(name) as String?
            if (!prop.isNullOrBlank()) return prop
            val file = rootProject.file("local.properties")
            if (!file.exists()) return ""
            return file.readLines().firstOrNull { it.substringBefore("#").trim().startsWith("$name=") }
                ?.substringAfter("=")?.trim().orEmpty()
        }
        applicationId = "dev.orange.hotel"
        minSdk = 26
        targetSdk = 34
        testInstrumentationRunner = if (project.hasProperty("upgradeFixture"))
            "com.agentdeck.hotel.UpgradeDataFixture" else "com.agentdeck.hotel.VoiceLifecycleFixture"
        // Increment explicitly for every published APK; never derive identity from a CI run.
        versionCode = rootProject.file("VERSION_CODE").readText().trim().toInt().also {
            require(it > 34) { "Release candidates must supersede published code 34" }
        }
        buildConfigField("String", "FEED_URL", javaString(project.findProperty("feedUrl") as String? ?: "https://github.com/somdipto/manzanilla/releases/latest/download/release-metadata.json"))
        buildConfigField("String", "LEGACY_FEED_URL", javaString(project.findProperty("legacyFeedUrl") as String? ?: "https://api.github.com/repos/somdipto/manzanilla/releases/latest"))
        // Never embed a Fish credential in any build, including CI artifacts.
        // Provider access comes from a device-entered key (SecretStore) or an enrolled hotel service.
        buildConfigField("String", "FISH_API_KEY", javaString(""))
        buildConfigField("String", "FISH_VOICE_ID", javaString(injected("FISH_VOICE_ID")))
        manifestPlaceholders["cleartext"] = if (project.hasProperty("feedUrl") || project.hasProperty("legacyFeedUrl")) "true" else "false"
        versionName = rootProject.file("VERSION").readText().trim()
    }
    androidResources { noCompress += "wav" }
    buildFeatures { buildConfig = true }
    signingConfigs {
        create("pilot") {
            val keyPath = System.getenv("ORANGE_KEYSTORE")
            if (!keyPath.isNullOrBlank()) {
                storeFile = file(keyPath)
                storePassword = System.getenv("ORANGE_STORE_PASSWORD")
                keyAlias = System.getenv("ORANGE_KEY_ALIAS")
                keyPassword = System.getenv("ORANGE_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (!System.getenv("ORANGE_KEYSTORE").isNullOrBlank()) signingConfig = signingConfigs.getByName("pilot")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.livekit:livekit-android:2.29.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.android.tools.build:apksig:8.2.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    doFirst {
        classpath = files(classpath.files.filter { !it.path.endsWith("android.jar") })
    }
}
