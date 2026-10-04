group = "app.arsound"

patches {
    about {
        name = "Arsound"
        description = "SoundCloud patches based on ReVanced Patches"
        source = "git@github.com:Allvoid/arsound.git"
        author = "Allvoid"
        contact = "https://github.com/Allvoid/arsound/issues"
        website = "https://github.com/Allvoid/arsound"
        license = "GNU General Public License v3.0"
    }
}

dependencies {
    // Required due to smali, or build fails. Can be removed once smali is bumped.
    implementation(libs.guava)

    implementation(libs.apksig)

    // Android API stubs defined here.
    compileOnly(project(":patches:stub"))
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xexplicit-backing-fields",
            "-Xcontext-parameters"
        )
    }
}

apply(from = "strings-processing.gradle.kts")

// The theme patch cannot list the files inside its own jar, so the build writes an index of the themes folder:
// every file of src/main/resources/soundcloud/theme, one relative path per line.
val themeSources: File = file("src/main/resources/soundcloud/theme")
val themeIndexDir: File = layout.buildDirectory.dir("generated/themeIndex").get().asFile
val generateThemeIndex = tasks.register("generateThemeIndex") {
    inputs.dir(themeSources)
    outputs.dir(themeIndexDir)
    doLast {
        val index = themeIndexDir.resolve("soundcloud/theme/index.txt")
        index.parentFile.mkdirs()
        index.writeText(
            themeSources.walkTopDown().filter { it.isFile }
                .map { it.relativeTo(themeSources).invariantSeparatorsPath }.sorted().joinToString("\n"),
        )
    }
}
sourceSets["main"].resources.srcDir(themeIndexDir)
tasks.named("processResources") { dependsOn(generateThemeIndex) }
