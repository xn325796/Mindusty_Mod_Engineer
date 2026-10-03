plugins {
    application
}

group = "dev.modmaker"
version = "0.1.0"

repositories {
    mavenCentral()
}

val javafxVersion = "21.0.4"
val javafxPlatform = "win"

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.openjfx:javafx-base:$javafxVersion:$javafxPlatform")
    implementation("org.openjfx:javafx-graphics:$javafxVersion:$javafxPlatform")
    implementation("org.openjfx:javafx-controls:$javafxVersion:$javafxPlatform")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.junit.platform:junit-platform-launcher:1.10.2")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

application {
    // Launcher does not extend Application, which keeps JavaFX usable straight from the classpath.
    mainClass.set("dev.modmaker.Launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

// Ship the schema data inside the jar so a packaged build does not depend on the working directory.
tasks.processResources {
    from("schemas") {
        into("schemas")
    }
}

tasks.test {
    useJUnitPlatform()
    systemProperty("file.encoding", "UTF-8")
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}

// Scans the Mindustry sources for content-class fields and vanilla content names,
// writing schemas/fields.json + schemas/vanilla-content.json.
tasks.register<JavaExec>("schemaBootstrap") {
    group = "modmaker"
    description = "Regenerate schemas/fields.json and schemas/vanilla-content.json from Mindustry sources"
    mainClass.set("dev.modmaker.tools.SchemaBootstrap")
    classpath = sourceSets["main"].runtimeClasspath
    args = listOf(project.findProperty("mindustrySrc")?.toString()
        ?: "../Mindustry-160.5/core/src/mindustry")
}
