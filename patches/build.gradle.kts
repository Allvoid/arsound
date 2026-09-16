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
