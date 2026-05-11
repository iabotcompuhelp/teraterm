plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

dependencies {
    api(project(":common"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.slf4j.api)
    implementation(libs.lucene.core)
    implementation(libs.lucene.analysis.common)
    implementation(libs.lucene.queryparser)
    implementation(libs.pdfbox)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.test {
    useJUnitPlatform()
}
