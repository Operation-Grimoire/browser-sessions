plugins {
    kotlin("jvm") version "2.2.10" apply false
    id("com.android.library") version "9.2.1" apply false
}
allprojects {
    group = "io.grimoire"
    version = providers.environmentVariable("RELEASE_VERSION").orElse("0.1.0-SNAPSHOT").get().removePrefix("v")
}

subprojects {
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/Operation-Grimoire/browser-sessions")
                    credentials {
                        username = System.getenv("GITHUB_ACTOR") ?: ""
                        password = System.getenv("GITHUB_TOKEN") ?: ""
                    }
                }
            }
            publications.withType<MavenPublication>().configureEach {
                pom {
                    name.set(project.name)
                    description.set("Cookie sessions shared by browsers and HTTP clients on Android and desktop JVM")
                    url.set("https://github.com/Operation-Grimoire/browser-sessions")
                    licenses { license { name.set("Apache License 2.0"); url.set("https://www.apache.org/licenses/LICENSE-2.0") } }
                    scm { url.set("https://github.com/Operation-Grimoire/browser-sessions") }
                }
            }
        }
    }
}
