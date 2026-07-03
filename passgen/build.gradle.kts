plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.understory.passgen"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.understory.passgen"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "1.0-alpha"
        // resourceConfigurations lock dropped (shared-gui §S-3): it hard-locks
        // the app to English and blocks future localization. Only `en` strings
        // exist today, so removing it is pure gain and unblocks translation.
        base.archivesName = "passgen"
    }

    buildTypes {
        debug {
            // Even though this is the debug variant, we ship a sideload APK that
            // must not be attachable by `adb shell run-as` or jdwp. Anyone with
            // USB debugging access could otherwise dump memory.
            isDebuggable = false
            isJniDebuggable = false
            isPseudoLocalesEnabled = false
            isMinifyEnabled = false
            isShrinkResources = false
        }
        release {
            isDebuggable = false
            isJniDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // Generates BuildConfig (incl. the FLAVOR field from the prod/eng
        // product flavors) so the shipping UI can gate the Diagnostics /
        // dev surface to eng builds only (BuildConfig.FLAVOR == "eng").
        // Matches the antivirus / backups / firewall modules' convention.
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        lintConfig = file("../lint.xml")
        abortOnError = true
        checkReleaseBuilds = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    // Engineering builds: same code as prod, but with a distinct
    // applicationId so eng + prod can be installed side-by-side, and
    // a runtime trigger (.eng package suffix) that activates
    // DiagnosticsDump's rolling-log writer in shared storage.
    //
    //   :passgen:assembleEngRelease   → com.understory.passgen.eng
    //   :passgen:assembleProdRelease  → com.understory.passgen
    //
    // No eng-only source set yet — the activation is a runtime check
    // on package name suffix, which means there's no risk of an eng-
    // only path leaking into prod via a flavor source-set merge.
    flavorDimensions += "channel"
    productFlavors {
        create("prod") {
            dimension = "channel"
        }
        create("eng") {
            dimension = "channel"
            applicationIdSuffix = ".eng"
            versionNameSuffix = "-eng"
        }
    }
}

dependencies {
    implementation(project(":common-security"))
    implementation(project(":common-backup"))

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // BouncyCastle: Argon2id KDF (not in JCE) and ML-KEM (post-quantum,
    // staged for Phase 2). Pure-Java, no native deps. We use the prov-jdk18on
    // module only — no PGP/CMS/TLS extras.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // BiometricPrompt with DEVICE_CREDENTIAL fallback. Daily vault unlock
    // uses this — no typed master password.
    implementation("androidx.biometric:biometric:1.2.0-alpha05")
    // Override the fragment 1.2.5 transitively pinned by biometric:1.2.0-
    // alpha05. 1.2.5 has the legacy 16-bit requestCode check on
    // FragmentActivity.startActivityForResult, which throws "Can only use
    // lower 16 bits for requestCode" when rememberLauncherForActivityResult
    // fires (the AndroidX activity-result registry generates codes
    // ≥ 0x10000). Fragment 1.6+ removed the check on the registry path.
    // User-facing symptom on 1.2.5: tap "Pick file" on the import screen
    // → no SAF picker opens, error banner appears with the IllegalArgument-
    // Exception text. Reproduced on Xiaomi MIUI (HyperOS) device.
    implementation("androidx.fragment:fragment-ktx:1.8.5")

    // JUnit + Robolectric. Robolectric is needed for tests that touch
    // android.util.Base64 (BackupFormat), Context-backed SharedPreferences
    // (Settings), or org.json (VaultEntry); pure-JVM tests don't need it.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
}

