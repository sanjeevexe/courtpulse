plugins {
    `java-library`
}

description = "Read models, keyset pagination, and PostgreSQL query services"

dependencies {
    api(project(":modules:domain"))

    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    implementation("org.springframework:spring-jdbc")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
}
