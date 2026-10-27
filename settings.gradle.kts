// Set GitHub Packages credentials before any plugin is loaded
if (System.getenv("GITHUB_PACKAGES_USERNAME") != null) {
    System.setProperty("githubPackagesUsername", System.getenv("GITHUB_PACKAGES_USERNAME"))
}
if (System.getenv("GITHUB_PACKAGES_PASSWORD") != null) {
    System.setProperty("githubPackagesPassword", System.getenv("GITHUB_PACKAGES_PASSWORD"))
}

rootProject.name = "arsound"

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        maven {
            name = "githubPackages"
            url = uri("https://maven.pkg.github.com/revanced/revanced-patches-gradle-plugin")
            credentials(PasswordCredentials::class) {
                username = System.getProperty("githubPackagesUsername", System.getenv("GITHUB_PACKAGES_USERNAME") ?: "x-token-auth")
                password = System.getProperty("githubPackagesPassword", System.getenv("GITHUB_PACKAGES_PASSWORD") ?: "")
            }
        }
        // TODO: Remove once https://github.com/google/protobuf-gradle-plugin/pull/797 is merged.
        maven { url = uri("https://jitpack.io") }
    }
    // TODO: Remove once https://github.com/google/protobuf-gradle-plugin/pull/797 is merged.
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.google.protobuf") {
                useModule("com.github.ReVanced:protobuf-gradle-plugin:${requested.version}")
            }
        }
    }
}

plugins {
    id("app.revanced.patches") version "1.0.0-dev.10"
}

// Gradle properties for GitHub Packages (can be set in gradle.properties or environment)
if (System.getenv("GITHUB_PACKAGES_USERNAME") != null && System.getenv("GITHUB_PACKAGES_PASSWORD") != null) {
    System.setProperty("githubPackagesUsername", System.getenv("GITHUB_PACKAGES_USERNAME"))
    System.setProperty("githubPackagesPassword", System.getenv("GITHUB_PACKAGES_PASSWORD"))
} else {
    // Default values if not set - will use token fallback
    System.setProperty("githubPackagesUsername", System.getenv("GITHUB_TOKEN") ?: "x-token-auth")
    System.setProperty("githubPackagesPassword", System.getenv("GITHUB_TOKEN") ?: "")
}

settings {
    extensions {
        defaultNamespace = "app.revanced.extension"

        // Must resolve to an absolute path (not relative),
        // otherwise the extensions in subfolders will fail to find the proguard config.
        proguardFiles(rootProject.projectDir.resolve("extensions/proguard-rules.pro").toString())
    }
}

include(":patches:stub")