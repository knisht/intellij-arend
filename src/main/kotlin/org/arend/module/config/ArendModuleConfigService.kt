package org.arend.module.config

import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleServiceManager
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import org.arend.ext.module.ModulePath
import org.arend.library.LibraryDependency
import org.arend.module.ArendModuleType
import org.arend.module.ArendRawLibrary
import org.arend.module.ModuleSynchronizer
import org.arend.settings.ArendSettings
import org.arend.typechecking.ArendTypechecking
import org.arend.typechecking.TypeCheckingService
import org.arend.typechecking.error.NotificationErrorReporter
import org.arend.util.*
import org.arend.yaml.*
import org.jetbrains.yaml.psi.YAMLFile
import java.nio.file.Paths


class ArendModuleConfigService(val module: Module) : LibraryConfig(module.project), ArendModuleConfiguration {
    private var synchronized = false

    fun synchronize() = synchronized(this) {
        val result = !synchronized
        synchronized = true
        result
    }

    private val libraryManager = project.service<TypeCheckingService>().libraryManager

    override var librariesRoot = service<ArendSettings>().librariesRoot
        set(value) {
            field = value
            service<ArendSettings>().librariesRoot = value
        }

    override var sourcesDir = ""
    override var withBinaries = false
    override var binariesDirectory = ""
    override var testsDir = ""
    override var withExtensions = false
    override var extensionsDirectory = ""
    override var extensionMainClassData = ""
    override var modules: List<ModulePath>? = null
    override var dependencies: List<LibraryDependency> = emptyList()
    override var langVersionString: String = ""

    override val binariesDir: String?
        get() = flaggedBinariesDir

    override val extensionsDir: String?
        get() = flaggedExtensionsDir

    override val extensionMainClass: String?
        get() = flaggedExtensionMainClass

    override val langVersion: Range<Version>
        get() = Range.parseVersionRange(langVersionString) ?: Range.unbound()

    val root
        get() = ModuleRootManager.getInstance(module).contentEntries.firstOrNull()?.file

    override val rootDir
        get() = root?.path

    override val name
        get() = module.name

    override val sourcesDirFile: VirtualFile?
        get() {
            val dir = sourcesDir
            if (dir.isEmpty()) {
                return root
            }

            val root = root
            val path = when {
                root != null -> Paths.get(root.path).resolve(dir).toString()
                Paths.get(dir).isAbsolute -> dir
                else -> return null
            }
            return VirtualFileManager.getInstance().getFileSystem(LocalFileSystem.PROTOCOL).findFileByPath(path)
        }

    private val yamlFile
        get() = rootPath?.resolve(FileUtils.LIBRARY_CONFIG_FILE)?.let { project.findPsiFileByPath(it) as? YAMLFile }

    val librariesRootDef: String?
        get() {
            val librariesRoot = librariesRoot
            return if (librariesRoot.isEmpty()) {
                root?.parent?.path?.let { FileUtil.toSystemDependentName(it) }
            } else librariesRoot
        }

    val library = ArendRawLibrary(this)

    private fun updateDependencies(newDependencies: List<LibraryDependency>, reload: Boolean) {
        val oldDependencies = dependencies
        dependencies = ArrayList(newDependencies)
        synchronized = false

        if (!reload) {
            return
        }

        var reloadLib = false
        for (dependency in oldDependencies) {
            if (!newDependencies.contains(dependency) && libraryManager.getRegisteredLibrary(dependency.name) != null) {
                reloadLib = true
                break
            }
        }

        val typechecking = ArendTypechecking.create(project)
        if (reloadLib) {
            libraryManager.reloadInternalLibraries { typechecking }
        } else {
            for (dependency in newDependencies) {
                if (!oldDependencies.contains(dependency)) {
                    var depLibrary = libraryManager.getRegisteredLibrary(dependency.name)
                    if (depLibrary == null) {
                        depLibrary = libraryManager.loadDependency(library, dependency.name, typechecking)
                    }
                    if (depLibrary != null) {
                        libraryManager.registerDependency(library, depLibrary)
                    }
                }
            }
        }
    }

    fun copyFromYAML(yaml: YAMLFile, update: Boolean) {
        val newDependencies = yaml.dependencies
        if (dependencies != newDependencies) {
            updateDependencies(newDependencies, update)
            ModuleSynchronizer.synchronizeModule(this)
        }

        modules = yaml.modules
        flaggedBinariesDir = yaml.binariesDir
        testsDir = yaml.testsDir
        val extDir = yaml.extensionsDir
        val extMain = yaml.extensionMainClass
        withExtensions = extDir != null && extMain != null
        if (extDir != null) {
            extensionsDirectory = extDir
        }
        if (extMain != null) {
            extensionMainClassData = extMain
        }
        sourcesDir = yaml.sourcesDir ?: ""
        langVersionString = yaml.langVersion ?: ""
    }

    fun copyFromYAML() {
        copyFromYAML(yamlFile ?: return, true)
    }

    fun updateFromIDEA(config: ArendModuleConfiguration) {
        val newLibrariesRoot = config.librariesRoot
        val reload = librariesRoot != newLibrariesRoot
        var updateYAML = false

        val newDependencies = config.dependencies
        if (dependencies != newDependencies) {
            updateDependencies(newDependencies, !reload)
            updateYAML = true
        }

        val newSourcesDir = config.sourcesDir
        if (sourcesDir != newSourcesDir) {
            sourcesDir = newSourcesDir
            updateYAML = true
        }

        val newBinariesDir = config.flaggedBinariesDir
        if (flaggedBinariesDir != newBinariesDir) {
            updateYAML = true
        }
        withBinaries = config.withBinaries
        binariesDirectory = config.binariesDirectory

        val newTestsDir = config.testsDir
        if (testsDir != newTestsDir) {
            testsDir = newTestsDir
            updateYAML = true
        }

        val newExtensionsDir = config.flaggedExtensionsDir
        if (flaggedExtensionsDir != newExtensionsDir) {
            updateYAML = true
        }

        val newExtensionMainClass = config.flaggedExtensionMainClass
        if (flaggedExtensionMainClass != newExtensionMainClass) {
            updateYAML = true
        }
        withExtensions = config.withExtensions
        extensionsDirectory = config.extensionsDirectory
        extensionMainClassData = config.extensionMainClassData

        val newLangVersion = config.langVersionString
        if (langVersionString != newLangVersion) {
            langVersionString = newLangVersion
            updateYAML = true
        }

        if (updateYAML) yamlFile?.write {
            langVersion = newLangVersion
            sourcesDir = newSourcesDir
            binariesDir = newBinariesDir
            testsDir = newTestsDir
            extensionsDir = newExtensionsDir
            extensionMainClass = newExtensionMainClass
            dependencies = newDependencies
        }

        if (reload) {
            librariesRoot = newLibrariesRoot
            project.service<TypeCheckingService>().reload()
        }
    }

    companion object {
        fun getConfig(module: Module): LibraryConfig {
            if (ArendModuleType.has(module)) {
                val service = ModuleServiceManager.getService(module, ArendModuleConfigService::class.java)
                if (service != null) {
                    return service
                }
                NotificationErrorReporter.ERROR_NOTIFICATIONS.createNotification("Failed to get ArendModuleConfigService for $module", NotificationType.ERROR).notify(module.project)
            }
            return EmptyLibraryConfig(module.name, module.project)
        }

        fun getInstance(module: Module?) =
            if (module != null && ArendModuleType.has(module)) ModuleServiceManager.getService(module, ArendModuleConfigService::class.java) else null
    }
}