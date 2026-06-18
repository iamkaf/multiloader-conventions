package com.iamkaf.multiloader.root

import com.iamkaf.multiloader.publishing.MultiloaderPublishingExtension
import com.iamkaf.multiloader.translations.MultiloaderTranslationsExtension
import groovy.json.JsonOutput
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task

import java.util.Properties

class MultiloaderRootPlugin implements Plugin<Project> {

    private static final List<String> FLAT_REQUIRED_PROPERTIES = [
        'mod.name',
        'mod.id',
        'project.minecraft',
        'project.java',
    ]
    private static final Set<String> TEAKIT_RUN_TASK_NAMES = ['runClient', 'runLegacyClient'] as Set
    private static final String TEAKIT_PROPERTY_PREFIX = 'teakit.'
    private static final List<String> KNOWN_LOADERS = ['fabric', 'forge', 'neoforge']
    private static final List<String> LEGACY_FABRIC_ONLY = ['1.14.4', '1.15', '1.15.1', '1.15.2', '1.16', '1.16.1', '1.16.2', '1.16.3', '1.16.4', '1.16.5', '1.17']
    private static final Map<String, String> FORGE_LOADER_RANGES = [
        '1.16.4':'[36,)', '1.16.5':'[36,)', '1.17.1':'[37,)',
        '1.18':'[38,)', '1.18.1':'[39,)', '1.18.2':'[40,)',
        '1.19':'[41,)', '1.19.1':'[42,)', '1.19.2':'[43,)', '1.19.3':'[44,)', '1.19.4':'[45,)',
        '1.20':'[46,)', '1.20.1':'[47,)', '1.20.2':'[48,)', '1.20.3':'[49,)', '1.20.4':'[49,)', '1.20.6':'[50,)',
        '1.21':'[51,)', '1.21.1':'[52,)', '1.21.3':'[53,)', '1.21.4':'[54,)', '1.21.5':'[55,)', '1.21.6':'[56,)',
        '1.21.7':'[57,)', '1.21.8':'[58,)', '1.21.9':'[59,)', '1.21.10':'[60,)', '1.21.11':'[61,)',
        '26.1':'[62,)', '26.1.1':'[62,)', '26.1.2':'[62,)'
    ]

    @Override
    void apply(Project project) {
        if (project != project.rootProject) {
            throw new GradleException('com.iamkaf.multiloader.root must be applied to the root project only.')
        }

        configureTeaKitRunPropertyForwarding(project)

        if (hasVersionMatrix(project)) {
            applyStonecutterRootPlugin(project)
            return
        }

        applyFlatRootPlugin(project)
    }

    private static void applyFlatRootPlugin(Project project) {
        applyCoordinates(project)
        registerFlatValidationTask(project)
        registerBuildGraphTasks(project)
        registerAggregateTask(project, 'buildAllLoaders', 'build')
        registerAggregateTask(project, 'checkAllLoaders', 'check')
        registerRunClientTask(project, 'runClientFabric', 'fabric')
        registerRunClientTask(project, 'runClientForge', 'forge')
        registerRunClientTask(project, 'runClientNeoForge', 'neoforge')
    }

    private static void applyStonecutterRootPlugin(Project project) {
        project.pluginManager.apply('com.iamkaf.multiloader.publishing')
        project.pluginManager.apply('com.iamkaf.multiloader.translations')

        applyCoordinates(project)

        def modId = requiredProperty(project, 'mod.id')
        def versionDirs = versionDirectories(project)

        project.extensions.configure(MultiloaderTranslationsExtension) { extension ->
            extension.projectSlug.set(project.providers.gradleProperty('mod.id'))
            extension.outputDir.set(project.layout.projectDirectory.dir("common/src/main/resources/assets/${modId}/lang"))
        }

        project.extensions.configure(MultiloaderPublishingExtension) { extension ->
            versionDirs.each { dir ->
                def props = versionMetadata(dir)
                def minecraftVersion = props.getProperty('project.minecraft')
                def javaVersion = props.getProperty('project.java')
                def enabledLoaders = parseEnabledLoaders(props)

                enabledLoaders.each { loaderId ->
                    extension.publication("${minecraftVersion}-${loaderId}") {
                        projectPath.set(":${loaderId}:${minecraftVersion}")
                        artifactTask.set(loaderId == 'fabric' && !minecraftVersion.startsWith('26.') ? 'remapJar' : 'jar')
                        if (loaderId == 'fabric' && !minecraftVersion.startsWith('26.')) {
                            fallbackArtifactTask.set('jar')
                        }
                        if (loaderId == 'forge') {
                            buildTasks.add('reobfJar')
                        }
                        loaders.add(loaderId)
                        gameVersions.add(minecraftVersion)
                        javaVersions.add(javaVersion)
                    }
                }
            }
        }

        registerBuildGraphTasks(project)
    }

    private static void applyCoordinates(Project project) {
        def groupValue = project.findProperty('project.group')
        def versionValue = project.findProperty('project.version')

        if (groupValue != null) {
            project.group = groupValue.toString()
        }

        if (versionValue != null) {
            project.version = versionValue.toString()
        }
    }

    private static void registerFlatValidationTask(Project project) {
        project.tasks.register('validateConventionProperties') { task ->
            task.group = 'verification'
            task.description = 'Checks that the required convention properties are present.'
            task.doLast {
                def missing = FLAT_REQUIRED_PROPERTIES.findAll { propertyName ->
                    def value = project.findProperty(propertyName)
                    value == null || value.toString().isBlank()
                }

                if (!missing.isEmpty()) {
                    throw new GradleException("Missing required convention properties: ${missing.join(', ')}")
                }
            }
        }
    }

    private static void registerAggregateTask(Project project, String taskName, String childTaskName) {
        project.tasks.register(taskName) { task ->
            task.group = 'build'
            task.description = "Runs ${childTaskName} on every included loader project."

            project.gradle.projectsEvaluated {
                task.dependsOn(project.subprojects
                    .findAll { child -> child.tasks.findByName(childTaskName) != null }
                    .collect { child -> "${child.path}:${childTaskName}" })
            }
        }
    }

    private static void registerRunClientTask(Project project, String taskName, String projectName) {
        project.tasks.register(taskName) { task ->
            task.group = 'run'
            task.description = "Runs ${projectName}:runClient when that project exists."

            project.gradle.projectsEvaluated {
                def child = project.findProject(":${projectName}")
                if (child != null && child.tasks.findByName('runClient') != null) {
                    task.dependsOn("${child.path}:runClient")
                }
            }
        }
    }

    private static void configureTeaKitRunPropertyForwarding(Project project) {
        project.subprojects { child ->
            child.tasks.configureEach { task ->
                if (!TEAKIT_RUN_TASK_NAMES.contains(task.name)) {
                    return
                }

                def systemPropertyMethod = task.class.methods.find { method ->
                    method.name == 'systemProperty' && method.parameterCount == 2
                }
                if (systemPropertyMethod == null) {
                    return
                }

                collectTeaKitSystemProperties(project).each { propertyName, value ->
                    systemPropertyMethod.invoke(task, propertyName, value)
                }
            }
        }
    }

    private static String requiredProperty(Project project, String name) {
        def value = project.findProperty(name)?.toString()
        if (value == null || value.isBlank()) {
            throw new GradleException("Missing required property '${name}'")
        }
        value
    }

    private static boolean hasVersionMatrix(Project project) {
        !versionDirectories(project).isEmpty()
    }

    private static List<File> versionDirectories(Project project) {
        def versionsDir = project.file('versions')
        if (!versionsDir.isDirectory()) {
            return []
        }

        versionsDir.listFiles()
            ?.findAll { candidate -> candidate.isDirectory() }
            ?.sort { left, right -> left.name <=> right.name }
            ?: []
    }

    private static Properties loadProperties(File file) {
        def properties = new Properties()
        file.withInputStream(properties.&load)
        properties
    }

    private static Properties versionMetadata(File versionDir) {
        def metadataFile = new File(versionDir, 'gradle.properties')
        if (metadataFile.isFile()) {
            return loadProperties(metadataFile)
        }

        def versionKey = versionDir.name
        def props = new Properties()
        props.setProperty('project.minecraft', versionKey)
        props.setProperty('project.version', "11.0.0+${versionKey}")
        props.setProperty('project.java', javaVersion(versionKey))
        props.setProperty('project.enabled-loaders', enabledLoaders(versionKey))
        def forgeRange = FORGE_LOADER_RANGES[versionKey]
        if (forgeRange != null) {
            props.setProperty('mod.forge-loader-range', forgeRange)
        }
        if (enabledLoaders(versionKey).contains('neoforge')) {
            props.setProperty('mod.neoforge-loader-range', '[4,)')
        }
        props
    }

    private static String enabledLoaders(String versionKey) {
        if (LEGACY_FABRIC_ONLY.contains(versionKey) || versionKey == '1.20.5') {
            return 'fabric'
        }
        if (versionKey == '1.21.2') {
            return 'fabric,neoforge'
        }
        if (versionKey == '1.21.1' || versionKey.startsWith('26.') || (versionKey.startsWith('1.21.') && versionKey != '1.21.2')) {
            return 'fabric,forge,neoforge'
        }
        return 'fabric,forge'
    }

    private static String javaVersion(String versionKey) {
        if (versionKey.startsWith('26.')) {
            return '25'
        }
        if (versionKey.startsWith('1.14') || versionKey.startsWith('1.15') || versionKey.startsWith('1.16')) {
            return '8'
        }
        if (versionKey == '1.17' || versionKey == '1.17.1') {
            return '16'
        }
        if (versionKey == '1.20.5' || versionKey == '1.20.6' || versionKey.startsWith('1.21')) {
            return '21'
        }
        return '17'
    }

    private static List<String> parseEnabledLoaders(Properties props) {
        props.getProperty('project.enabled-loaders', '')
            .split(',')
            .collect { it.trim() }
            .findAll { !it.isBlank() }
    }

    private static void registerBuildGraphTasks(Project project) {
        def graphFile = project.layout.buildDirectory.file('reports/multiloader/graph.json')

        project.tasks.register('writeMultiloaderGraph') { task ->
            task.group = 'help'
            task.description = 'Writes the resolved multiloader build graph as JSON.'
            task.outputs.file(graphFile)

            task.doLast {
                def outputFile = graphFile.get().asFile
                outputFile.parentFile.mkdirs()
                outputFile.text = graphJson(project) + System.lineSeparator()
                project.logger.lifecycle("Wrote ${project.relativePath(outputFile)}")
            }
        }

        project.tasks.register('printMultiloaderGraph') { task ->
            task.group = 'help'
            task.description = 'Prints the resolved multiloader build graph as JSON.'

            task.doLast {
                println graphJson(project)
            }
        }
    }

    private static String graphJson(Project project) {
        JsonOutput.prettyPrint(JsonOutput.toJson(buildGraph(project)))
    }

    private static Map<String, Object> buildGraph(Project project) {
        def versionDirs = versionDirectories(project)
        def teaKitNodes = readTeaKitNodes(project)

        [
            schemaVersion: 1,
            project      : [
                name   : project.name,
                group  : project.group?.toString(),
                version: project.version?.toString(),
            ],
            mod          : [
                id  : optionalProjectProperty(project, 'mod.id'),
                name: optionalProjectProperty(project, 'mod.name'),
            ],
            conventions  : [
                version          : optionalProjectProperty(project, 'project.plugins'),
                stonecutterVersion: detectStonecutterVersion(project),
            ],
            versions     : versionDirs.isEmpty()
                ? [flatVersionGraph(project, teaKitNodes)]
                : versionDirs.collect { versionGraph(project, it, teaKitNodes) },
        ]
    }

    private static Map<String, Object> flatVersionGraph(Project project, List<Map<String, String>> teaKitNodes) {
        def props = new Properties()
        ['project.minecraft', 'project.version', 'project.java', 'project.build-java', 'project.enabled-loaders',
         'mod.minecraft-range', 'mod.fabric-range', 'mod.forge-loader-range', 'mod.neoforge-loader-range'].each { name ->
            def value = optionalProjectProperty(project, name)
            if (value != null) {
                props.setProperty(name, value)
            }
        }

        def minecraftVersion = props.getProperty('project.minecraft')
        versionGraph(project, null, teaKitNodes, props, minecraftVersion)
    }

    private static Map<String, Object> versionGraph(Project project, File versionDir, List<Map<String, String>> teaKitNodes, Properties providedProps = null, String providedVersion = null) {
        def props = providedProps ?: versionMetadata(versionDir)
        def minecraftVersion = providedVersion ?: props.getProperty('project.minecraft') ?: versionDir.name
        def enabledLoaders = parseEnabledLoaders(props)

        [
            name          : minecraftVersion,
            minecraft     : minecraftVersion,
            projectVersion: props.getProperty('project.version') ?: project.version?.toString(),
            java          : props.getProperty('project.java'),
            buildJava     : props.getProperty('project.build-java'),
            catalog       : catalogName(minecraftVersion),
            enabledLoaders: enabledLoaders,
            ranges        : [
                minecraft: props.getProperty('mod.minecraft-range'),
                fabric   : props.getProperty('mod.fabric-range'),
                forge    : props.getProperty('mod.forge-loader-range'),
                neoforge : props.getProperty('mod.neoforge-loader-range'),
            ].findAll { _, value -> value != null },
            common        : commonGraph(project, minecraftVersion),
            loaders       : KNOWN_LOADERS.collect { loader ->
                loaderGraph(project, minecraftVersion, props, loader, enabledLoaders.contains(loader), teaKitNodes)
            },
        ]
    }

    private static Map<String, Object> commonGraph(Project project, String minecraftVersion) {
        def commonPath = minecraftVersion == null ? ':common' : ":common:${minecraftVersion}"
        def commonProject = project.findProject(commonPath)
        [
            projectPath      : commonPath,
            projectExists    : commonProject != null,
            compileTask      : taskPath(commonProject, 'compileJava'),
            buildTask        : taskPath(commonProject, 'build'),
            mavenPublishTasks: publishTasks(commonProject, 'publish', 'PublicationTo'),
        ]
    }

    private static Map<String, Object> loaderGraph(Project project, String minecraftVersion, Properties props, String loader, boolean enabled, List<Map<String, String>> teaKitNodes) {
        def loaderPath = minecraftVersion == null ? ":${loader}" : ":${loader}:${minecraftVersion}"
        def loaderProject = project.findProject(loaderPath)
        def artifactTask = artifactTaskName(loader, minecraftVersion)
        def artifactTaskPath = taskPath(loaderProject, artifactTask) ?: taskPath(loaderProject, 'jar')
        def artifactPath = artifactTaskPath == null ? null : artifactPath(project, loaderProject, loader, minecraftVersion, props)
        def publishSuffix = taskSuffix(minecraftVersion == null ? loader : "${minecraftVersion}-${loader}")

        [
            name              : loader,
            enabled           : enabled,
            projectPath       : loaderPath,
            projectExists     : loaderProject != null,
            loaderRootExists  : project.file(loader).isDirectory(),
            buildTask         : taskPath(loaderProject, 'build'),
            runClientTask     : taskPath(loaderProject, 'runClient'),
            artifactTask      : artifactTaskPath,
            artifactPath      : artifactPath,
            mavenPublishTasks : publishTasks(loaderProject, 'publish', 'PublicationTo'),
            platformPublishTasks: [
                modrinth  : taskPath(project, "publishModrinth${publishSuffix}"),
                curseforge: taskPath(project, "publishCurseforge${publishSuffix}"),
            ].findAll { _, value -> value != null },
            scenarioNodes     : teaKitNodes.findAll { node ->
                node.loader == loader && (minecraftVersion == null || node.minecraft == minecraftVersion)
            }.collect { it.name }.sort(),
        ]
    }

    private static String taskPath(Project project, String taskName) {
        if (project == null || taskName == null) {
            return null
        }
        project.tasks.names.contains(taskName) ? taskPath(project.path, taskName) : null
    }

    private static List<String> publishTasks(Project project, String prefix, String contains) {
        if (project == null) {
            return []
        }
        project.tasks.names.findAll { name ->
            name.startsWith(prefix) && name.contains(contains)
        }.collect { name -> taskPath(project.path, name) }.sort()
    }

    private static String taskPath(String projectPath, String taskName) {
        projectPath == ':' ? ":${taskName}" : "${projectPath}:${taskName}"
    }

    private static String artifactPath(Project rootProject, Project targetProject, String loader, String minecraftVersion, Properties props) {
        if (targetProject == null) {
            return null
        }
        def projectVersion = props.getProperty('project.version') ?: rootProject.version?.toString()
        def modId = optionalProjectProperty(rootProject, 'mod.id') ?: rootProject.name
        def archiveName = "${modId}-${loader}-${projectVersion}.jar"
        rootProject.relativePath(new File(targetProject.layout.buildDirectory.get().asFile, "libs/${archiveName}"))
    }

    private static String artifactTaskName(String loader, String minecraftVersion) {
        loader == 'fabric' && minecraftVersion != null && !minecraftVersion.startsWith('26.') ? 'remapJar' : 'jar'
    }

    private static String optionalProjectProperty(Project project, String name) {
        def value = project.findProperty(name)
        if (value == null) {
            return null
        }
        def text = value.toString().trim()
        text.isEmpty() ? null : text
    }

    private static String catalogName(String minecraftVersion) {
        if (minecraftVersion == null || minecraftVersion.isBlank()) {
            return null
        }
        "libsMc${minecraftVersion.replace('.', '').replace('-', '')}"
    }

    private static String detectStonecutterVersion(Project project) {
        def settingsFile = ['settings.gradle.kts', 'settings.gradle']
            .collect { project.file(it) }
            .find { it.isFile() }
        if (settingsFile == null) {
            return null
        }

        def text = settingsFile.getText('UTF-8')
        def kotlinMatcher = text =~ /id\("dev\.kikugie\.stonecutter"\)\s+version\s+"([^"]+)"/
        if (kotlinMatcher.find()) {
            return kotlinMatcher.group(1)
        }

        def groovyMatcher = text =~ /id\s+['"]dev\.kikugie\.stonecutter['"]\s+version\s+['"]([^'"]+)['"]/
        groovyMatcher.find() ? groovyMatcher.group(1) : null
    }

    private static List<Map<String, String>> readTeaKitNodes(Project project) {
        def file = project.file('teakit.toml')
        if (!file.isFile()) {
            return []
        }

        def nodes = []
        def current = null
        file.eachLine('UTF-8') { line ->
            def section = line =~ /^\s*\[nodes\."([^"]+)"\]\s*$/
            if (section.find()) {
                current = [name: section.group(1)]
                nodes.add(current)
                return
            }

            if (line ==~ /^\s*\[.*\]\s*$/) {
                current = null
                return
            }

            if (current == null) {
                return
            }

            def property = line =~ /^\s*(loader|minecraft)\s*=\s*"([^"]+)"\s*$/
            if (property.find()) {
                current[property.group(1)] = property.group(2)
            }
        }
        nodes.findAll { it.loader != null || it.minecraft != null }
    }

    private static String taskSuffix(String name) {
        name
            .replaceAll(/[^A-Za-z0-9]+/, ' ')
            .trim()
            .split(/\s+/)
            .findAll { !it.isEmpty() }
            .collect { token -> token.substring(0, 1).toUpperCase(Locale.ROOT) + token.substring(1) }
            .join('')
    }

    private static Map<String, String> collectTeaKitSystemProperties(Project project) {
        def properties = new LinkedHashMap<String, String>()

        project.gradle.startParameter.systemPropertiesArgs.each { key, value ->
            if (key.startsWith(TEAKIT_PROPERTY_PREFIX) && value != null) {
                properties.put(key, value.toString())
            }
        }

        System.properties.stringPropertyNames()
            .findAll { it.startsWith(TEAKIT_PROPERTY_PREFIX) }
            .sort()
            .each { key ->
                def value = System.getProperty(key)
                if (value != null) {
                    properties.put(key, value)
                }
            }

        properties
    }
}
