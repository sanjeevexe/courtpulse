plugins {
    application
    id("org.springframework.boot") version "4.1.1"
}

description = "Spring Boot command-line durable PostgreSQL replay demonstration"

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    implementation(project(":modules:domain"))
    implementation(project(":modules:providers"))
    implementation(project(":modules:persistence"))
    implementation(project(":modules:testkit"))

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(enforcedPlatform("io.zonky.test.postgres:embedded-postgres-binaries-bom:18.6.0"))
    testImplementation("io.zonky.test:embedded-postgres:2.2.2")
    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
}

application {
    mainClass.set("com.courtpulse.durablereplay.DurableReplayApplication")
}
