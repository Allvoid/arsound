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
