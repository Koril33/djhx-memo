plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.gitlab.arturbosch.detekt")
}
android {
    namespace = "app.djhx.memo"
    compileSdk = 36
    ndkVersion = "30.0.16248370"
    defaultConfig {
        applicationId = "app.djhx.memo"
        manifestPlaceholders["appLabel"] = "DJHX Memo"
        minSdk = 29
        targetSdk = 36
        // Only isolated device fixtures can override identity for a real upgrade preflight.
        versionCode = if (project.hasProperty("isolatedValidation")) providers.gradleProperty("updateTestVersionCode").orNull?.toInt() ?: 6 else 6
        versionName = if (project.hasProperty("isolatedValidation")) providers.gradleProperty("updateTestVersionName").orNull ?: "0.1.5" else "0.1.5"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        providers.environmentVariable("DJHX_ANDROID_TEST_SERVER_URL").orNull?.let {
            testInstrumentationRunnerArguments["serverUrl"] = it
        }
        providers.environmentVariable("DJHX_ANDROID_TEST_INVITE_CODE").orNull?.let {
            testInstrumentationRunnerArguments["inviteCode"] = it
        }
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    buildFeatures { compose = true; buildConfig = true }
    bundle { language { enableSplit = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs {
        create("distribution") {
            val store = System.getenv("DJHX_ANDROID_KEYSTORE")
            if (!store.isNullOrBlank()) {
                storeFile = file(store)
                storePassword = System.getenv("DJHX_ANDROID_STORE_PASSWORD")
                keyAlias = System.getenv("DJHX_ANDROID_KEY_ALIAS") ?: "djhx-memo"
                keyPassword = System.getenv("DJHX_ANDROID_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        debug {
            // Optional isolated device validation never opens an existing debug/release data directory.
            applicationIdSuffix = if (project.hasProperty("isolatedValidation")) ".validation014" else ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["appLabel"] = "DJHX Memo Debug"
        }
        release {
            isMinifyEnabled = false
            if (!System.getenv("DJHX_ANDROID_KEYSTORE").isNullOrBlank()) signingConfig = signingConfigs.getByName("distribution")
        }
    }
    sourceSets["main"].java.srcDir("build/generated/uniffi")
    packaging { resources.excludes += listOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/LICENSE*", "META-INF/NOTICE*") }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencyLocking { lockAllConfigurations() }
dependencies {
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui:1.7.5")
    implementation("androidx.compose.foundation:foundation:1.7.5")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.5")
    implementation("androidx.compose.material3:material3:1.3.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("net.java.dev.jna:jna:5.17.0@aar")
    implementation("org.commonmark:commonmark:0.24.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.8")
}
detekt { autoCorrect = project.hasProperty("format"); buildUponDefaultConfig = true; config.setFrom(files("../config/detekt.yml")); source.setFrom("src/main/java", "src/test/java", "src/androidTest/java") }
tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach { exclude("**/build/**") }

val prepareNotices by tasks.registering(Copy::class) {
    from("../../LICENSE") { rename { "DJHX_MEMO_LICENSE.txt" } }
    from("../../core/THIRD_PARTY_NOTICES.txt") { rename { "CORE_THIRD_PARTY_NOTICES.txt" } }
    from("../../core/licenses/two-face-syntaxes.txt") { rename { "CODE_SYNTAX_NOTICES.txt" } }
    from("../THIRD_PARTY_NOTICES.txt") { rename { "ANDROID_THIRD_PARTY_NOTICES.txt" } }
    into(layout.buildDirectory.dir("generated/notices"))
}
android.sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/notices"))
tasks.named("preBuild") { dependsOn(prepareNotices) }
