import org.gradle.api.GradleException
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}

fun signingValue(propertyKey: String, envKey: String): String? {
    val value = keystoreProperties.getProperty(propertyKey) ?: System.getenv(envKey)
    return value?.trim()?.takeIf { it.isNotEmpty() }
}

val releaseStoreFile = signingValue("storeFile", "CLIPBOARD_STORE_FILE")
val releaseStorePassword = signingValue("storePassword", "CLIPBOARD_STORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "CLIPBOARD_KEY_ALIAS")
    ?: signingValue("keyAlias", "ANDROID_KEYSTORE_ALIAS")
val releaseKeyPassword = releaseStorePassword

val releaseSigningValues = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
)

val releaseSigningConfigured = releaseSigningValues.all { it != null }
val releaseSigningPartiallyConfigured = releaseSigningValues.any { it != null } && !releaseSigningConfigured

if (releaseSigningPartiallyConfigured) {
    throw GradleException(
        "Incomplete Android release signing configuration. " +
        "Provide all values in android/keystore.properties (storeFile, storePassword, keyAlias) " +
        "or via CLIPBOARD_STORE_FILE, CLIPBOARD_STORE_PASSWORD, CLIPBOARD_KEY_ALIAS."
    )
}

android {
    namespace = "net.wastu.clipboard"
    compileSdk = 36

    defaultConfig {
        applicationId = "net.wastu.binderclip"
        minSdk = 31
        targetSdk = 36
        versionCode = (System.getenv("VERSION_CODE") ?: "7").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "0.0.3"

        val gitHash = providers.exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
        }.standardOutput.asText.get().trim()
        buildConfigField("String", "GIT_HASH", "\"$gitHash\"")
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword!!
                keyAlias = releaseKeyAlias!!
                keyPassword = releaseKeyPassword!!
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // Bundle native debug symbols (ML Kit .so libs from quickie) into the AAB
            // so Play Console can symbolicate native crashes/ANRs. Kills the
            // "you've not uploaded debug symbols" warning. FULL = names + line numbers.
            ndk {
                debugSymbolLevel = "FULL"
            }

            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // android.util.Log is not available on the JVM; without this flag, any unit
    // test that exercises code calling Log.* would throw a RuntimeException.
    // returnDefaultValues makes the stub methods return 0/null instead.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

}

gradle.taskGraph.whenReady {
    val releaseTaskRequested = allTasks.any { task ->
        task.project == project && task.name.contains("Release", ignoreCase = true)
    }

    if (releaseTaskRequested && !releaseSigningConfigured) {
        throw GradleException(
            "Android release signing is not configured. " +
            "Create android/keystore.properties (storeFile, storePassword, keyAlias) " +
                "or set CLIPBOARD_STORE_FILE, CLIPBOARD_STORE_PASSWORD, CLIPBOARD_KEY_ALIAS."
        )
    }

}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // Quickie: CameraX + bundled ML Kit QR scanner, no Google Play services required.
    implementation("io.github.g00fy2.quickie:quickie-bundled:1.12.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
