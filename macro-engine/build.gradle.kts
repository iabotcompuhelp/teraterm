plugins {
    groovy
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    api(project(":common"))
    implementation(libs.groovy)
    implementation(libs.slf4j.api)

    testImplementation(libs.junit.jupiter)
}