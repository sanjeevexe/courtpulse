plugins {
    application
}

description = "Command-line deterministic replay demonstration"

dependencies {
    implementation(project(":modules:domain"))
    implementation(project(":modules:providers"))
    implementation(project(":modules:testkit"))
}

application {
    mainClass.set("com.courtpulse.replaycli.ReplayCli")
}
