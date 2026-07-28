import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.jaredsburrows.license")
}

// --- limm VPN: secrets injected via gitignored limm.properties (UUID/token never in git) ---
val limmProps = Properties().apply {
    val f = rootProject.file("limm.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun limm(key: String, default: String = ""): String = limmProps.getProperty(key) ?: default

// CI injects full github SHA via limm.properties so app hash matches the footer on limm.space/stat.
// Locally uses full git SHA (40 chars) so last-4 display matches apk-info.json filename.
val limmBuildHash: String = limm("LIMM_BUILD_SHA").ifEmpty {
    try {
        val p = ProcessBuilder("git", "rev-parse", "HEAD")
            .directory(rootProject.rootDir)
            .redirectErrorStream(true)
            .start()
        p.inputStream.bufferedReader().readText().trim().ifEmpty { "nogit" }
    } catch (e: Exception) {
        "nogit"
    }
}

android {
    namespace = "com.v2ray.ang"
    compileSdk = 37

    defaultConfig {
        applicationId = "space.limm.vpn"
        minSdk = 24
        targetSdk = 37
        versionCode = 817
        versionName = "2.2.3.93"
        multiDexEnabled = true

        // limm VPN config — non-secret REALITY params baked, secrets from limm.properties
        buildConfigField("String", "LIMM_VLESS_UUID", "\"${limm("LIMM_VLESS_UUID")}\"")
        buildConfigField("String", "LIMM_TOKEN", "\"${limm("LIMM_TOKEN")}\"")
        buildConfigField("String", "LIMM_SERVER_IP", "\"77.90.52.123\"")
        buildConfigField("String", "LIMM_SERVER_PORT", "\"443\"")
        buildConfigField("String", "LIMM_SERVER_NAME", "\"de1-skrime\"")
        buildConfigField("String", "LIMM_LABEL", "\"${limm("LIMM_LABEL")}\"")
        buildConfigField("String", "LIMM_REALITY_PBK", "\"znXvmpAIvstY45kp0ERqf6zweh_wWZyiN8tg90dFTWc\"")
        buildConfigField("String", "LIMM_REALITY_SNI", "\"www.microsoft.com\"")
        buildConfigField("String", "LIMM_REALITY_SID", "\"${limm("LIMM_REALITY_SID")}\"")
        buildConfigField("String", "LIMM_REALITY_FLOW", "\"xtls-rprx-vision\"")
        buildConfigField("String", "LIMM_REALITY_FP", "\"chrome\"")
        buildConfigField("String", "LIMM_COLLECTOR_URL", "\"https://limm.space\"")
        buildConfigField("String", "LIMM_BUILD", "\"${limmBuildHash}\"")

        val abiFilterList = (properties["ABI_FILTERS"] as? String)?.split(';')
        splits {
            abi {
                isEnable = true
                reset()
                if (abiFilterList != null && abiFilterList.isNotEmpty()) {
                    include(*abiFilterList.toTypedArray())
                } else {
                    include(
                        "arm64-v8a",
                        "armeabi-v7a",
                        "x86_64",
                        "x86"
                    )
                }
                isUniversalApk = abiFilterList.isNullOrEmpty()
            }
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("limmRelease") {
            val ksPath = limm("LIMM_KEYSTORE_PATH")
            val ksFile = if (ksPath.isNotEmpty()) rootProject.file(ksPath) else null
            if (ksFile != null && ksFile.exists()) {
                storeFile = ksFile
                storePassword = limm("LIMM_KEYSTORE_PASSWORD")
                keyAlias = limm("LIMM_KEY_ALIAS")
                keyPassword = limm("LIMM_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val limmReleaseCfg = signingConfigs.findByName("limmRelease")
            if (limmReleaseCfg?.storeFile != null) {
                signingConfig = limmReleaseCfg
            }
        }
    }

    flavorDimensions.add("distribution")
    productFlavors {
        create("fdroid") {
            dimension = "distribution"
            applicationIdSuffix = ".fdroid"
            buildConfigField("String", "DISTRIBUTION", "\"F-Droid\"")
        }
        create("playstore") {
            dimension = "distribution"
            buildConfigField("String", "DISTRIBUTION", "\"Play Store\"")
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("libs")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    applicationVariants.all {
        val variant = this
        val isFdroid = variant.productFlavors.any { it.name == "fdroid" }
        if (isFdroid) {
            val versionCodes =
                mapOf(
                    "armeabi-v7a" to 2, "arm64-v8a" to 1, "x86" to 4, "x86_64" to 3, "universal" to 0
                )

            variant.outputs
                .map { it as com.android.build.gradle.internal.api.ApkVariantOutputImpl }
                .forEach { output ->
                    val abi = output.getFilter("ABI") ?: "universal"
                    output.outputFileName = "v2rayNG_${variant.versionName}-fdroid_${abi}.apk"
                    if (versionCodes.containsKey(abi)) {
                        output.versionCodeOverride =
                            (100 * variant.versionCode + versionCodes[abi]!!).plus(5000000)
                    } else {
                        return@forEach
                    }
                }
        } else {
            val versionCodes =
                mapOf("armeabi-v7a" to 4, "arm64-v8a" to 4, "x86" to 4, "x86_64" to 4, "universal" to 4)

            variant.outputs
                .map { it as com.android.build.gradle.internal.api.ApkVariantOutputImpl }
                .forEach { output ->
                    val abi = if (output.getFilter("ABI") != null)
                        output.getFilter("ABI")
                    else
                        "universal"

                    output.outputFileName = "v2rayNG_${variant.versionName}_${abi}.apk"
                    if (versionCodes.containsKey(abi)) {
                        output.versionCodeOverride =
                            (1000000 * versionCodes[abi]!!).plus(variant.versionCode)
                    } else {
                        return@forEach
                    }
                }
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

}

dependencies {
    // Core Libraries
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    // AndroidX Core Libraries
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.preference.ktx)
    implementation(libs.recyclerview)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.fragment)

    // UI Libraries
    implementation(libs.material)
    implementation(libs.toasty)
    implementation(libs.editorkit)
    implementation(libs.flexbox)

    // Data and Storage Libraries
    implementation(libs.mmkv.static)
    implementation(libs.gson)
    implementation(libs.okhttp)

    // Reactive and Utility Libraries
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // Language and Processing Libraries
    implementation(libs.language.base)
    implementation(libs.language.json)

    // Intent and Utility Libraries
    implementation(libs.quickie.foss)
    implementation(libs.core)

    // AndroidX Lifecycle and Architecture Components
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.livedata.ktx)
    implementation(libs.lifecycle.runtime.ktx)

    // Background Task Libraries
    implementation(libs.work.runtime.ktx)
    implementation(libs.work.multiprocess)

    // Multidex Support
    implementation(libs.multidex)

    // Testing Libraries
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    testImplementation(libs.org.mockito.mockito.inline)
    testImplementation(libs.mockito.kotlin)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
