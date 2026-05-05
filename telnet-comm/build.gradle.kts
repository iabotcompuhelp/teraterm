plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    api(project(":common"))
    implementation(libs.commons.net)
    implementation(libs.slf4j.api)

    testImplementation(libs.junit.jupiter)
}