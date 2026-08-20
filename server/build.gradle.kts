plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.opentermx.server.MainKt")
    applicationName = "opentermx-server"
}

dependencies {
    implementation(project(":mcp-server"))
    implementation(project(":ai-assistant"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.junit.jupiter)
}

tasks.test {
    useJUnitPlatform()
}
