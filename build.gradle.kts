import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    war
    kotlin("jvm") version "1.9.10"
    application
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("com.squareup.okhttp3:okhttp:4.9.3")
    implementation("io.github.microutils:kotlin-logging-jvm:2.1.23")
    implementation("org.slf4j:slf4j-simple:1.7.30")
    implementation("com.microsoft.sqlserver:mssql-jdbc:9.4.0.jre8")
    implementation("javax.servlet:javax.servlet-api:4.0.1")
    implementation("org.json:json:20231013")
    implementation ("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    providedCompile ("javax.servlet:javax.servlet-api:4.0.1")



}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

application {
    mainClass.set("MainKt")
}