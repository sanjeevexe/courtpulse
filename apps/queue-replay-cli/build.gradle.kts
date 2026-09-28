plugins {
    application
    id("org.springframework.boot") version "4.1.1"
}

description = "Bounded PostgreSQL outbox to SQS FIFO replay demonstration"

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    implementation(platform("software.amazon.awssdk:bom:2.55.1"))
    implementation(project(":modules:messaging"))
    implementation(project(":modules:observability"))
    implementation(project(":modules:testkit"))

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("software.amazon.awssdk:sqs")
    implementation("software.amazon.awssdk:url-connection-client")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

application {
    mainClass.set("com.courtpulse.queuereplay.QueueReplayApplication")
}
