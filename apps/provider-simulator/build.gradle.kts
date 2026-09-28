plugins {
    application
}

description = "Local-only BALLDONTLIE-shaped provider simulator for replay, fault, and correction drills"

dependencies {
    implementation(project(":modules:testkit"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.19.2")
}

application {
    mainClass.set("com.courtpulse.providersimulator.ProviderSimulator")
}

dependencies {
    testImplementation(project(":modules:providers"))
}
