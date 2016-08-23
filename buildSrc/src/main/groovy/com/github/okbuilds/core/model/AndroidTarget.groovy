package com.github.okbuilds.core.model

import com.android.build.gradle.api.BaseVariant
import com.android.build.gradle.internal.variant.BaseVariantData
import com.android.builder.model.ClassField
import com.android.builder.model.SourceProvider
import com.android.manifmerger.ManifestMerger2
import com.android.manifmerger.MergingReport
import com.android.utils.ILogger
import com.github.okbuilds.core.util.FileUtil
import groovy.transform.ToString
import groovy.util.slurpersupport.GPathResult
import groovy.xml.StreamingMarkupBuilder
import org.apache.commons.codec.digest.DigestUtils
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.logging.Logger

/**
 * An Android target
 */
abstract class AndroidTarget extends JavaLibTarget {

    final String applicationId
    final String applicationIdSuffix
    final String versionName
    final Integer versionCode
    final int minSdk
    final int targetSdk
    final boolean debuggable
    final boolean generateR2

    AndroidTarget(Project project, String name) {
        super(project, name)

        String suffix = ""
        if (baseVariant.mergedFlavor.applicationIdSuffix != null) {
            suffix += baseVariant.mergedFlavor.applicationIdSuffix
        }
        if (baseVariant.buildType.applicationIdSuffix != null) {
            suffix += baseVariant.buildType.applicationIdSuffix
        }

        baseVariant
        applicationIdSuffix = suffix
        applicationId = baseVariant.applicationId - applicationIdSuffix
        versionName = baseVariant.mergedFlavor.versionName
        versionCode = baseVariant.mergedFlavor.versionCode
        minSdk = baseVariant.mergedFlavor.minSdkVersion.apiLevel
        targetSdk = baseVariant.mergedFlavor.targetSdkVersion.apiLevel
        debuggable = baseVariant.buildType.debuggable

        // Butterknife support
        generateR2 = project.plugins.hasPlugin('com.jakewharton.butterknife')
    }

    protected abstract BaseVariant getBaseVariant()

    protected abstract ManifestMerger2.MergeType getMergeType()

    @Override
    Scope getMain() {
        Set<File> srcDirs = baseVariant.sourceSets.collect { SourceProvider provider ->
            provider.javaDirectories
        }.flatten() as Set<File>

        return new Scope(
                project,
                ["compile", "${buildType}Compile", "${flavor}Compile", "${name}Compile"] as Set,
                srcDirs,
                null,
                extraJvmArgs)
    }

    @Override
    Scope getTest() {
        return new Scope(
                project,
                ["compile", "${buildType}Compile", "${flavor}Compile", "${name}Compile",
                 "testCompile", "${buildType}TestCompile", "${flavor}TestCompile", "${name}TestCompile"] as Set,
                project.files("src/test/java") as Set<File>,
                project.file("src/test/resources"),
                extraJvmArgs)
    }

    @Override
    Set<GradleSourcegen> getGradleSourcegen() {
        Set<GradleSourcegen> tasks = super.getGradleSourcegen()
        // SqlDelight support
        if (project.plugins.hasPlugin('com.squareup.sqldelight')) {
            BaseVariantData data = baseVariant.variantData
            Task sqlDelightGen = data.sourceGenTask.getDependsOn().find {
                it instanceof Task && it.name.toLowerCase().contains("sqldelight")
            } as Task
            tasks.add(new GradleSourcegen(sqlDelightGen,
                    srcDirNames.collect { "src/${it}/sqldelight/**/*.sq" },
                    sqlDelightGen.outputs.files[0]))
        }
        return tasks
    }

    Set<String> getSrcDirNames() {
        return baseVariant.sourceSets.collect { SourceProvider provider ->
            provider.name
        }
    }

    public boolean getRobolectric() {
        return rootProject.okbuck.experimental.robolectric
    }

    @Override
    String getSourceCompatibility() {
        return javaVersion(project.android.compileOptions.sourceCompatibility as JavaVersion)
    }

    @Override
    String getTargetCompatibility() {
        return javaVersion(project.android.compileOptions.targetCompatibility as JavaVersion)
    }

    @Override
    String getInitialBootCp() {
        return baseVariant.javaCompile.options.bootClasspath
    }

    List<String> getBuildConfigFields() {
        return ["String BUILD_TYPE = \"${buildType}\"",
                "String FLAVOR = \"${flavor}\"",
        ] + baseVariant.mergedFlavor.buildConfigFields.collect {
            String key, ClassField classField ->
                "${classField.type} ${key} = ${classField.value}"
        }
    }

    String getFlavor() {
        return baseVariant.flavorName
    }

    String getBuildType() {
        return baseVariant.buildType.name
    }

    Set<ResBundle> getResources() {
        Set<String> resources = [] as Set
        Set<String> assets = [] as Set

        baseVariant.sourceSets.each { SourceProvider provider ->
            resources.addAll(getAvailable(provider.resDirectories))
            assets.addAll(getAvailable(provider.assetsDirectories))
        }

        Map<String, String> resourceMap = resources.collectEntries { String res ->
            [project.file(res).parentFile.path, res]
        }
        Map<String, String> assetMap = assets.collectEntries { String asset ->
            [project.file(asset).parentFile.path, asset]
        }

        Set<String> keys = (resourceMap.keySet() + assetMap.keySet())
        return keys.collect { key ->
            new ResBundle(identifier, resourceMap.get(key, null), assetMap.get(key, null), keys.size() > 1)
        } as Set
    }

    Set<String> getAidl() {
        baseVariant.sourceSets.collect { SourceProvider provider ->
            getAvailable(provider.aidlDirectories)
        }.flatten() as Set<String>
    }

    Set<String> getJniLibs() {
        baseVariant.sourceSets.collect { SourceProvider provider ->
            getAvailable(provider.jniLibsDirectories)
        }.flatten() as Set<String>
    }

    String getManifest() {
        Set<String> manifests = [] as Set

        baseVariant.sourceSets.each { SourceProvider provider ->
            manifests.addAll(getAvailable(Collections.singletonList(provider.manifestFile)))
        }

        if (manifests.empty) {
            return null
        }

        File mainManifest = project.file(manifests[0])

        List<File> secondaryManifests = []
        secondaryManifests.addAll(
                manifests.collect {
                    String manifestFile -> project.file(manifestFile)
                })

        secondaryManifests.remove(mainManifest)

        File mergedManifest = project.file("${project.buildDir}/okbuck/${name}/AndroidManifest.xml")
        mergedManifest.parentFile.mkdirs()
        mergedManifest.createNewFile()

        MergingReport report = ManifestMerger2.newMerger(mainManifest, new GradleLogger(project.logger), mergeType)
                .addFlavorAndBuildTypeManifests(secondaryManifests as File[])
                .withFeatures(ManifestMerger2.Invoker.Feature.NO_PLACEHOLDER_REPLACEMENT) // needs to be handled by buck
                .merge()

        if (report.result.success) {
            mergedManifest.text = report.getMergedDocument(MergingReport.MergedManifestKind.MERGED)

            XmlSlurper slurper = new XmlSlurper()
            GPathResult manifestXml = slurper.parse(project.file(mergedManifest))

            manifestXml.@package = applicationId + applicationIdSuffix
            if (versionCode) {
                manifestXml.@'android:versionCode' = String.valueOf(versionCode)
            }
            if (versionName) {
                manifestXml.@'android:versionName' = versionName
            }
            manifestXml.application.@'android:debuggable' = String.valueOf(debuggable)

            def sdkNode = {
                'uses-sdk'('android:minSdkVersion': String.valueOf(minSdk),
                        'android:targetSdkVersion': String.valueOf(targetSdk)) {}
            }
            if (manifestXml.'uses-sdk'.size() == 0) {
                manifestXml.appendNode(sdkNode)
            } else {
                manifestXml.'uses-sdk'.replaceNode(sdkNode)
            }

            def builder = new StreamingMarkupBuilder()
            builder.setUseDoubleQuotes(true)
            mergedManifest.text = (builder.bind {
                mkp.yield manifestXml
            } as String)
                    .replaceAll("\\{http://schemas.android.com/apk/res/android\\}versionCode", "android:versionCode")
                    .replaceAll("\\{http://schemas.android.com/apk/res/android\\}versionName", "android:versionName")
                    .replaceAll("\\{http://schemas.android.com/apk/res/android\\}debuggable", "android:debuggable")
                    .replaceAll('xmlns:android="http://schemas.android.com/apk/res/android"', "")
                    .replaceAll("<manifest ", '<manifest xmlns:android="http://schemas.android.com/apk/res/android" ')
        } else if (report.result.error) {
            throw new IllegalStateException(report.loggingRecords.collect {
                "${it.severity}: ${it.message} at ${it.sourceLocation}"
            }.join('\n'))
        }

        return FileUtil.getRelativePath(project.projectDir, mergedManifest)
    }

    @ToString(includeNames = true)
    static class ResBundle {

        String id
        String resDir
        String assetsDir

        ResBundle(String identifier, String resDir, String assetsDir, boolean digestId = true) {
            this.resDir = resDir
            this.assetsDir = assetsDir
            if (digestId) {
                id = DigestUtils.md5Hex("${identifier}:${resDir}:${assetsDir}")
            } else {
                id = null
            }
        }
    }

    @Override
    def getProp(Map map, defaultValue) {
        return map.get("${identifier}${name.capitalize()}" as String,
                map.get("${identifier}${flavor.capitalize()}" as String,
                        map.get("${identifier}${buildType.capitalize()}" as String,
                                map.get(identifier, defaultValue))))
    }

    private static class GradleLogger implements ILogger {

        private final Logger mLogger;

        GradleLogger(Logger logger) {
            mLogger = logger;
        }

        @Override
        void error(Throwable throwable, String s, Object... objects) {
            mLogger.error(s, objects)
        }

        @Override
        void warning(String s, Object... objects) {
            mLogger.warn(s, objects)
        }

        @Override
        void info(String s, Object... objects) {
            mLogger.info(s, objects)
        }

        @Override
        void verbose(String s, Object... objects) {
            mLogger.debug(s, objects)
        }
    }
}
