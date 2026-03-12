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
        def resolvedDisplayName = null as String
        def resolvedChangelog = null as String
        def loaderSpecs = loaderSpecs(extension)
        def capitalize = { String value -> value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1) }

        def assembleAll = project.tasks.register('publishingAssemble') {
            group = 'publishing'
            description = 'Aggregate all enabled loader artifacts for release publishing.'
        }

        def publishCurseforgeAll = project.tasks.register('publishCurseforge') {
            group = 'publishing'
            description = 'Upload all enabled loader artifacts to CurseForge.'
        }

        def publishModrinthAll = project.tasks.register('publishModrinth') {
            group = 'publishing'
            description = 'Upload all enabled loader artifacts to Modrinth.'
        }

        def publishAll = project.tasks.register('publishMod') {
            group = 'publishing'
            description = 'Upload all enabled loader artifacts to configured platforms.'
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

        loaderSpecs.each { loaderId, spec ->
            def suffix = capitalize(loaderId)

            def assembleTask = project.tasks.register("publishingAssemble${suffix}") {
                group = 'publishing'
                description = "Aggregate the ${loaderId} artifact for release publishing."

                doFirst {
                    def publication = requiredPublication(resolvedPublications, loaderId)
                    project.logger.lifecycle("[Publishing] Assemble starting for ${loaderId} (dryRun=${extension.config.dryRun.get()}, releaseType=${extension.config.releaseType.get()}, displayName=${publicationDisplayName(project, loaderId)})")
                }

                doLast {
                    def publication = requiredPublication(resolvedPublications, loaderId)
                    def source = publication.archiveFile.get().asFile
                    if (!source.exists()) {
                        throw new IllegalStateException("[Publishing] Expected jar does not exist for ${publication.project.path}: ${source}")
                    }

                    def dest = stagedArtifactFile(project, publication)
                    dest.parentFile.mkdirs()
                    Files.copy(source.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    project.logger.lifecycle("[Publishing] Copied ${publication.project.path} -> ${dest}")
                }
            }

            assembleAll.configure { dependsOn(assembleTask) }

            def curseTask = project.tasks.register("publishCurseforge${suffix}") {
                group = 'publishing'
                description = "Upload the ${loaderId} artifact to CurseForge."
                dependsOn(assembleTask)
                onlyIf { extension.publish.curseforge.id.isPresent() }

                doLast {
                    def publication = requiredPublication(resolvedPublications, loaderId)
                    def isDryRun = extension.config.dryRun.get()
                    if (!isDryRun && !extension.publish.curseforge.token.isPresent()) {
                        throw new IllegalStateException('[Publishing] CurseForge publishing requires publish.curseforge.token unless dryRun=true')
                    }

                    def gameVersions = requiredCsv(project, 'publish.game-versions')
                    MultiloaderPublishRules.requireNonEmpty('publish.game-versions', gameVersions)
                    def curseGameVersions = MultiloaderPublishRules.curseNormalizeGameVersions(gameVersions)

                    project.logger.lifecycle("[Publishing] Destinations=[curseforge]")
                    project.logger.lifecycle("[Publishing] loaders=${publication.loaders}")
                    project.logger.lifecycle("[Publishing] gameVersions=${gameVersions}")

                    publishCurseForge(project, extension, resolvedDisplayName, resolvedChangelog, isDryRun, curseGameVersions, stagedArtifactFile(project, publication), publication.loaders)

                    if (isDryRun) {
                        project.logger.lifecycle('[Publishing] dryRun=true -> skipping live publish')
                    }
                }
            }

            def modrinthTask = project.tasks.register("publishModrinth${suffix}") {
                group = 'publishing'
                description = "Upload the ${loaderId} artifact to Modrinth."
                dependsOn(assembleTask)
                onlyIf { extension.publish.modrinth.id.isPresent() }

                doLast {
                    def publication = requiredPublication(resolvedPublications, loaderId)
                    def isDryRun = extension.config.dryRun.get()
                    if (!isDryRun && !extension.publish.modrinth.token.isPresent()) {
                        throw new IllegalStateException('[Publishing] Modrinth publishing requires publish.modrinth.token unless dryRun=true')
                    }

                    def gameVersions = requiredCsv(project, 'publish.game-versions')
                    MultiloaderPublishRules.requireNonEmpty('publish.game-versions', gameVersions)
                    def modrinthGameVersions = MultiloaderPublishRules.modrinthNormalizeGameVersions(gameVersions)

                    project.logger.lifecycle("[Publishing] Destinations=[modrinth]")
                    project.logger.lifecycle("[Publishing] loaders=${publication.loaders}")
                    project.logger.lifecycle("[Publishing] gameVersions=${gameVersions}")

                    publishModrinth(project, extension, resolvedDisplayName, resolvedChangelog, isDryRun, modrinthGameVersions, stagedArtifactFile(project, publication), publication.loaders)

                    if (isDryRun) {
                        project.logger.lifecycle('[Publishing] dryRun=true -> skipping live publish')
                    }
                }
            }

            publishCurseforgeAll.configure { dependsOn(curseTask) }
            publishModrinthAll.configure { dependsOn(modrinthTask) }
        }

        project.gradle.projectsEvaluated {
            resolvedDisplayName = resolveDisplayName(project, extension)
            resolvedChangelog = resolveChangelog(project, extension)

            loaderSpecs.each { loaderId, spec ->
                if (!(spec.enabled as Closure<Boolean>).call()) {
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

                def assembleTask = project.tasks.named("publishingAssemble${capitalize(loaderId)}")
                assembleTask.configure { task ->
                    (spec.extraDepends as List<String>).each { taskName ->
                        def extraTask = subproject.tasks.findByName(taskName)
                        if (extraTask != null) {
                            task.dependsOn(extraTask)
                        }
                    }
                    task.dependsOn(jarOutput.task)
                }

                resolvedPublications[loaderId] = new PublicationSpec(
                    loaderId,
                    [loaderId],
                    subproject,
                    jarOutput.archiveFile,
                )
            }
        }
    }

    private static Map<String, Map<String, Object>> loaderSpecs(MultiloaderPublishingExtension extension) {
        [
            fabric  : [enabled: { extension.loaders.fabric.enabled.get() }, path: ':fabric', jarTask: 'remapJar', fallbackJarTask: 'jar', extraDepends: []],
            forge   : [enabled: { extension.loaders.forge.enabled.get() }, path: ':forge', jarTask: 'jar', fallbackJarTask: null, extraDepends: ['reobfJar']],
            neoforge: [enabled: { extension.loaders.neoforge.enabled.get() }, path: ':neoforge', jarTask: 'jar', fallbackJarTask: null, extraDepends: []],
        ]
    }

    private static void configureDefaults(Project project, MultiloaderPublishingExtension extension) {
        extension.config.dryRun.convention(booleanProperty(project, 'publish.dry-run', false))
        extension.config.releaseType.convention(optionalProperty(project, 'publish.release-type') ?: 'release')
        extension.publish.curseforge.environment.convention(curseEnvironment(project))
        extension.publish.curseforge.javaVersions.convention([requiredProperty(project, 'project.java')])

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

    private static PublicationSpec requiredPublication(Map<String, PublicationSpec> resolvedPublications, String loaderId) {
        def publication = resolvedPublications[loaderId]
        if (publication == null) {
            throw new IllegalStateException("[Publishing] No publication configured for loader '${loaderId}'")
        }
        publication
    }

    private static File stagedArtifactFile(Project project, PublicationSpec publication) {
        def source = publication.archiveFile.get().asFile
        project.layout.buildDirectory.file("publishing/artifacts/${source.name}").get().asFile
    }

    private static void publishModrinth(Project project, MultiloaderPublishingExtension extension, String displayName, String changelog, boolean dryRun, List<String> gameVersions, File file, List<String> loaders) {
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

        def normalizedLoaders = MultiloaderPublishRules.modrinthNormalizeLoaders(loaders)
        if (normalizedLoaders.isEmpty()) {
            throw new IllegalStateException("[Publishing] Could not infer Modrinth loaders for ${file.name}")
        }

        def body = [
            project_id    : projectId,
            file_parts    : [file.name],
            version_number: project.version.toString(),
            name          : publishedArtifactName(project, loaders.first()),
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

    private static void publishCurseForge(Project project, MultiloaderPublishingExtension extension, String displayName, String changelog, boolean dryRun, List<String> gameVersions, File file, List<String> loaders) {
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
        curseTags.addAll(extension.publish.curseforge.javaVersions.getOrElse([]).collect { normalizeJavaTag(it) })
        curseTags.addAll(MultiloaderPublishRules.curseNormalizeLoaders(loaders))

        if (curseTags.isEmpty()) {
            throw new IllegalStateException("[Publishing] Could not infer CurseForge loader tags for ${file.name}")
        }

        def metadata = [
            changelog               : changelog,
            changelogType           : 'markdown',
            displayName             : publishedArtifactName(project, loaders.first()),
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
                .displayName(publishedArtifactName(project, loaders.first()))
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

    private static String resolveDisplayName(Project project, MultiloaderPublishingExtension extension) {
        if (extension.metadata.displayName.isPresent()) {
            return extension.metadata.displayName.get()
        }
        "${requiredProperty(project, 'mod.name')} ${project.version}"
    }

    private static String publicationDisplayName(Project project, String loader) {
        publishedArtifactName(project, loader)
    }

    private static String publishedArtifactName(Project project, String loader) {
        "${requiredProperty(project, 'mod.id')}-${loader}-${project.version}"
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

    private static final class PublicationSpec {
        final String loader
        final List<String> loaders
        final Project project
        final Provider<RegularFile> archiveFile

        PublicationSpec(String loader, List<String> loaders, Project project, Provider<RegularFile> archiveFile) {
            this.loader = loader
            this.loaders = loaders
            this.project = project
            this.archiveFile = archiveFile
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
