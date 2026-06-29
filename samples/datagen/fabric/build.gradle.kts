plugins {
    id("com.iamkaf.multiloader.fabric")
}

dependencies {
    add("modImplementation", libs.fabric.api)
}

multiloaderFabric {
    commonDatagen.set(true)
}
