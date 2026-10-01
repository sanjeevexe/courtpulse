plugins {
    `java-library`
}

description = "Broker-independent queue workflow and AWS SDK v2 SQS adapter"

dependencies {
    api(project(":modules:persistence"))
    implementation(project(":modules:observability"))

    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    implementation(platform("software.amazon.awssdk:bom:2.55.6"))
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("org.slf4j:slf4j-api")
    implementation("org.springframework:spring-tx")
    implementation("software.amazon.awssdk:sqs")
    implementation("software.amazon.awssdk:ses")
    implementation("software.amazon.awssdk:url-connection-client")

    testImplementation(project(":modules:testkit"))
    testImplementation("org.springframework:spring-jdbc")
    testImplementation("org.springframework:spring-tx")
    testImplementation("org.postgresql:postgresql")
    testImplementation("org.flywaydb:flyway-core")
    testImplementation("org.flywaydb:flyway-database-postgresql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.5")
    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
    testImplementation("org.testcontainers:testcontainers-localstack:2.0.5")
    testImplementation("org.mockito:mockito-core")
}
