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
    implementation(libs.jserialcomm)
    // JNA está en `api` porque NativeCell/NativeCursor extienden com.sun.jna.Structure y
    // forman parte de la API pública del módulo (el app las consume al integrar NativeTerminal).
    api(libs.jna)
    implementation(libs.jna.platform)
    implementation(libs.slf4j.api)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockito.core)
}
