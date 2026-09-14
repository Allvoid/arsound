dependencies {
    compileOnly(project(":extensions:shared:library"))
    compileOnly(project(":extensions:soundcloud:stub"))
    compileOnly(libs.annotation)
}

android {
    defaultConfig {
        minSdk = 24
    }
}
