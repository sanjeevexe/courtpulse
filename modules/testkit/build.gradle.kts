plugins {
    `java-library`
}

description = "Redistributable synthetic fixtures and milestone test helpers"

dependencies {
    api(project(":modules:domain"))
    api(project(":modules:providers"))
}
