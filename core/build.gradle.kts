plugins { kotlin("jvm"); `java-library`; `maven-publish` }
kotlin { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
java { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11; withSourcesJar() }
dependencies {
    api("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(kotlin("test-junit5"))
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}
tasks.test { useJUnitPlatform() }
publishing { publications { create<MavenPublication>("library") { from(components["java"]); artifactId = "browser-sessions-core" } } }
