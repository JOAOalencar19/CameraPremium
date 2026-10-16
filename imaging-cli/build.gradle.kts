plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":imaging"))
}

application {
    mainClass.set("com.camerapremium.tools.CliKt")
    applicationDefaultJvmArgs = listOf("-Xmx6g")
}

// ./gradlew :imaging-cli:imagingTests
tasks.register<JavaExec>("imagingTests") {
    group = "verification"
    description = "Runs the photographic test suite (synthetic scenes with measurable criteria)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.camerapremium.tools.CliKt")
    args("test")
    jvmArgs("-Xmx6g", "-Dcp.testOut=${layout.buildDirectory.get().asFile}/test-output")
}
