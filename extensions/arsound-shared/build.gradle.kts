dependencies {
    implementation(project(":extensions:arsound-shared:library"))
    compileOnly(libs.okhttp)
}

android {
    defaultConfig {
        minSdk = 23
    }
}
