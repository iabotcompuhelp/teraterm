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
    implementation(project(":edge-agent"))
    implementation(project(":net-parsers"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.slf4j.api)
    implementation(libs.javalin)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.core)
}

tasks.test {
    useJUnitPlatform()
}
