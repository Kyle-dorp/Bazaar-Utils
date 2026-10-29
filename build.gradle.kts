import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.HexFormat

plugins {
    id("net.fabricmc.fabric-loom") version "1.17.16"
    id("maven-publish")
    id("me.modmuss50.mod-publish-plugin") version "2.0.0"
    `maven-publish`
    java
}

base {
    archivesName.set(property("mod.id").toString())
}

repositories {
    maven {
        name = "Dev Auth"
        url = uri("https://pkgs.dev.azure.com/djtheredstoner/DevAuth/_packaging/public/maven/v1")
    }
    maven {
        name = "meteor-maven"
        url = uri("https://maven.meteordev.org/releases")
    }
    maven {
        name = "Hypixel"
        url = uri("https://repo.hypixel.net/repository/Hypixel/")
    }
    maven {
        name = "YACL"
        url = uri("https://maven.isxander.dev/releases")
    }
    maven {
        name = "Terraformers (for gui)"
        url = uri("https://maven.terraformersmc.com/")
    }

    exclusiveContent {
        forRepository {
            maven {
                url = uri("https://cursemaven.com")
                name = "CurseMaven" // Repository name is often required for exclusiveContent
            }
        }
        filter {
            includeGroup("curse.maven")
        }
        forRepository {
            maven {
                url = uri("https://api.modrinth.com/maven")
                name = "Modrinth"
            }
        }
        filter {
            includeGroup("maven.modrinth")
        }
    }
    mavenCentral()
}

class ModDependencies {
    operator fun get(name: String) = property("deps.$name").toString()
}
val deps = ModDependencies()
val mcVersion = stonecutter.current.version
val maxMcVersion = deps["core.maxMcVersion"]
group = property("maven_group")!!
val versionNumber = property("mod_version") as String
val releaseChannel = property("mod_release_channel") as String
val preReleaseNumber = property("mod_prerelease_number")

version = if (preReleaseNumber == 0) {
    "$versionNumber-$releaseChannel+mc$mcVersion"
} else {
    "$versionNumber-$releaseChannel.$preReleaseNumber+mc$mcVersion"
}

dependencies {
    minecraft("com.mojang:minecraft:${mcVersion}")
    implementation("net.fabricmc:fabric-loader:${deps["fabricLoaderVersion"]}")

    implementation("net.fabricmc.fabric-api:fabric-api:${property("deps.fabric_api")}")

    runtimeOnly("me.djtheredstoner:DevAuth-fabric:1.2.1")

    implementation("meteordevelopment:orbit:0.2.4")
    include("meteordevelopment:orbit:0.2.4")

    implementation("net.hypixel:hypixel-api-transport-apache:4.4")
    include("net.hypixel:hypixel-api-transport-apache:4.4")
    include("net.hypixel:hypixel-api-core:4.4")

    // Apache HTTP Client + all transitive deps — no longer bundled as of 1.21.11
    include("org.apache.httpcomponents:httpclient:4.5.14")
    include("org.apache.httpcomponents:httpcore:4.4.16")
    include("commons-logging:commons-logging:1.2")
    include("commons-codec:commons-codec:1.16.0")

    // Config lib and settings screen
    implementation("dev.isxander:yet-another-config-lib:${deps["yacl_version"]}-fabric")

    compileOnly("maven.modrinth:modmenu:${property("modmenu_version")}")

    // Project Lombok
    compileOnly("org.projectlombok:lombok:1.18.46")
    annotationProcessor("org.projectlombok:lombok:1.18.46")
    // Mixin Constraints
    include(implementation("com.moulberry:mixinconstraints:1.0.8")!!)

    //gson extras for easy type adapters
    implementation("org.danilopianini:gson-extras:3.3.0")
    include("org.danilopianini:gson-extras:3.3.0")
    // Skyblocker for compatibility
    compileOnly("maven.modrinth:skyblocker-liap:v${deps["skyblocker_version"]}")

    // Unit tests for the pure calculators (no game needed)
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val buildtimeInjectionTask = tasks.register<com.github.mkram17.bazaarutils.build.BuildtimeInjectionTask>("processInitAnnotations") {
    group = "build"
    description = "Scans for @RunOnInit @RegisterWidget annotations and injects method calls into their respective methods."
    // This task should run after compileJava
    dependsOn(tasks.compileJava)
    // The input is the output directory of the compileJava task
    classesDir.set(tasks.compileJava.get().destinationDirectory)
}

tasks {
    classes {
        dependsOn(buildtimeInjectionTask)
    }

    processResources {
        inputs.property("version", project.version)
        inputs.property("mcVersion", mcVersion)
        inputs.property("major_update_notes", rootProject.property("major_update_notes") as String)

        filesMatching("fabric.mod.json") {
            expand(mapOf(
                "version" to project.version,
                "mod_version" to rootProject.property("mod_version"),
                "mcVersion" to mcVersion,
                "maxMcVersion" to maxMcVersion,
                "major_update_notes" to rootProject.property("major_update_notes")
            ))
        }
    }

    test {
        useJUnitPlatform()
    }

    jar {
        from("LICENSE"){
            rename { "${it}_${archiveBaseName.get()}" }
        }
    }
}
tasks.register("installLocal") {
    group = "build"
    description = "Installs the tested local build with a rollback backup; requires -PminecraftModsDir."
    dependsOn(tasks.build)
    doLast {
        val mods = Path.of(providers.gradleProperty("minecraftModsDir").get())
        val backupDir = mods.parent.resolve("mods-backup")
        val dist = rootProject.layout.projectDirectory.dir("dist").asFile.toPath()
        require(Files.isDirectory(mods) && Files.isDirectory(backupDir) && Files.isDirectory(dist))
        check(ProcessHandle.allProcesses().use { processes ->
            processes.noneMatch { process ->
                val command = process.info().command().orElse("").lowercase()
                val args = process.info().commandLine().orElse("").lowercase()
                (command.endsWith("javaw.exe") || command.endsWith("java.exe")) &&
                    (args.contains("net.fabricmc.loader") || args.contains("net.minecraft"))
            }
        }) { "Close Minecraft before installing." }
        val artifact = tasks.jar.get().archiveFile.get().asFile.toPath()
        val target = mods.resolve(artifact.fileName)
        require(Files.isRegularFile(target)) { "The installed BazaarUtils JAR was not found." }
        val hash = { path: Path -> HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))) }
        val expected = hash(artifact)
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
        val backup = backupDir.resolve("${target.fileName}.$stamp.bak")
        Files.copy(target, backup)
        val incoming = Files.createTempFile(backupDir, "bazaarutils-install-", ".incoming")
        try {
            Files.copy(artifact, incoming, StandardCopyOption.REPLACE_EXISTING)
            check(hash(incoming) == expected) { "The incoming JAR failed verification." }
            Files.copy(artifact, dist.resolve(artifact.fileName), StandardCopyOption.REPLACE_EXISTING)
            val staged = dist.resolve("bazaarutils-staged.jar")
            Files.copy(artifact, staged, StandardCopyOption.REPLACE_EXISTING)
            Files.writeString(dist.resolve("bazaarutils-staged.jar.sha256"), expected + "\n")
            Files.move(incoming, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            check(hash(target) == expected) { "The installed JAR failed verification; rollback backup: $backup" }
            logger.lifecycle("Installed: $target\nRollback backup: $backup\nSHA-256: $expected")
        } finally {
            Files.deleteIfExists(incoming)
        }
    }
}

loom {
    runConfigs.all {
        ideConfigGenerated(true) // Run configurations are not created for subprojects by default
        runDir = "../../run" // Use a shared run folder and create separate worlds
    }
}
java {
    withSourcesJar()
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

publishMods {
    version.set(project.version.toString())
    file.set(tasks.jar.get().archiveFile)
    version.set(project.version.toString())
    type.set(if(releaseChannel == "alpha") ALPHA else STABLE)
    modLoaders.add("fabric")
    changelog.set(rootProject.file("UPDATES.MD").readText())
    displayName = if (preReleaseNumber == 0) {
        "Bazaar Utils v$versionNumber-$releaseChannel for $mcVersion"
    } else {
        "Bazaar Utils v$versionNumber-$releaseChannel.$preReleaseNumber for $mcVersion"
    }
    dryRun = true

    modrinth {
        accessToken = providers.environmentVariable("MODRINTH_TOKEN")
        projectId = "c4u7nzUZ"
        minecraftVersions.add(mcVersion)

        requires("fabric-api", "yacl")
        optional("modmenu")
    }
    github {
        accessToken = providers.environmentVariable("GITHUB_TOKEN")
        repository = "mkram17/Bazaar-Utils"
        commitish = "modern"
        tagName = "v" + project.version.toString()
        type = STABLE
    }
    curseforge {
        accessToken = providers.environmentVariable("CURSEFORGE_TOKEN")
        projectId = "1342860"
    }
}