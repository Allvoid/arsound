dependencies {
    compileOnly(project(":extensions:arsound-shared:library"))
    compileOnly(project(":extensions:arsound:stub"))
    compileOnly(libs.annotation)
}

android {
    defaultConfig {
        minSdk = 24
    }
}
