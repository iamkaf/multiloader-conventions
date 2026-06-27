plugins {
    id("com.iamkaf.multiloader.root")
    id("com.iamkaf.multiloader.publishing")
    id("com.iamkaf.multiloader.common") apply false
    id("com.iamkaf.multiloader.fabric") apply false
    id("com.iamkaf.multiloader.forge") apply false
    id("com.iamkaf.multiloader.neoforge") apply false
}

tasks.register("validateSampleWiring") {
    group = "verification"
    description = "Checks that the minimal sample gets the expected loader defaults from the convention plugins."
    dependsOn("validateConventionProperties")
    doLast {
        val fabricProject = project(":fabric")
        val forgeProject = project(":forge")
        val neoForgeProject = project(":neoforge")

        check(
            fabricProject.configurations.getByName("modImplementation").allDependencies.any {
                it.group == "net.fabricmc" && it.name == "fabric-loader"
            },
        ) { "Fabric plugin must provide the Fabric loader dependency" }

        check(
            fabricProject.configurations.getByName("minecraft").allDependencies.any {
                it.group == "com.mojang" && it.name == "minecraft"
            },
        ) { "Fabric plugin must provide the Minecraft dependency" }

        check(fabricProject.tasks.findByName("runClient") != null) { "Fabric runClient task is missing" }
        check(forgeProject.tasks.findByName("runClient") != null) { "Forge runClient task is missing" }
        check(neoForgeProject.tasks.findByName("runClient") != null) { "NeoForge runClient task is missing" }
        check(project.tasks.findByName("publishingRelease") != null) { "publishingRelease task is missing" }
    }
}
