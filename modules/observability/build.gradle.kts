plugins {
    `java-library`
}

description = "Privacy-safe W3C trace context and operational telemetry conventions"

dependencies {
    api("io.opentelemetry:opentelemetry-api:1.66.0")
    implementation("org.slf4j:slf4j-api:2.0.20")
    testImplementation("io.opentelemetry:opentelemetry-sdk:1.66.0")
}
