plugins { kotlin("jvm"); application }
kotlin { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
java { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
dependencies { implementation(project(":jcef")); implementation("com.google.code.gson:gson:2.11.0") }
application { mainClass.set("io.grimoire.sessions.example.MainKt") }
val browserTest = sourceSets.create("browserTest")
browserTest.compileClasspath += sourceSets.main.get().output
browserTest.runtimeClasspath += sourceSets.main.get().output
configurations[browserTest.implementationConfigurationName].extendsFrom(configurations.implementation.get())
dependencies { add(browserTest.implementationConfigurationName, "com.squareup.okhttp3:mockwebserver:4.12.0") }
kotlin.sourceSets.getByName("browserTest").kotlin.srcDir("src/browserTest/kotlin")
tasks.register<JavaExec>("browserTest") {
    group = "verification"
    description = "Run native embedded JCEF cookie tests in a separate browser process"
    classpath = browserTest.runtimeClasspath + sourceSets.main.get().output
    mainClass.set("io.grimoire.sessions.example.BrowserSmokeMainKt")
    workingDir = projectDir
    args(providers.gradleProperty("browserPhase").orElse("roundtrip").get())
}
