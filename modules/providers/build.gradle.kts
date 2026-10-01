plugins {
    `java-library`
}

description = "Provider-neutral conversion boundary and synthetic fixture loader"

dependencies {
    api(project(":modules:domain"))
    api("com.fasterxml.jackson.core:jackson-databind:2.22.3")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.22.3")

    testImplementation(project(":modules:testkit"))
}
