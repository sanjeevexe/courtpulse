plugins {
    `java-library`
}

description = "PostgreSQL repositories and durable transactional processing"

dependencies {
    api(project(":modules:domain"))
    api(project(":modules:providers"))
    implementation(project(":modules:observability"))

    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    implementation("org.springframework:spring-jdbc")
    implementation("org.springframework:spring-tx")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("io.micrometer:micrometer-core")

    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    testImplementation(project(":modules:testkit"))
    testImplementation("org.postgresql:postgresql")
    testImplementation("org.flywaydb:flyway-core")
    testImplementation("org.flywaydb:flyway-database-postgresql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.5")
    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
    testImplementation(enforcedPlatform("io.zonky.test.postgres:embedded-postgres-binaries-bom:17.11.0"))
    testImplementation("io.zonky.test:embedded-postgres:2.2.2")
}
