plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.understory.keyboard.plugin.sample"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.understory.keyboard.plugin.sample"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        base.archivesName = "kotoba-plugin-sample"
    }

    buildTypes {
        debug {
            isDebuggable = false
            isJniDebuggable = false
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

    lint {
        lintConfig = file("../lint.xml")
        abortOnError = true
        checkReleaseBuilds = true
    }
}

dependencies {
    implementation(project(":keyboard-plugin-api"))

    testImplementation("junit:junit:4.13.2")
}
