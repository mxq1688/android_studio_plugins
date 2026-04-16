import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.0"
    id("org.jetbrains.intellij.platform") version "2.9.0"
}

group = "com.serialport"
version = "1.0.2"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

val localAndroidStudioPath =
    providers.gradleProperty("androidStudioPath")
        .orElse("D:/Program Files/Android/Android Studio1")

dependencies {
    implementation("com.fazecast:jSerialComm:2.11.4")
    intellijPlatform {
        local(localAndroidStudioPath)
        bundledPlugin("org.jetbrains.android")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild.set("241")
        }
    }
    signing {
        certificateChain = System.getenv("CERTIFICATE_CHAIN")
        privateKey = System.getenv("PRIVATE_KEY")
        password = System.getenv("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = System.getenv("PUBLISH_TOKEN")
    }
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

}
