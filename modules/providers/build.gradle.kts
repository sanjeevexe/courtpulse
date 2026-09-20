plugins {
    `java-library`
}

description = "Provider-neutral conversion boundary and synthetic fixture loader"

dependencies {
    api(project(":modules:domain"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.19.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.19.2")
}
