plugins {
    java

    alias(libs.plugins.kotlin)
    alias(libs.plugins.shadow)

    alias(libs.plugins.jpnilla.runPaper)

    alias(libs.plugins.versionCatalogueUpdate)
}

group = "com.swapflip.fortunepillars"
version = "0.1"
description = "Open-Source & customizable \"Pillars of Fortune\"-like game — spawn on bedrock pillars, get random items, and dominate!"

versionCatalogUpdate {
    pin { // Don't auto-update these.
        versions.add("kotlin")
        versions.add("paper")
    }
    keep {
        versions.add("paper")
    }
}

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenLocal()
    mavenCentral()

//    maven("https://marcpg.com/repo/")
    maven("https://repo.faststats.dev/releases/")
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly(kotlin("stdlib"))
    compileOnly(libs.paper.api)
    compileOnly("com.mojang:brigadier:1.0.18")
    compileOnly("me.clip:placeholderapi:2.11.6")

    implementation(files("libs/ktlibpg-full-${libs.versions.ktlibpg.get()}.jar"))
    implementation(files("libs/adventure-nbt-4.26.1.jar"))
    implementation(libs.faststats)

    testImplementation(kotlin("test"))
}

tasks {
    test {
        useJUnitPlatform()
    }

    build {
        dependsOn(shadowJar)
    }
    runServer {
        dependsOn(shadowJar)
        minecraftVersion("1.20.4")
    }
    shadowJar {
        archiveClassifier.set("")
        relocate("dev.faststats", "$group.libs.faststats")
    }
}
