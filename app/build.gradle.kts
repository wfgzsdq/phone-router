import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseProperties = Properties().apply {
    val config = rootProject.file("keystore.properties")
    if (config.isFile) config.inputStream().use { load(it) }
}

android {
    namespace = "com.phonerouter.hotspot"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.phonerouter.hotspot"
        minSdk = 29
        targetSdk = 34
        versionCode = 3
        versionName = "1.1.1"
    }

    buildTypes {
        release {
            if (releaseProperties.isNotEmpty()) {
                signingConfig = signingConfigs.create("release") {
                    storeFile = rootProject.file(releaseProperties.getProperty("storeFile"))
                    storePassword = releaseProperties.getProperty("storePassword")
                    keyAlias = releaseProperties.getProperty("keyAlias")
                    keyPassword = releaseProperties.getProperty("keyPassword")
                }
            }
            isMinifyEnabled = false
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
        viewBinding = true
    }
}

// Generate the privileged dex jar from source using the app's SDK and JDK.
val shimClasses = layout.buildDirectory.dir("shim/classes")
val shimAssets = layout.buildDirectory.dir("generated/shimAssets")
val sdkRoot = android.sdkDirectory
val androidJar = file("$sdkRoot/platforms/android-34/android.jar")
val compileShim by tasks.registering(JavaCompile::class) {
    source(rootProject.fileTree("shim/src/main/java") { include("**/*.java") })
    classpath = files(androidJar)
    destinationDirectory.set(shimClasses)
    sourceCompatibility = "17"
    targetCompatibility = "17"
    options.encoding = "UTF-8"
}
val buildShim by tasks.registering(JavaExec::class) {
    dependsOn(compileShim)
    classpath = files("$sdkRoot/build-tools/34.0.0/lib/d8.jar")
    mainClass.set("com.android.tools.r8.D8")
    inputs.dir(shimClasses)
    inputs.file(androidJar)
    outputs.dir(shimAssets)
    doFirst {
        val outputDir = shimAssets.get().asFile.apply { mkdirs() }
        setArgs(listOf("--min-api", "29", "--lib", androidJar.path,
            "--output", outputDir.resolve("shim.jar").path) +
            fileTree(shimClasses.get().asFile).matching { include("**/*.class") }
                .files.sortedBy { it.path }.map { it.path })
    }
}
android.sourceSets.getByName("main").assets.srcDir(shimAssets)
tasks.named("preBuild") { dependsOn(buildShim) }

dependencies {
    // Provided by LSPosed in the hooked process; never package the API stubs.
    compileOnly("de.robv.android.xposed:api:82")
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
