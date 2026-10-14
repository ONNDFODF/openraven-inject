plugins {
    java
    id("gg.essential.loom") version "0.10.0.+"
    id("dev.architectury.architectury-pack200") version "0.1.3"
}

group = "xyz.daikirai"
version = "1.0.0-inject"

java { toolchain.languageVersion.set(JavaLanguageVersion.of(8)) }

loom {
    forge {
        pack200Provider.set(dev.architectury.pack200.java.Pack200Adapter())
        // No mixinConfig: the injected build has no Mixin at runtime. The
        // transformer in xyz.daikirai.inject does that job, driven by
        // InjectHooks, and it is installed by the JVMTI agent rather than by a
        // tweaker.
    }
    runConfigs {
        "client" {
            isIdeConfigGenerated = true
        }
    }
}

repositories {
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:1.8.9")
    mappings("de.oceanlabs.mcp:mcp_stable:22-1.8.9")
    forge("net.minecraftforge:forge:1.8.9-11.15.1.2318-1.8.9")
    // ASM is what does the rewriting. Pinned rather than ranged: the injected
    // build ships its own copy of everything it needs, because the game it
    // lands in has whatever ASM its own ModLauncher happens to carry.
    implementation("org.ow2.asm:asm:9.4")
    implementation("org.ow2.asm:asm-tree:9.4")
    implementation("org.ow2.asm:asm-commons:9.4")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("mcmod.info") { expand("version" to project.version) }
}
tasks.withType<JavaCompile> { options.encoding = "UTF-8" }

// The name the native build looks for.
val injectJar by tasks.registering(Jar::class) {
    archiveFileName.set("OpenRaven-Inject.jar")
    // Everything, including ASM: the loader embeds this one file and the game
    // it lands in is not going to supply our dependencies.
    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/MANIFEST.MF")
        exclude("META-INF/services/javax.annotation.processing.Processor")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// remapJar is what turns MCP names into SRG names for a production Forge
// install. The injected build is not that: it lands in a launcher client that
// already speaks MCP, so the plain jar is the one to hand to the loader.
tasks.named<org.gradle.jvm.tasks.Jar>("remapJar") {
    archiveFileName.set("OpenRaven-Inject-srg.jar")
}
tasks.named("build") { dependsOn(injectJar) }
