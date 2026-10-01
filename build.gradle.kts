import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.tasks.Jar

plugins {
    base
}

group = "com.courtpulse"
version = "0.1.0-SNAPSHOT"

allprojects {
    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java-library")

    group = rootProject.group
    version = rootProject.version

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    dependencies {
        // Security floor for Jackson (CVE-2026-68497, CVE-2026-91776, CVE-2026-91777): newer than
        // the versions in Spring Boot 4.1.1's dependency set. Remove once Boot ships them.
        "implementation"(platform("com.fasterxml.jackson:jackson-bom:2.22.3"))
        "implementation"(platform("tools.jackson:jackson-bom:3.1.7"))
        "testImplementation"("org.junit.jupiter:junit-jupiter:5.12.2")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher:1.12.2")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-Xlint:all")
    }

    tasks.withType<Jar>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}

tasks.named("build") {
    dependsOn(subprojects.map { "${it.path}:build" })
}

tasks.register("test") {
    group = "verification"
    description = "Runs all test suites."
    dependsOn(subprojects.map { "${it.path}:test" })
}
