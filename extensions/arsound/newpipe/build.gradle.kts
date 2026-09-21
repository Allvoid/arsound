import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

// NewPipeExtractor with every dependency moved into its own package.
// SoundCloud already ships com.google.protobuf, so an unmoved copy would clash with it.
plugins {
    kotlin("jvm")
    alias(libs.plugins.shadow)
}

repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

val shade: Configuration by configurations.creating {
    configurations.getByName("compileClasspath").extendsFrom(this)
    configurations.getByName("runtimeClasspath").extendsFrom(this)
}

dependencies {
    shade(libs.newpipe.extractor)
}

val shadowJar = tasks.named<ShadowJar>("shadowJar") {
    configurations = listOf(shade)
    val prefix = "app.arsound.shaded"
    relocate("org.schabi.newpipe", "$prefix.newpipe")
    relocate("com.grack.nanojson", "$prefix.nanojson")
    relocate("org.jsoup", "$prefix.jsoup")
    relocate("org.mozilla", "$prefix.mozilla")
    relocate("com.google.protobuf", "$prefix.protobuf")
    relocate("javax.annotation", "$prefix.javax.annotation")
    exclude("META-INF/**", "**/*.proto", "core/**")
}

configurations.named("runtimeElements") {
    isCanBeConsumed = true
    isCanBeResolved = false

    outgoing.artifacts.clear()
    outgoing.artifact(shadowJar)
}!!.let { artifacts { add(it.name, shadowJar) } }
