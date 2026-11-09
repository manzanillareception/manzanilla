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
        manifestPlaceholders["cleartext"] = if (project.hasProperty("feedUrl") || project.hasProperty("legacyFeedUrl")) "true" else "false"
        // OpenAI is always device BYOK. Cartesia bundling is the owner's explicit V1 choice.
        buildConfigField("String", "CARTESIA_API_KEY", javaString(""))
        buildConfigField("String", "CARTESIA_AGENT_ID", javaString(System.getenv("CARTESIA_AGENT_NIGHT")
            ?: project.findProperty("cartesiaAgentId") as String? ?: "agent_gKPCioSa99LcdABjCEKLX7"))
        // Public-by-design Supabase client config for hotel email/password sign-in at enrollment.
        // Empty in local/debug builds until CI plumbs the staging/production project values;
        // the enrollment UI then offers the admin-issued setup code path instead.
        buildConfigField("String", "SUPABASE_URL", javaString(System.getenv("SUPABASE_URL")
            ?: project.findProperty("supabaseUrl") as String? ?: ""))
        buildConfigField("String", "SUPABASE_ANON_KEY", javaString(System.getenv("SUPABASE_ANON_KEY")
            ?: project.findProperty("supabaseAnonKey") as String? ?: ""))
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
            val cartesiaKey = System.getenv("CARTESIA_API_KEY").orEmpty().trim()
            if (project.hasProperty("requireBundledCartesia")) {
                require(cartesiaKey.isNotBlank()) { "CARTESIA_API_KEY must be supplied by the approved release secret" }
            }
            buildConfigField("String", "CARTESIA_API_KEY", javaString(cartesiaKey))
            isMinifyEnabled = false
            if (!System.getenv("ORANGE_KEYSTORE").isNullOrBlank()) signingConfig = signingConfigs.getByName("pilot")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.android.tools.build:apksig:8.2.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    doFirst {
        classpath = files(classpath.files.filter { !it.path.endsWith("android.jar") })
    }
}
