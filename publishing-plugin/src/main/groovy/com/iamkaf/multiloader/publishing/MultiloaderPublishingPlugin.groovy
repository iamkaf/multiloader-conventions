package com.iamkaf.multiloader.publishing

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider

import java.nio.file.Files
import java.nio.file.StandardCopyOption

class MultiloaderPublishingPlugin implements Plugin<Project> {
    static final String EXTENSION_NAME = 'multiloaderPublishing'

    @Override
    void apply(Project project) {
        if (project != project.rootProject) {
            throw new IllegalStateException('com.iamkaf.multiloader.publishing must be applied to the root project only.')
        }

        def extension = project.extensions.create(EXTENSION_NAME, MultiloaderPublishingExtension, project.objects)
        configureDefaults(project, extension)

        def resolvedJars = [] as List<Map>
        def resolvedDisplayName = null as String
        def resolvedChangelog = null as String

        def assemble = project.tasks.register('publishingAssemble') {
            group = 'publishing'
            description = 'Aggregate loader artifacts for release publishing.'

            doFirst {
                project.logger.lifecycle("[Publishing] Assemble starting (dryRun=${extension.config.dryRun.get()}, releaseType=${extension.config.releaseType.get()}, displayName=${resolvedDisplayName})")
            }

            doLast {
                def outDir = project.layout.buildDirectory.dir('publishing/artifacts').get().asFile
                if (outDir.exists()) {
                    outDir.listFiles()?.each { it.delete() }
                }
                outDir.mkdirs()

                resolvedJars.each { entry ->
                    Provider<RegularFile> archiveFile = (Provider<RegularFile>) entry.archiveFile
                    def jarFile = archiveFile.get().asFile
                    if (!jarFile.exists()) {
                        throw new IllegalStateException("[Publishing] Expected jar does not exist for ${entry.project.path}: ${jarFile}")
                    }

                    def dest = new File(outDir, jarFile.name)
                    Files.copy(jarFile.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    project.logger.lifecycle("[Publishing] Copied ${entry.project.path} -> ${dest}")
                }
            }
        }

        def publish = project.tasks.register('publishingPublish') {
            group = 'publishing'
            description = 'Publish aggregated loader artifacts to Modrinth and CurseForge.'

            dependsOn(assemble)

            doLast {
                def artifactsDir = project.layout.buildDirectory.dir('publishing/artifacts').get().asFile
                if (!artifactsDir.exists()) {
                    throw new IllegalStateException("[Publishing] No artifacts directory found at ${artifactsDir}. Run publishingAssemble first.")
                }

                def gameVersions = requiredCsv(project, 'game_versions')
                MultiloaderPublishRules.requireNonEmpty('game_versions', gameVersions)

                def jarFiles = (artifactsDir.listFiles({ File file -> file.name.endsWith('.jar') } as FileFilter) ?: [] as File[]).toList()
                MultiloaderPublishRules.requireNonEmpty('assembled artifacts', jarFiles)

                def jarLoaders = [:] as Map<File, List<String>>
                def loaders = [] as List<String>
                jarFiles.each { jar ->
                    def inferred = inferLoaders(jar)
                    jarLoaders[jar] = inferred
                    loaders.addAll(inferred)
                }
                loaders = loaders.unique()

                if (!extension.publish.disableEmptyJarCheck.get()) {
                    jarLoaders.each { jar, inferred ->
                        if (inferred.isEmpty()) {
                            throw new IllegalStateException("[Publishing] Could not infer a loader from artifact filename ${jar.name}")
                        }
                        MultiloaderPublishRules.checkEmptyJar(jar, inferred)
                    }
                }

                def modrinthGameVersions = MultiloaderPublishRules.modrinthNormalizeGameVersions(gameVersions)
                def modrinthLoaders = MultiloaderPublishRules.modrinthNormalizeLoaders(loaders)
                def curseGameVersions = MultiloaderPublishRules.curseNormalizeGameVersions(gameVersions)
                def curseLoaders = MultiloaderPublishRules.curseNormalizeLoaders(loaders)
                def isDryRun = extension.config.dryRun.get()

                def enabledDestinations = [] as List<String>
                if (extension.publish.modrinth.id.isPresent()) {
                    if (!isDryRun && !extension.publish.modrinth.token.isPresent()) {
                        throw new IllegalStateException('[Publishing] Modrinth publishing requires publish.modrinth.token unless dryRun=true')
                    }
                    enabledDestinations.add('modrinth')
                }
                if (extension.publish.curseforge.id.isPresent()) {
                    if (!isDryRun && !extension.publish.curseforge.token.isPresent()) {
                        throw new IllegalStateException('[Publishing] CurseForge publishing requires publish.curseforge.token unless dryRun=true')
                    }
                    enabledDestinations.add('curseforge')
                }

                project.logger.lifecycle("[Publishing] Destinations=${enabledDestinations}")
                project.logger.lifecycle("[Publishing] loaders=${loaders}")
                project.logger.lifecycle("[Publishing] gameVersions=${gameVersions}")

                if (enabledDestinations.contains('modrinth')) {
                    publishModrinth(project, extension, resolvedDisplayName, resolvedChangelog, isDryRun, modrinthGameVersions, modrinthLoaders, jarFiles)
                }
                if (enabledDestinations.contains('curseforge')) {
                    publishCurseForge(project, extension, resolvedDisplayName, resolvedChangelog, isDryRun, curseGameVersions, curseLoaders, jarFiles, jarLoaders)
                }

                if (isDryRun) {
                    project.logger.lifecycle('[Publishing] dryRun=true -> skipping live publish')
                }
            }
        }

        project.tasks.register('publishingRelease') {
            group = 'publishing'
            description = 'Assemble and publish release artifacts.'
            dependsOn(publish)
        }

        project.gradle.projectsEvaluated {
            resolvedDisplayName = resolveDisplayName(project, extension)
            resolvedChangelog = resolveChangelog(project, extension)

            [
                [id: 'fabric', enabled: extension.loaders.fabric.enabled.get(), path: ':fabric', jarTask: 'remapJar', fallbackJarTask: 'jar', extraDepends: []],
                [id: 'forge', enabled: extension.loaders.forge.enabled.get(), path: ':forge', jarTask: 'jar', fallbackJarTask: null, extraDepends: ['reobfJar']],
                [id: 'neoforge', enabled: extension.loaders.neoforge.enabled.get(), path: ':neoforge', jarTask: 'jar', fallbackJarTask: null, extraDepends: []],
            ].each { spec ->
                if (!(spec.enabled as Boolean)) {
                    return
                }

                def subproject = project.findProject(spec.path as String)
                if (subproject == null) {
                    return
                }

                def jarOutput = findJarOutput(subproject, spec.jarTask as String, spec.fallbackJarTask as String)
                if (jarOutput == null) {
                    throw new IllegalStateException("[Publishing] Could not locate a jar task for ${subproject.path}")
                }

                assemble.configure { task ->
                    (spec.extraDepends as List<String>).each { taskName ->
                        def extraTask = subproject.tasks.findByName(taskName)
                        if (extraTask != null) {
                            task.dependsOn(extraTask)
                        }
                    }
                    task.dependsOn(jarOutput.task)
                }

                resolvedJars.add([
                    project    : subproject,
                    archiveFile: jarOutput.archiveFile,
                ])
            }
        }
    }

    private static void configureDefaults(Project project, MultiloaderPublishingExtension extension) {
        extension.config.dryRun.convention(booleanProperty(project, 'dry_run', false))
        extension.config.releaseType.convention(optionalProperty(project, 'release_type') ?: 'release')
        extension.publish.curseforge.environment.convention(optionalProperty(project, 'mod_environment') ?: 'both')
        extension.publish.curseforge.javaVersions.convention(javaVersionsFor(requiredProperty(project, 'java_version')))

        def modrinthId = optionalProperty(project, 'modrinth_id')
        if (modrinthId) {
            extension.publish.modrinth.id.convention(modrinthId)
        }

        def curseId = optionalProperty(project, 'curse_id')
        if (curseId) {
            extension.publish.curseforge.id.convention(curseId)
        }

        def modrinthToken = System.getenv('MODRINTH_TOKEN')
        if (modrinthToken) {
            extension.publish.modrinth.token.convention(modrinthToken)
        }

        def curseToken = System.getenv('CURSEFORGE_TOKEN')
        if (curseToken) {
            extension.publish.curseforge.token.convention(curseToken)
        }

        requiredCsv(project, 'mod_modrinth_depends').each { dependency ->
            extension.publish.modrinth.dependencies.required(dependency)
        }
        requiredCsv(project, 'mod_curse_depends').each { dependency ->
            extension.publish.curseforge.dependencies.required(dependency)
        }

        def defaultChangelog = project.file('../changelog.md').exists() ? '../changelog.md' : 'changelog.md'
        extension.metadata.changelogFile.convention(defaultChangelog)
    }

    private static List<String> inferLoaders(File file) {
        def name = file.name.toLowerCase(Locale.ROOT)
        def inferred = [] as List<String>
        if (name.contains('-fabric-')) {
            inferred.add('fabric')
        }
        if (name.contains('-forge-') && !name.contains('-neoforge-')) {
            inferred.add('forge')
        }
        if (name.contains('-neoforge-')) {
            inferred.add('neoforge')
        }
        inferred
    }

    private static void publishModrinth(Project project, MultiloaderPublishingExtension extension, String displayName, String changelog, boolean dryRun, List<String> gameVersions, List<String> loaders, List<File> jarFiles) {
        def token = dryRun ? (extension.publish.modrinth.token.getOrNull() ?: '') : extension.publish.modrinth.token.get()
        def client = new ModrinthPublishingClient(token)
        def projectId = dryRun
            ? extension.publish.modrinth.id.get()
            : client.resolveProjectId(extension.publish.modrinth.id.get())

        def dependencies = [] as List<Map>
        def addDependencies = { List<String> slugs, String type ->
            slugs.each { slug ->
                def dependencyId = dryRun ? slug : client.resolveProjectId(slug)
                dependencies.add([
                    project_id     : dependencyId,
                    dependency_type: type,
                ])
            }
        }
        addDependencies(extension.publish.modrinth.dependencies.required.getOrElse([]), 'required')
        addDependencies(extension.publish.modrinth.dependencies.optional.getOrElse([]), 'optional')
        addDependencies(extension.publish.modrinth.dependencies.incompatible.getOrElse([]), 'incompatible')
        addDependencies(extension.publish.modrinth.dependencies.embedded.getOrElse([]), 'embedded')

        def body = [
            project_id    : projectId,
            file_parts    : jarFiles.collect { it.name },
            version_number: project.version.toString(),
            name          : displayName,
            changelog     : changelog,
            dependencies  : dependencies,
            game_versions : gameVersions,
            version_type  : extension.config.releaseType.get(),
            loaders       : loaders,
            featured      : true,
            status        : extension.publish.isManualRelease.get() ? 'draft' : 'listed',
        ]

        def parts = ModrinthPublishingClient.filePartsFrom(jarFiles)
        if (dryRun) {
            def info = client.createVersionMultipart(body, parts, true)
            project.logger.lifecycle("[Publishing] Modrinth dryRun payload: ${groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(info))}")
            return
        }

        def response = client.createVersionMultipart(body, parts, false)
        project.logger.lifecycle("[Publishing] Modrinth upload ok: version_id=${response.id}, version_number=${response.version_number}")
    }

    private static void publishCurseForge(Project project, MultiloaderPublishingExtension extension, String displayName, String changelog, boolean dryRun, List<String> gameVersions, List<String> loaders, List<File> jarFiles, Map<File, List<String>> jarLoaders) {
        def curseTags = [] as List<String>
        curseTags.addAll(gameVersions)
        curseTags.addAll(environmentTags(extension.publish.curseforge.environment.get()))
        curseTags.addAll(extension.publish.curseforge.javaVersions.getOrElse([]).collect { normalizeJavaTag(it) })
        curseTags.addAll(loaders)

        def client = dryRun ? null : new CurseForgePublishingClient(extension.publish.curseforge.token.get())
        def gameVersionIds = dryRun ? curseTags : client.resolveGameVersionIds(curseTags)

        def relations = [] as List<Map>
        def addRelations = { List<String> slugs, String type ->
            slugs.each { slug ->
                relations.add([slug: slug, type: type])
            }
        }
        addRelations(extension.publish.curseforge.dependencies.required.getOrElse([]), 'requiredDependency')
        addRelations(extension.publish.curseforge.dependencies.optional.getOrElse([]), 'optionalDependency')
        addRelations(extension.publish.curseforge.dependencies.incompatible.getOrElse([]), 'incompatible')
        addRelations(extension.publish.curseforge.dependencies.embedded.getOrElse([]), 'embeddedLibrary')

        jarFiles.each { file ->
            def inferred = jarLoaders[file] ?: []
            def suffix = inferred.isEmpty() ? null : inferred.first()
            def fileDisplayName = suffix == null ? displayName : "${displayName} (${suffix})"
            def metadata = [
                changelog              : changelog,
                changelogType          : 'markdown',
                displayName            : fileDisplayName,
                gameVersions           : gameVersionIds,
                releaseType            : extension.config.releaseType.get(),
                isMarkedForManualRelease: extension.publish.isManualRelease.get(),
            ]
            if (!relations.isEmpty()) {
                metadata.relations = [projects: relations]
            }

            if (dryRun) {
                project.logger.lifecycle("[Publishing] CurseForge dryRun payload (${file.name}): ${groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson([projectId: extension.publish.curseforge.id.get(), gameVersions: gameVersionIds, metadata: metadata]))}")
            } else {
                def response = client.uploadFile(Long.parseLong(extension.publish.curseforge.id.get()), metadata, file)
                project.logger.lifecycle("[Publishing] CurseForge upload ok: file_id=${response.id} (${file.name})")
            }
        }
    }

    private static List<String> environmentTags(String environment) {
        switch ((environment ?: 'both').toLowerCase(Locale.ROOT)) {
            case 'client':
                return ['client']
            case 'server':
                return ['server']
            default:
                return ['client', 'server']
        }
    }

    private static String normalizeJavaTag(String value) {
        def normalized = value.toString().trim()
        normalized.toLowerCase(Locale.ROOT).startsWith('java ') ? normalized : "Java ${normalized}"
    }

    private static JarOutput findJarOutput(Project project, String preferredTaskName, String fallbackTaskName) {
        def preferred = project.tasks.findByName(preferredTaskName)
        if (preferred != null) {
            return asJarOutput(project, preferred)
        }

        if (fallbackTaskName != null) {
            def fallback = project.tasks.findByName(fallbackTaskName)
            if (fallback != null) {
                return asJarOutput(project, fallback)
            }
        }

        null
    }

    private static JarOutput asJarOutput(Project project, Task task) {
        if (!task.hasProperty('archiveFile')) {
            throw new IllegalStateException("[Publishing] Task ${project.path}:${task.name} does not expose archiveFile")
        }
        new JarOutput(task, (Provider<RegularFile>) task.property('archiveFile'))
    }

    private static String resolveDisplayName(Project project, MultiloaderPublishingExtension extension) {
        if (extension.metadata.displayName.isPresent()) {
            return extension.metadata.displayName.get()
        }
        "${requiredProperty(project, 'mod_name')} ${project.version}"
    }

    private static String resolveChangelog(Project project, MultiloaderPublishingExtension extension) {
        if (extension.metadata.changelogText.isPresent()) {
            return extension.metadata.changelogText.get()
        }

        def relPath = extension.metadata.changelogFile.orNull
        if (relPath == null || relPath.toString().trim().isEmpty()) {
            throw new IllegalStateException('[Publishing] No changelog configured')
        }

        def file = project.file(relPath)
        if (!file.exists()) {
            throw new IllegalStateException("[Publishing] Changelog file not found: ${file}")
        }

        def extracted = extractHeaderLatestFooterFromChangelog(file.getText('UTF-8'))
        if (extracted == null || extracted.trim().isEmpty()) {
            throw new IllegalStateException("[Publishing] Failed to extract a changelog section from ${file}")
        }
        extracted
    }

    private static String extractHeaderLatestFooterFromChangelog(String completeChangelog) {
        def headerMatcher = (completeChangelog =~ /(?ms)\A.*?(?=^## \d+\.\d+\.\d+)/)
        if (!headerMatcher.find()) {
            return null
        }
        def latestMatcher = (completeChangelog =~ /(?ms)^## \d+\.\d+\.\d+[\s\S]*?(?=^## (?:\d+\.\d+\.\d+|[^0-9]|$))/)
        if (!latestMatcher.find()) {
            return null
        }
        def footerMatcher = (completeChangelog =~ /(?ms)^## Types of changes[\s\S]*/)
        if (!footerMatcher.find()) {
            return null
        }

        headerMatcher.group(0) + latestMatcher.group(0) + footerMatcher.group(0)
    }

    private static String requiredProperty(Project project, String name) {
        def value = project.findProperty(name)
        if (value == null || value.toString().trim().isEmpty()) {
            throw new IllegalStateException("[Publishing] Missing required Gradle property '${name}'")
        }
        value.toString()
    }

    private static String optionalProperty(Project project, String name) {
        def value = project.findProperty(name)
        if (value == null) {
            return null
        }
        def trimmed = value.toString().trim()
        trimmed.isEmpty() ? null : trimmed
    }

    private static boolean booleanProperty(Project project, String name, boolean defaultValue) {
        def value = optionalProperty(project, name)
        value == null ? defaultValue : Boolean.parseBoolean(value)
    }

    private static List<String> requiredCsv(Project project, String name) {
        def value = optionalProperty(project, name)
        if (value == null) {
            return []
        }
        value.split(',').collect { it.trim() }.findAll { !it.isEmpty() }
    }

    private static List<String> javaVersionsFor(String javaVersion) {
        def version = Integer.parseInt(javaVersion)
        version >= 21 ? ['21', '22'] : [javaVersion]
    }

    private static final class JarOutput {
        final Task task
        final Provider<RegularFile> archiveFile

        JarOutput(Task task, Provider<RegularFile> archiveFile) {
            this.task = task
            this.archiveFile = archiveFile
        }
    }
}
