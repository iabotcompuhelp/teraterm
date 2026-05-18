plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

allprojects {
    group = "com.opentermx"
    version = "1.1.0"

    repositories {
        mavenCentral()
    }
}

subprojects {
    tasks.withType<JavaCompile>().configureEach {
        sourceCompatibility = "21"
        targetCompatibility = "21"
        options.encoding = "UTF-8"
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    // Gradle 9 no longer provides the JUnit Platform launcher transitively;
    // every Java/Kotlin module that runs JUnit 5 tests needs it on the test
    // runtime classpath explicitly.
    plugins.withId("java") {
        dependencies {
            "testRuntimeOnly"(rootProject.libs.junit.platform.launcher)
        }
    }
}