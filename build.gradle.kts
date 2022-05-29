plugins {
    kotlin("jvm") version "1.6.21"
    kotlin("plugin.serialization") version "1.6.21"

    id("net.mamoe.mirai-console") version "2.11.1"
    id("net.mamoe.maven-central-publish") version "0.7.1"
}

group = "io.github.gnuf0rce"
version = "1.2.1"

mavenCentralPublish {
    useCentralS01()
    singleDevGithubProject("gnuf0rce", "rss-helper", "cssxsh")
    licenseFromGitHubProject("AGPL-3.0", "master")
    publication {
        artifact(tasks.getByName("buildPlugin"))
        artifact(tasks.getByName("buildPluginLegacy"))
    }
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-client-encoding:1.6.7") {
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains.kotlinx")
        exclude(group = "org.slf4j")
        exclude(group = "io.ktor", module = "ktor-client-core")
    }
    implementation("io.ktor:ktor-client-serialization:1.6.7") {
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains.kotlinx")
        exclude(group = "org.slf4j")
        exclude(group = "io.ktor", module = "ktor-client-core")
    }
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.9.3") {
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "com.squareup.okhttp3")
    }
    implementation("com.rometools:rome:1.18.0") {
        exclude(group = "org.slf4j")
    }
    implementation("org.jsoup:jsoup:1.14.3")
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
    compileOnly("net.mamoe:mirai-core:2.11.1")
    compileOnly("net.mamoe:mirai-core-utils:2.11.1")
    // test
    testImplementation(kotlin("test", "1.6.21"))
}

tasks {
    test {
        useJUnitPlatform()
    }
}