package com.iamkaf.multiloader.support

import org.gradle.api.Project
import org.gradle.api.tasks.JavaExec
import java.util.Locale

object ClientRunEnvironmentPolicy {
    private const val X11_ON_WAYLAND_PROPERTY = "multiloader.clientRun.x11OnWayland"

    data class HostEnvironment(
        val osName: String,
        val waylandDisplay: String?,
        val xdgSessionType: String?,
    ) {
        companion object {
            fun current(): HostEnvironment =
                HostEnvironment(
                    osName = System.getProperty("os.name").orEmpty(),
                    waylandDisplay = System.getenv("WAYLAND_DISPLAY"),
                    xdgSessionType = System.getenv("XDG_SESSION_TYPE"),
                )
        }
    }

    fun configureForgeLikeClientRuns(
        project: Project,
        hostEnvironment: HostEnvironment = HostEnvironment.current(),
    ) {
        if (!shouldPreferX11(project, hostEnvironment)) return

        project.tasks.withType(JavaExec::class.java)
            .matching { task -> isClientRunTask(task.name) }
            .configureEach {
                environment.remove("WAYLAND_DISPLAY")
                environment("XDG_SESSION_TYPE", "x11")
                environment("GLFW_PLATFORM", "x11")
            }
    }

    fun applyToGroovyClientRun(
        project: Project,
        run: Any,
        hostEnvironment: HostEnvironment = HostEnvironment.current(),
    ) {
        if (!shouldPreferX11(project, hostEnvironment)) return

        runCatching { GroovyGradleDsl.invoke(run, "environment", "XDG_SESSION_TYPE", "x11") }
        runCatching { GroovyGradleDsl.invoke(run, "environment", "GLFW_PLATFORM", "x11") }

        val environmentVariables = runCatching { GroovyGradleDsl.get(run, "environmentVariables") }.getOrNull()
        if (environmentVariables is MutableMap<*, *>) {
            @Suppress("UNCHECKED_CAST")
            val map = environmentVariables as MutableMap<Any?, Any?>
            map.remove("WAYLAND_DISPLAY")
            map["XDG_SESSION_TYPE"] = "x11"
            map["GLFW_PLATFORM"] = "x11"
        }
    }

    fun shouldPreferX11OnWayland(hostEnvironment: HostEnvironment): Boolean =
        hostEnvironment.osName.lowercase(Locale.ROOT).contains("linux") &&
            (hostEnvironment.waylandDisplay != null || hostEnvironment.xdgSessionType.equals("wayland", ignoreCase = true))

    fun isClientRunTask(taskName: String): Boolean =
        taskName == "runClient" ||
            taskName == "runLegacyClient" ||
            (taskName.startsWith("run") && taskName.contains("Client"))

    private fun shouldPreferX11(project: Project, hostEnvironment: HostEnvironment): Boolean =
        isPolicyEnabled(project) && shouldPreferX11OnWayland(hostEnvironment)

    private fun isPolicyEnabled(project: Project): Boolean =
        project.providers.gradleProperty(X11_ON_WAYLAND_PROPERTY)
            .map { value -> value.toBooleanStrictOrNull() ?: !value.equals("false", ignoreCase = true) }
            .getOrElse(true)
}
