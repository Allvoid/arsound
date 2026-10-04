import java.util.Properties

dependencies {
    compileOnly(project(":extensions:arsound-shared:library"))
    compileOnly(project(":extensions:arsound:stub"))
    compileOnly(libs.annotation)
    implementation(project(":extensions:arsound:newpipe", configuration = "shadowRuntimeElements"))
}

android {
    defaultConfig {
        minSdk = 24
    }
}

// The Last.fm API key for the "For you" recommendations: local/lastfm.properties (api_key=...) or the
// ARSOUND_LASTFM_API_KEY environment variable. Without one the recommendations say that no key is set.
val lastFmApiKey: String = rootProject.file("local/lastfm.properties").let { file ->
    if (!file.isFile) return@let null
    Properties().apply { file.inputStream().use { load(it) } }.getProperty("api_key")
} ?: System.getenv("ARSOUND_LASTFM_API_KEY") ?: ""
val lastFmKeySources: File = layout.buildDirectory.dir("generated/lastfm").get().asFile
val generateLastFmKey = tasks.register("generateLastFmKey") {
    inputs.property("key", lastFmApiKey)
    outputs.dir(lastFmKeySources)
    doLast {
        val file = lastFmKeySources.resolve("app/revanced/extension/soundcloud/recommendations/LastFmKey.java")
        file.parentFile.mkdirs()
        file.writeText(
            "package app.revanced.extension.soundcloud.recommendations;\n\n" +
                "final class LastFmKey {\n    static final String API_KEY = \"$lastFmApiKey\";\n\n" +
                "    private LastFmKey() {\n    }\n}\n",
        )
    }
}
android.sourceSets["main"].java.srcDir(lastFmKeySources)
tasks.named("preBuild") { dependsOn(generateLastFmKey) }
