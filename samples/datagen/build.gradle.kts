plugins {
    id("com.iamkaf.multiloader.root")
    id("com.iamkaf.multiloader.common") apply false
    id("com.iamkaf.multiloader.fabric") apply false
}

tasks.register("validateSampleWiring") {
    group = "verification"
    description = "Checks that the datagen sample gets the expected Fabric defaults."
    dependsOn("validateConventionProperties")
    doLast {
        val fabricProject = project(":fabric")

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

        check(fabricProject.tasks.findByName("runDatagen") != null) { "Fabric runDatagen task is missing" }

        val extension = fabricProject.extensions.getByType(
            com.iamkaf.multiloader.fabric.MultiloaderFabricExtension::class.java,
        )
        check(extension.commonDatagen.get()) {
            "Fabric datagen must be enabled through the typed multiloaderFabric extension"
        }

        check(!fabricProject.extensions.extraProperties.has("enableCommonFabricDatagen")) {
            "Deprecated enableCommonFabricDatagen helper must not be exposed"
        }
    }
}

tasks.register("verifyDatagenOutput") {
    group = "verification"
    description = "Verifies that Fabric datagen writes into common/src/main/generated."
    dependsOn(":fabric:runDatagen")
    doLast {
        val generatedLang = file("common/src/main/generated/assets/exampledatagen/lang/en_us.json")
        check(generatedLang.isFile) { "Expected generated language file at $generatedLang" }
        check(generatedLang.readText().contains("\"itemGroup.exampledatagen\": \"Example Datagen\""))
        check(generatedLang.readText().contains("\"exampledatagen.dummy\": \"Dummy Datagen Value\""))
    }
}
