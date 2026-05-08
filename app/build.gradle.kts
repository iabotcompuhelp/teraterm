plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.javafx)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.opentermx.app.MainKt")
}

tasks.named<JavaExec>("run") {
    jvmArgs("-Dprism.order=sw")
}

javafx {
    version = "21.0.5"
    modules = listOf("javafx.controls", "javafx.fxml", "javafx.graphics", "javafx.swing")
}

dependencies {
    implementation(project(":common"))
    implementation(project(":serial-comm"))
    implementation(project(":ssh-comm"))
    implementation(project(":telnet-comm"))
    implementation(project(":file-transfer"))
    implementation(project(":tftp-service"))
    implementation(project(":macro-engine"))
    implementation(project(":logger"))

    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.javafx)
    implementation(libs.jsch)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.richtextfx)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)

    testImplementation(libs.junit.jupiter)
}