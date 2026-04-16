package com.iamkaf.multiloader.publishing

import me.hypherionmc.curseupload.CurseUploadApi
import me.hypherionmc.curseupload.constants.CurseChangelogType
import me.hypherionmc.curseupload.constants.CurseReleaseType
import me.hypherionmc.curseupload.requests.CurseArtifact
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

        def resolvedPublications = [:] as Map<String, PublicationSpec>
        def resolvedChangelog = null as String

        def assembleAll = project.tasks.register('publishingAssemble') {
            group = 'publishing'
            description = 'Aggregate all enabled artifacts for release publishing.'
        }

        def publishCurseforgeAll = project.tasks.register('publishCurseforge') {
            group = 'publishing'
            description = 'Upload all enabled artifacts to CurseForge.'
        }

        def publishModrinthAll = project.tasks.register('publishModrinth') {
            group = 'publishing'
            description = 'Upload all enabled artifacts to Modrinth.'
        }

        def publishAll = project.tasks.register('publishMod') {
            group = 'publishing'
            description = 'Upload all enabled artifacts to configured platforms.'
            dependsOn(publishCurseforgeAll, publishModrinthAll)
        }

        project.tasks.register('publishingPublish') {
            group = 'publishing'
            description = 'Compatibility aggregate for publishMod.'
            dependsOn(publishAll)
        }

        project.tasks.register('publishingRelease') {
            group = 'publishing'
            description = 'Compatibility aggregate for publishMod.'
            dependsOn(publishAll)
        }

        project.gradle.projectsEvaluated {
            resolvedChangelog = resolveChangelog(project, extension)

            configuredPublications(project, extension).each { publicationConfig ->
                def taskSuffix = taskSuffix(publicationConfig.name)

                if (!publicationConfig.enabled) {
                    def assembleTask = project.tasks.register("publishingAssemble${taskSuffix}") {
                        group = 'publishing'
                        description = "Aggregate the ${publicationConfig.name} artifact for release publishing."
                        onlyIf { false }
                    }
                    assembleAll.configure { dependsOn(assembleTask) }

                    def curseTask = project.tasks.register("publishCurseforge${taskSuffix}") {
                        group = 'publishing'
                        description = "Upload the ${publicationConfig.name} artifact to CurseForge."
                        dependsOn(assembleTask)
                        onlyIf { false }
                    }
                    publishCurseforgeAll.configure { dependsOn(curseTask) }

                    def modrinthTask = project.tasks.register("publishModrinth${taskSuffix}") {
                        group = 'publishing'
                        description = "Upload the ${publicationConfig.name} artifact to Modrinth."
                        dependsOn(assembleTask)
                        onlyIf { false }
                    }
                    publishModrinthAll.configure { dependsOn(modrinthTask) }
                    return
                }

                def targetProject = project.findProject(publicationConfig.projectPath)
                if (targetProject == null) {
                    throw new IllegalStateException("[Publishing] Unknown publication project path '${publicationConfig.projectPath}' for '${publicationConfig.name}'")
                }

                def jarOutput = findJarOutput(targetProject, publicationConfig.artifactTask, publicationConfig.fallbackArtifactTask)
                if (jarOutput == null) {
                    throw new IllegalStateException("[Publishing] Could not locate archive task for ${targetProject.path} (preferred=${publicationConfig.artifactTask}, fallback=${publicationConfig.fallbackArtifactTask})")
                }

                def loaders = publicationConfig.loaders ?: inferLoaders(targetProject)
                MultiloaderPublishRules.requireNonEmpty("${publicationConfig.name}.loaders", loaders)

                def gameVersions = publicationConfig.gameVersions ?: inferGameVersions(targetProject)
                MultiloaderPublishRules.requireNonEmpty("${publicationConfig.name}.gameVersions", gameVersions)

                def javaVersions = publicationConfig.javaVersions ?: [requiredProperty(targetProject, 'project.java')]
                MultiloaderPublishRules.requireNonEmpty("${publicationConfig.name}.javaVersions", javaVersions)

                def spec = new PublicationSpec(
                    publicationConfig.name,
                    taskSuffix,
                    targetProject,
                    jarOutput.archiveFile,
                    loaders,
                    gameVersions,
                    javaVersions,
                    publicationConfig.displayName,
                )
                resolvedPublications[publicationConfig.name] = spec

                def assembleTask = project.tasks.register("publishingAssemble${taskSuffix}") {
                    group = 'publishing'
                    description = "Aggregate the ${publicationConfig.name} artifact for release publishing."

                    publicationConfig.buildTasks.each { taskName ->
                        def extraTask = targetProject.tasks.findByName(taskName)
                        if (extraTask != null) {
                            dependsOn(extraTask)
                        }
                    }
                    dependsOn(jarOutput.task)

                    doFirst {
                        project.logger.lifecycle("[Publishing] Assemble starting for ${publicationConfig.name} (dryRun=${extension.config.dryRun.get()}, releaseType=${extension.config.releaseType.get()}, loaders=${spec.loaders}, gameVersions=${spec.gameVersions})")
                    }

                    doLast {
                        def source = spec.archiveFile.get().asFile
                        if (!source.exists()) {
                            throw new IllegalStateException("[Publishing] Expected jar does not exist for ${spec.project.path}: ${source}")
                        }

                        def dest = stagedArtifactFile(project, spec)
                        dest.parentFile.mkdirs()
                        Files.copy(source.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)

                        if (!extension.publish.disableEmptyJarCheck.get()) {
                            MultiloaderPublishRules.checkEmptyJar(dest, spec.loaders)
                        }

                        project.logger.lifecycle("[Publishing] Copied ${spec.project.path} -> ${dest}")
                    }
                }

                assembleAll.configure { dependsOn(assembleTask) }

                def curseTask = project.tasks.register("publishCurseforge${taskSuffix}") {
                    group = 'publishing'
                    description = "Upload the ${publicationConfig.name} artifact to CurseForge."
                    dependsOn(assembleTask)
                    onlyIf { extension.publish.curseforge.id.isPresent() }

                    doLast {
                        def isDryRun = extension.config.dryRun.get()
                        if (!isDryRun && !extension.publish.curseforge.token.isPresent()) {
                            throw new IllegalStateException('[Publishing] CurseForge publishing requires publish.curseforge.token unless dryRun=true')
                        }

                        def curseGameVersions = MultiloaderPublishRules.curseNormalizeGameVersions(spec.gameVersions)
                        project.logger.lifecycle("[Publishing] Destinations=[curseforge]")
                        project.logger.lifecycle("[Publishing] loaders=${spec.loaders}")
                        project.logger.lifecycle("[Publishing] gameVersions=${spec.gameVersions}")

                        publishCurseForge(project, extension, resolvedChangelog, isDryRun, curseGameVersions, stagedArtifactFile(project, spec), spec)

                        if (isDryRun) {
                            project.logger.lifecycle('[Publishing] dryRun=true -> skipping live publish')
                        }
                    }
                }

                def modrinthTask = project.tasks.register("publishModrinth${taskSuffix}") {
                    group = 'publishing'
                    description = "Upload the ${publicationConfig.name} artifact to Modrinth."
                    dependsOn(assembleTask)
                    onlyIf { extension.publish.modrinth.id.isPresent() }

                    doLast {
                        def isDryRun = extension.config.dryRun.get()
                        if (!isDryRun && !extension.publish.modrinth.token.isPresent()) {
                            throw new IllegalStateException('[Publishing] Modrinth publishing requires publish.modrinth.token unless dryRun=true')
                        }

                        def modrinthGameVersions = MultiloaderPublishRules.modrinthNormalizeGameVersions(spec.gameVersions)
                        project.logger.lifecycle("[Publishing] Destinations=[modrinth]")
                        project.logger.lifecycle("[Publishing] loaders=${spec.loaders}")
                        project.logger.lifecycle("[Publishing] gameVersions=${spec.gameVersions}")

                        publishModrinth(project, extension, resolvedChangelog, isDryRun, modrinthGameVersions, stagedArtifactFile(project, spec), spec)

                        if (isDryRun) {
                            project.logger.lifecycle('[Publishing] dryRun=true -> skipping live publish')
                        }
                    }
                }

                publishCurseforgeAll.configure { dependsOn(curseTask) }
                publishModrinthAll.configure { dependsOn(modrinthTask) }
            }
        }
    }

    private static List<PublicationConfig> configuredPublications(Project project, MultiloaderPublishingExtension extension) {
        def explicit = extension.publications.collect { publication ->
            new PublicationConfig(
                publication.name,
                publication.enabled.get(),
                requiredPublicationPath(publication),
                publication.artifactTask.get(),
                publication.fallbackArtifactTask.orNull,
                publication.buildTasks.getOrElse([]),
                publication.loaders.getOrElse([]),
                publication.gameVersions.getOrElse([]),
                publication.javaVersions.getOrElse([]),
                publication.displayName.orNull,
            )
        }
        if (!explicit.isEmpty()) {
            return explicit
        }

        def fabricJarTask = projectProperty(project, 'project.minecraft')?.startsWith('26.') ? 'jar' : 'remapJar'
        def fabricFallbackJarTask = projectProperty(project, 'project.minecraft')?.startsWith('26.') ? null : 'jar'
        def enabledLoaders = configuredLoaders(project)

        [
            new PublicationConfig('fabric', enabledLoaders.contains('fabric') && extension.loaders.fabric.enabled.get(), ':fabric', fabricJarTask, fabricFallbackJarTask, [], ['fabric'], [], [], null),
            new PublicationConfig('forge', enabledLoaders.contains('forge') && extension.loaders.forge.enabled.get(), ':forge', 'jar', null, ['reobfJar'], ['forge'], [], [], null),
            new PublicationConfig('neoforge', enabledLoaders.contains('neoforge') && extension.loaders.neoforge.enabled.get(), ':neoforge', 'jar', null, [], ['neoforge'], [], [], null),
        ]
    }

    private static String requiredPublicationPath(MultiloaderPublishingExtension.Publication publication) {
        if (!publication.projectPath.present || publication.projectPath.get().trim().isEmpty()) {
            throw new IllegalStateException("[Publishing] Publication '${publication.name}' must set projectPath")
        }
        publication.projectPath.get().trim()
    }

    private static void configureDefaults(Project project, MultiloaderPublishingExtension extension) {
        extension.config.dryRun.convention(booleanProperty(project, 'publish.dry-run', false))
        extension.config.releaseType.convention(optionalProperty(project, 'publish.release-type') ?: 'release')
        extension.publish.curseforge.environment.convention(curseEnvironment(project))

        def modrinthId = optionalProperty(project, 'publish.modrinth.id')
        if (modrinthId) {
            extension.publish.modrinth.id.convention(modrinthId)
        }

        def curseId = optionalProperty(project, 'publish.curseforge.id')
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

        requiredCsv(project, 'dependencies.modrinth.required').each { dependency ->
            extension.publish.modrinth.dependencies.required(dependency)
        }
        requiredCsv(project, 'dependencies.curseforge.required').each { dependency ->
            extension.publish.curseforge.dependencies.required(dependency)
        }

        def defaultChangelog = project.file('../changelog.md').exists() ? '../changelog.md' : 'changelog.md'
        extension.metadata.changelogFile.convention(defaultChangelog)
    }

    private static File stagedArtifactFile(Project project, PublicationSpec publication) {
        def source = publication.archiveFile.get().asFile
        project.layout.buildDirectory.file("publishing/artifacts/${source.name}").get().asFile
    }

    private static void publishModrinth(Project project, MultiloaderPublishingExtension extension, String changelog, boolean dryRun, List<String> gameVersions, File file, PublicationSpec publication) {
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

        def normalizedLoaders = MultiloaderPublishRules.modrinthNormalizeLoaders(publication.loaders)
        if (normalizedLoaders.isEmpty()) {
            throw new IllegalStateException("[Publishing] Could not infer Modrinth loaders for ${file.name}")
        }

        def body = [
            project_id    : projectId,
            file_parts    : [file.name],
            version_number: publication.project.version.toString(),
            name          : publicationDisplayName(file, publication),
            changelog     : changelog,
            dependencies  : dependencies,
            game_versions : gameVersions,
            version_type  : extension.config.releaseType.get(),
            loaders       : normalizedLoaders,
            featured      : true,
            status        : extension.publish.isManualRelease.get() ? 'draft' : 'listed',
        ]

        def parts = ModrinthPublishingClient.filePartsFrom([file])
        if (dryRun) {
            def info = client.createVersionMultipart(body, parts, true)
            project.logger.lifecycle("[Publishing] Modrinth dryRun payload (${file.name}): ${groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(info))}")
        } else {
            def response = client.createVersionMultipart(body, parts, false)
            project.logger.lifecycle("[Publishing] Modrinth upload ok: version_id=${response.id}, version_number=${response.version_number} (${file.name})")
        }
    }

    private static void publishCurseForge(Project project, MultiloaderPublishingExtension extension, String changelog, boolean dryRun, List<String> gameVersions, File file, PublicationSpec publication) {
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

        def curseTags = [] as List<String>
        curseTags.addAll(gameVersions)
        curseTags.addAll(environmentTags(extension.publish.curseforge.environment.get()))
        curseTags.addAll(publication.javaVersions.collect { normalizeJavaTag(it) })
        curseTags.addAll(MultiloaderPublishRules.curseNormalizeLoaders(publication.loaders))

        if (curseTags.isEmpty()) {
            throw new IllegalStateException("[Publishing] Could not infer CurseForge loader tags for ${file.name}")
        }

        def metadata = [
            changelog               : changelog,
            changelogType           : 'markdown',
            displayName             : publicationDisplayName(file, publication),
            gameVersions            : curseTags,
            releaseType             : extension.config.releaseType.get(),
            isMarkedForManualRelease: extension.publish.isManualRelease.get(),
        ]
        if (!relations.isEmpty()) {
            metadata.relations = [projects: relations]
        }

        if (dryRun) {
            project.logger.lifecycle("[Publishing] CurseForge dryRun payload (${file.name}): ${groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson([projectId: extension.publish.curseforge.id.get(), gameVersions: curseTags, metadata: metadata]))}")
        } else {
            def api = new CurseUploadApi(extension.publish.curseforge.token.get())
            api.setDebug(false)

            def artifact = new CurseArtifact(file, Long.parseLong(extension.publish.curseforge.id.get()))
                .changelog(changelog)
                .changelogType(CurseChangelogType.MARKDOWN)
                .displayName(publicationDisplayName(file, publication))
                .releaseType(CurseReleaseType.valueOf(extension.config.releaseType.get().toUpperCase(Locale.ROOT)))

            curseTags.each { tag ->
                artifact.addGameVersion(tag)
            }

            relations.each { relation ->
                switch (relation.type) {
                    case 'requiredDependency':
                        artifact.requirement(relation.slug as String)
                        break
                    case 'optionalDependency':
                        artifact.optional(relation.slug as String)
                        break
                    case 'incompatible':
                        artifact.incompatibility(relation.slug as String)
                        break
                    case 'embeddedLibrary':
                        artifact.embedded(relation.slug as String)
                        break
                    default:
                        throw new IllegalStateException("[Publishing] Unsupported CurseForge relation type: ${relation.type}")
                }
            }

            if (extension.publish.isManualRelease.get()) {
                artifact.manualRelease()
            }

            api.upload(artifact)
            project.logger.lifecycle("[Publishing] CurseForge upload ok: ${file.name}")
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

    private static String publicationDisplayName(File file, PublicationSpec publication) {
        publication.displayName ?: file.name.replaceFirst(/\.jar$/, '')
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

    private static List<String> inferLoaders(Project project) {
        def loader = optionalProperty(project, 'loader')
        if (loader != null) {
            return [loader]
        }
        def name = project.name.toLowerCase(Locale.ROOT)
        ['fabric', 'forge', 'neoforge'].contains(name) ? [name] : []
    }

    private static List<String> inferGameVersions(Project project) {
        def configured = optionalProperty(project, 'publish.game-versions')
        if (configured != null) {
            return csv(configured == 'auto' ? requiredProperty(project, 'project.minecraft') : configured)
        }
        [requiredProperty(project, 'project.minecraft')]
    }

    private static String requiredProperty(Project project, String name) {
        def value = project.findProperty(name)
        if (value == null || value.toString().trim().isEmpty()) {
            throw new IllegalStateException("[Publishing] Missing required Gradle property '${name}' for ${project.path}")
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

    private static String projectProperty(Project project, String name) {
        optionalProperty(project, name)
    }

    private static boolean booleanProperty(Project project, String name, boolean defaultValue) {
        def value = optionalProperty(project, name)
        value == null ? defaultValue : Boolean.parseBoolean(value)
    }

    private static List<String> requiredCsv(Project project, String name) {
        def value = optionalProperty(project, name)
        value == null ? [] : csv(value)
    }

    private static List<String> csv(String value) {
        value.split(',').collect { it.trim() }.findAll { !it.isEmpty() }
    }

    private static List<String> configuredLoaders(Project project) {
        def configured = requiredCsv(project, 'project.enabled-loaders')
        configured.isEmpty() ? ['fabric', 'forge', 'neoforge'] : configured
    }

    private static String curseEnvironment(Project project) {
        def client = (optionalProperty(project, 'environments.client') ?: 'required').toLowerCase(Locale.ROOT)
        def server = (optionalProperty(project, 'environments.server') ?: 'required').toLowerCase(Locale.ROOT)

        if (client == 'required' && server == 'required') {
            return 'both'
        }
        if (client == 'required' && server != 'required') {
            return 'client'
        }
        if (server == 'required' && client != 'required') {
            return 'server'
        }
        return 'both'
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

    private static final class PublicationConfig {
        final String name
        final boolean enabled
        final String projectPath
        final String artifactTask
        final String fallbackArtifactTask
        final List<String> buildTasks
        final List<String> loaders
        final List<String> gameVersions
        final List<String> javaVersions
        final String displayName

        PublicationConfig(String name, boolean enabled, String projectPath, String artifactTask, String fallbackArtifactTask, List<String> buildTasks, List<String> loaders, List<String> gameVersions, List<String> javaVersions, String displayName) {
            this.name = name
            this.enabled = enabled
            this.projectPath = projectPath
            this.artifactTask = artifactTask
            this.fallbackArtifactTask = fallbackArtifactTask
            this.buildTasks = buildTasks
            this.loaders = loaders
            this.gameVersions = gameVersions
            this.javaVersions = javaVersions
            this.displayName = displayName
        }
    }

    private static final class PublicationSpec {
        final String name
        final String taskSuffix
        final Project project
        final Provider<RegularFile> archiveFile
        final List<String> loaders
        final List<String> gameVersions
        final List<String> javaVersions
        final String displayName

        PublicationSpec(String name, String taskSuffix, Project project, Provider<RegularFile> archiveFile, List<String> loaders, List<String> gameVersions, List<String> javaVersions, String displayName) {
            this.name = name
            this.taskSuffix = taskSuffix
            this.project = project
            this.archiveFile = archiveFile
            this.loaders = loaders
            this.gameVersions = gameVersions
            this.javaVersions = javaVersions
            this.displayName = displayName
        }
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
