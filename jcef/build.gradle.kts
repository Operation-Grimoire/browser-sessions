plugins { kotlin("jvm"); `java-library`; `maven-publish` }
kotlin { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
java { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11; withSourcesJar() }
dependencies {
    api(project(":core"))
    api("me.friwi:jcefmaven:152.0.6")
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}
tasks.test { useJUnitPlatform() }
publishing { publications { create<MavenPublication>("library") { from(components["java"]); artifactId = "browser-sessions-jcef" } } }
