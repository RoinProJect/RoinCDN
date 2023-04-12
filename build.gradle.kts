import io.papermc.paperweight.util.*

plugins {
    java
    `maven-publish`
    id("com.github.johnrengelman.shadow")
}

dependencies {
    implementation(project(":paper-api"))
    implementation(project(":paper-mojangapi"))
    // Paper start
    implementation("org.jline:jline-terminal-jansi:3.21.0")
    implementation("net.minecrell:terminalconsoleappender:1.3.0")
    /*
          Required to add the missing Log4j2Plugins.dat file from log4j-core
          which has been removed by Mojang. Without it, log4j has to classload
          all its classes to check if they are plugins.
          Scanning takes about 1-2 seconds so adding this speeds up the server start.
     */
    implementation("org.bouncycastle:bcpkix-jdk15on:1.68")
    implementation("org.apache.logging.log4j:log4j-core:2.14.1") // Paper - implementation
    annotationProcessor("org.apache.logging.log4j:log4j-core:2.14.1") // Paper - Needed to generate meta for our Log4j plugins
    implementation("io.netty:netty-codec-haproxy:4.1.87.Final") // Paper - Add support for proxy protocol
    // Paper "end
    implementation("org.apache.logging.log4j:log4j-iostreams:2.19.0") // Paper - remove exclusion
    implementation("org.ow2.asm:asm:9.4")
    implementation("org.ow2.asm:asm-commons:9.4") // Paper - ASM event executor generation
    testImplementation("org.mockito:mockito-core:4.9.0") // Paper - switch to mockito
    implementation("org.spongepowered:configurate-yaml:4.1.2") // Paper - config files
    implementation("commons-lang:commons-lang:2.6")
    implementation("net.fabricmc:mapping-io:0.3.0") // Paper - needed to read mappings for stacktrace deobfuscation
    runtimeOnly("org.xerial:sqlite-jdbc:3.41.0.0")
    runtimeOnly("com.mysql:mysql-connector-j:8.0.32")
    runtimeOnly("com.lmax:disruptor:3.4.4") // Paper
    // Paper start - Use Velocity cipher
    implementation("com.velocitypowered:velocity-native:3.1.2-SNAPSHOT") {
        isTransitive = false
    }
    // Paper end

    runtimeOnly("org.apache.maven:maven-resolver-provider:3.8.5")
    runtimeOnly("org.apache.maven.resolver:maven-resolver-connector-basic:1.7.3")
    runtimeOnly("org.apache.maven.resolver:maven-resolver-transport-http:1.7.3")

    testImplementation("io.github.classgraph:classgraph:4.8.47") // Paper - mob goal test
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.hamcrest:hamcrest-library:1.3")

    implementation("io.netty:netty-all:4.1.87.Final"); // Paper - Bump netty
}

val craftbukkitPackageVersion = "1_19_R3" // Paper
tasks.jar {
    archiveClassifier.set("dev")

    manifest {
        val git = Git(rootProject.layout.projectDirectory.path)
        val gitHash = git("rev-parse", "--short=7", "HEAD").getText().trim()
        val implementationVersion = System.getenv("BUILD_NUMBER") ?: "\"$gitHash\""
        val date = git("show", "-s", "--format=%ci", gitHash).getText().trim() // Paper
        val gitBranch = git("rev-parse", "--abbrev-ref", "HEAD").getText().trim() // Paper
        attributes(
            "Main-Class" to "org.bukkit.craftbukkit.Main",
            "Implementation-Title" to "CraftBukkit",
            "Implementation-Version" to "git-Paper-$implementationVersion",
            "Implementation-Vendor" to date, // Paper
            "Specification-Title" to "Bukkit",
            "Specification-Version" to project.version,
            "Specification-Vendor" to "Bukkit Team",
            "Git-Branch" to gitBranch, // Paper
            "Git-Commit" to gitHash, // Paper
            "CraftBukkit-Package-Version" to craftbukkitPackageVersion, // Paper
        )
        for (tld in setOf("net", "com", "org")) {
            attributes("$tld/bukkit", "Sealed" to true)
        }
    }
}

publishing {
    publications.create<MavenPublication>("maven") {
        artifact(tasks.shadowJar)
    }
}

relocation {
    // Order matters here - e.g. craftbukkit proper must be relocated before any of the libs are relocated into the cb package
    relocate("org.bukkit.craftbukkit" to "org.bukkit.craftbukkit.v$craftbukkitPackageVersion") {
        exclude("org.bukkit.craftbukkit.Main*")
    }
}

tasks.shadowJar {
    configurations = listOf(project.configurations.vanillaServer.get())
    archiveClassifier.set("mojang-mapped")

    for (relocation in relocation.relocations.get()) {
        relocate(relocation.fromPackage, relocation.toPackage) {
            for (exclude in relocation.excludes) {
                exclude(exclude)
            }
        }
    }
}

// Paper start
val scanJar = tasks.register("scanJarForBadCalls", io.papermc.paperweight.tasks.ScanJarForBadCalls::class) {
    badAnnotations.add("Lio/papermc/paper/annotation/DoNotUse;")
    jarToScan.set(tasks.shadowJar.flatMap { it.archiveFile })
    classpath.from(configurations.compileClasspath)
}
tasks.check {
    dependsOn(scanJar)
}
// Paper end

// Paper start - include reobf mappings in jar for stacktrace deobfuscation
val includeMappings = tasks.register<io.papermc.paperweight.tasks.IncludeMappings>("includeMappings") {
    inputJar.set(tasks.fixJarForReobf.flatMap { it.outputJar })
    mappings.set(tasks.reobfJar.flatMap { it.mappingsFile })
    mappingsDest.set("META-INF/mappings/reobf.tiny")
}

tasks.reobfJar {
    inputJar.set(includeMappings.flatMap { it.outputJar })
}
// Paper end - include reobf mappings in jar for stacktrace deobfuscation

tasks.test {
    exclude("org/bukkit/craftbukkit/inventory/ItemStack*Test.class")
}

fun TaskContainer.registerRunTask(
    name: String,
    block: JavaExec.() -> Unit
): TaskProvider<JavaExec> = register<JavaExec>(name) {
    group = "paper"
    mainClass.set("org.bukkit.craftbukkit.Main")
    standardInput = System.`in`
    workingDir = rootProject.layout.projectDirectory
        .dir(providers.gradleProperty("paper.runWorkDir").getOrElse("run"))
        .asFile
    javaLauncher.set(project.javaToolchains.defaultJavaLauncher(project))

    if (rootProject.childProjects["test-plugin"] != null) {
        val testPluginJar = rootProject.project(":test-plugin").tasks.jar.flatMap { it.archiveFile }
        inputs.file(testPluginJar)
        args("-add-plugin=${testPluginJar.get().asFile.absolutePath}")
    }

    args("--nogui")
    systemProperty("net.kyori.adventure.text.warnWhenLegacyFormattingDetected", true)
    if (providers.gradleProperty("paper.runDisableWatchdog").getOrElse("false") == "true") {
        systemProperty("disable.watchdog", true)
    }

    val memoryGb = providers.gradleProperty("paper.runMemoryGb").getOrElse("2")
    minHeapSize = "${memoryGb}G"
    maxHeapSize = "${memoryGb}G"

    doFirst {
        workingDir.mkdirs()
    }

    block(this)
}

val runtimeClasspathWithoutVanillaServer = configurations.runtimeClasspath.flatMap { it.elements }
    .zip(configurations.vanillaServer.map { it.singleFile.absolutePath }) { runtime, vanilla ->
        runtime.filterNot { it.asFile.absolutePath == vanilla }
    }

tasks.registerRunTask("runShadow") {
    description = "Spin up a test server from the shadowJar archiveFile"
    classpath(tasks.shadowJar.flatMap { it.archiveFile })
    classpath(runtimeClasspathWithoutVanillaServer)
}

tasks.registerRunTask("runReobf") {
    description = "Spin up a test server from the reobfJar output jar"
    classpath(tasks.reobfJar.flatMap { it.outputJar })
    classpath(runtimeClasspathWithoutVanillaServer)
}

tasks.registerRunTask("runDev") {
    description = "Spin up a non-relocated Mojang-mapped test server"
    classpath(sourceSets.main.map { it.runtimeClasspath })
}
