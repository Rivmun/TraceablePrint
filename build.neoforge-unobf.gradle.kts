plugins {
    id("dev.architectury.loom-no-remap") version "1.14-SNAPSHOT"
}

val minecraft = property("deps.minecraft") as String

loom {
    //accessWidenerPath = rootProject.file("src/main/resources/${property("mod.id")}.unobf.accesswidener")
}

tasks.named<ProcessResources>("processResources") {
    fun prop(name: String) = project.property(name) as String

    val props = HashMap<String, String>().apply {
        this["mod_group"] =     prop("mod.group")
        this["mod_id"] =        prop("mod.id")
        this["mod_name"] =      prop("mod.name")
        this["mod_version"] =   prop("mod.version")
        this["mod_description"]=prop("mod.description")
        this["mod_author"] =    prop("mod.author")
        this["mod_contributor"]=prop("mod.contributor")
        this["mod_sources"] =   prop("mod.sources")
        this["mod_issues"] =    prop("mod.issues")
        this["mod_homepage"] =  prop("mod.homepage")
        this["mod_modrinth"] =  prop("mod.modrinth")
        this["mod_mcmod"] =     prop("mod.mcmod")
        this["mod_license"] =   prop("mod.license")
        this["mod_icon"] =      prop("mod.icon")

        this["version_range"] = prop("version_range")
        this["neoforge_min_version"] = prop("neoforge_min_version")

        // insert version-specific mixins
    }

    filesMatching(listOf("META-INF/neoforge.mods.toml", "${prop("mod.id")}.mixins.json")) {
        expand(props)
    }
}

version = "${property("mod.version")}+${minecraft}-neoforge"
base.archivesName = property("mod.id") as String

repositories {
    mavenLocal()
    maven("https://maven.neoforged.net/releases/")
    maven("https://api.modrinth.com/maven")
    maven("https://maven.shedaniel.me/")
}

dependencies {
    minecraft("com.mojang:minecraft:${property("deps.minecraft")}")
    neoForge("net.neoforged:neoforge:${property("deps.neoforge")}")

    // cloth
    api("me.shedaniel.cloth:cloth-config-neoforge:${property("deps.cloth")}") {
        exclude(group = "net.fabricmc.fabric-api")
    }
}

tasks {
    processResources {
        exclude("**/fabric.mod.json", "**/mods.toml", "**/*.accesswidener")
        // 26.x 走新版 RenderPipeline + footprint_pulse；剔除整个 minecraft 覆盖命名空间（含空目录条目）。
        exclude("assets/minecraft/**")
    }

    jar {
        manifest.attributes["MixinConfigs"] = "${project.property("mod.id")}.mixins.json"
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}
