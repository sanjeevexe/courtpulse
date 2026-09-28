plugins {
    `java-library`
}

description = "Privacy-safe W3C trace context and operational telemetry conventions"

dependencies {
    api("io.opentelemetry:opentelemetry-api:1.62.0")
    implementation("org.slf4j:slf4j-api:2.0.17")
    testImplementation("io.opentelemetry:opentelemetry-sdk:1.62.0")
}
