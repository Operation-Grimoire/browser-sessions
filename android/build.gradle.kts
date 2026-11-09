plugins { id("com.android.library"); `maven-publish` }
android {
    namespace = "io.grimoire.sessions.android"
    compileSdk = 36
    defaultConfig { minSdk = 26; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    publishing { singleVariant("release") { withSourcesJar() } }
}
dependencies {
    api(project(":core"))
    implementation("androidx.webkit:webkit:1.14.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
afterEvaluate { publishing { publications { create<MavenPublication>("library") { from(components["release"]); artifactId = "browser-sessions-android" } } } }
