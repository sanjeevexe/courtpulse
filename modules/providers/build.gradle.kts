plugins {
    `java-library`
}

description = "Provider-neutral conversion boundary and synthetic fixture loader"

dependencies {
    api(project(":modules:domain"))
    api("com.fasterxml.jackson.core:jackson-databind:2.22.1")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.22.1")

    testImplementation(project(":modules:testkit"))
}
