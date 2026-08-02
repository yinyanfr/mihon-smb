import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "SMB Library"
    versionCode = 5
    contentWarning = ContentWarning.SAFE
    libVersion = "1.4"

    source {
        lang = "all"
        baseUrl = "https://smb.library.local"
    }
}

android {
    buildTypes.named("release") {
        proguardFiles("proguard-rules.pro")
    }

    sourceSets.named("test") {
        java.directories.add("test")
        kotlin.directories.add("test")
    }
}

dependencies {
    implementation("com.hierynomus:smbj:0.14.0")
    testImplementation(libs.kotlin.stdlib)
    testImplementation(libs.junit)
}

tasks.matching { it.name == "kspDebugUnitTestKotlin" }.configureEach {
    enabled = false
}
