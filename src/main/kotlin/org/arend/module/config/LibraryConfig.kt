package org.arend.module.config

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.PsiManager
import org.arend.ArendFileType
import org.arend.ext.module.ModulePath
import org.arend.library.LibraryDependency
import org.arend.module.ArendRawLibrary
import org.arend.module.ModuleLocation
import org.arend.psi.ArendFile
import org.arend.typechecking.TypeCheckingService
import org.arend.util.FileUtils
import org.arend.util.Range
import org.arend.util.Version
import org.arend.util.mapFirstNotNull
import java.nio.file.Path
import java.nio.file.Paths


abstract class LibraryConfig(val project: Project) {
    open val sourcesDir: String
        get() = ""
    open val binariesDir: String?
        get() = null
    open val testsDir: String
        get() = ""
    open val extensionsDir: String?
        get() = null
    open val extensionMainClass: String?
        get() = null
    open val modules: List<ModulePath>?
        get() = null
    open val dependencies: List<LibraryDependency>
        get() = emptyList()
    open val langVersion: Range<Version>
        get() = Range.unbound()

    abstract val name: String

    abstract val rootDir: String?

    val rootPath: Path?
        get() = rootDir?.let { Paths.get(FileUtil.toSystemDependentName(it)) }

    private val additionalModules = HashMap<ModulePath, ArendFile>()

    // Sources directory

    val sourcesPath: Path?
        get() {
            val sources = sourcesDir
            if (sources.isEmpty()) {
                return rootPath
            }

            val path = Paths.get(sources)
            return if (path.isAbsolute) path else rootPath?.resolve(path)
        }

    open val sourcesDirFile: VirtualFile?
        get() = sourcesPath?.let { VirtualFileManager.getInstance().getFileSystem(LocalFileSystem.PROTOCOL).findFileByPath(it.toString()) }

    // Tests directory

    val testsPath: Path?
        get() {
            val tests = testsDir
            if (tests.isEmpty()) {
                return null
            }

            val path = Paths.get(tests)
            return if (path.isAbsolute) path else rootPath?.resolve(path)
        }

    open val testsDirFile: VirtualFile?
        get() = testsPath?.let { VirtualFileManager.getInstance().getFileSystem(LocalFileSystem.PROTOCOL).findFileByPath(it.toString()) }

    // Binaries directory

    val binariesPath: Path?
        get() {
            val path = binariesDir?.let { Paths.get(it) } ?: return null
            return if (path.isAbsolute) path else rootPath?.resolve(path)
        }

    // Extensions

    val extensionsPath: Path?
        get() {
            val path = extensionsDir?.let { Paths.get(it) } ?: return null
            return if (path.isAbsolute) path else rootPath?.resolve(path)
        }

    val extensionClassPath: Path?
        get() {
            val className = extensionMainClass ?: return null
            var path = extensionsPath ?: return null
            val names = className.split('.')
            if (names.isEmpty()) {
                return null
            }
            for (name in names.subList(0, names.size - 1)) {
                path = path.resolve(name)
            }
            return path.resolve(names[names.lastIndex] + ".class")
        }

    // Modules

    fun findModules(inTests: Boolean): List<ModulePath> {
        val modules = modules
        if (modules != null) {
            return modules
        }

        val srcFile = if (inTests) testsDirFile else sourcesDirFile
        if (srcFile != null) {
            return getArendFiles(srcFile).mapNotNull { it.moduleLocation?.modulePath }
        }

        val srcPath = if (inTests) testsPath else sourcesPath
        if (srcPath != null) {
            val list = ArrayList<ModulePath>()
            FileUtils.getModules(srcPath, FileUtils.EXTENSION, list, project.service<TypeCheckingService>().libraryManager.libraryErrorReporter)
            return list
        }

        return emptyList()
    }

    fun getArendFiles(root: VirtualFile): List<ArendFile> {
        val result = ArrayList<ArendFile>()
        val psiManager = PsiManager.getInstance(project)
        VfsUtilCore.iterateChildrenRecursively(root, null) { file ->
            if (file.name.endsWith(FileUtils.EXTENSION)) {
                (psiManager.findFile(file) as? ArendFile)?.let { result.add(it) }
            }
            return@iterateChildrenRecursively true
        }
        return result
    }

    fun containsModule(modulePath: ModulePath) =
        modules?.any { it == modulePath } ?: findArendFile(modulePath, withAdditional = false, withTests = false) != null

    val additionalModulesSet: Set<ModulePath>
        get() = additionalModules.keys

    fun addAdditionalModule(modulePath: ModulePath, file: ArendFile) {
        additionalModules[modulePath] = file
    }

    fun clearAdditionalModules() {
        val maps = project.service<TypeCheckingService>().tcRefMaps
        for (file in additionalModules.values) {
            maps.remove(file.moduleLocation)
        }
        additionalModules.clear()
    }

    private fun findParentDirectory(modulePath: ModulePath, inTests: Boolean): VirtualFile? {
        var dir = (if (inTests) testsDirFile else sourcesDirFile) ?: return null
        val list = modulePath.toList()
        var i = 0
        while (i < list.size - 1) {
            dir = dir.findChild(list[i++]) ?: return null
        }
        return dir
    }

    fun findArendDirectory(modulePath: ModulePath): PsiDirectory? {
        var dir = sourcesDirFile ?: return null
        for (name in modulePath.toList()) {
            dir = dir.findChild(name) ?: return null
        }
        return PsiManager.getInstance(project).findDirectory(dir)
    }

    fun findArendFile(modulePath: ModulePath, inTests: Boolean): ArendFile? =
        findParentDirectory(modulePath, inTests)?.findChild(modulePath.lastName + FileUtils.EXTENSION)?.let {
            PsiManager.getInstance(project).findFile(it) as? ArendFile
        }

    fun findArendFile(modulePath: ModulePath, withAdditional: Boolean, withTests: Boolean): ArendFile? =
        if (modulePath.size() == 0) {
            null
        } else {
            (if (withAdditional) additionalModules[modulePath] else null) ?:
                findArendFile(modulePath, false) ?: if (withTests) findArendFile(modulePath, true) else null
        }

    fun findArendFileOrDirectory(modulePath: ModulePath, withAdditional: Boolean, withTests: Boolean): PsiFileSystemItem? {
        if (modulePath.size() == 0) {
            return findArendDirectory(modulePath)
        }
        if (withAdditional) {
            additionalModules[modulePath]?.let {
                return it
            }
        }

        val psiManager = PsiManager.getInstance(project)

        val srcDir = findParentDirectory(modulePath, false)
        srcDir?.findChild(modulePath.lastName + FileUtils.EXTENSION)?.let {
            val file = psiManager.findFile(it)
            if (file is ArendFile) {
                return file
            }
        }

        val testDir = if (withTests) findParentDirectory(modulePath, true) else null
        testDir?.findChild(modulePath.lastName + FileUtils.EXTENSION)?.let {
            val file = psiManager.findFile(it)
            if (file is ArendFile) {
                return file
            }
        }

        return (srcDir?.findChild(modulePath.lastName) ?: testDir?.findChild(modulePath.lastName))?.let {
            psiManager.findDirectory(it)
        }
    }

    fun getFileModulePath(file: ArendFile): ModuleLocation? {
        file.generatedModuleLocation?.let {
            return it
        }

        val fileName = file.originalFile.viewProvider.virtualFile.path
        val locationKind: ModuleLocation.LocationKind
        val root: String
        val sourcesRoot = sourcesPath?.let { FileUtil.toSystemIndependentName(it.toString()) }
        if (sourcesRoot != null && fileName.startsWith(sourcesRoot)) {
            root = sourcesRoot
            locationKind = ModuleLocation.LocationKind.SOURCE
        } else {
            val testsRoot = testsPath?.let { FileUtil.toSystemIndependentName(it.toString()) }
            if (testsRoot == null || !fileName.startsWith(testsRoot)) {
                return null
            }
            root = testsRoot
            locationKind = ModuleLocation.LocationKind.TEST
        }
        val fullName = fileName.substring(root.length).removePrefix("/").removeSuffix('.' + ArendFileType.defaultExtension).replace('/', '.')
        return ModuleLocation(name, locationKind, ModulePath(fullName.split('.')))
    }

    fun getFileLocationKind(file: ArendFile): ModuleLocation.LocationKind? = getFileModulePath(file)?.locationKind

    // Dependencies

    val availableConfigs: List<LibraryConfig>
        get() {
            val deps = dependencies
            if (deps.isEmpty()) {
                return listOf(this)
            }

            val libraryManager = project.service<TypeCheckingService>().libraryManager
            return listOf(this) + deps.mapNotNull { dep -> (libraryManager.getRegisteredLibrary(dep.name) as? ArendRawLibrary)?.config }
        }

    inline fun <T> forAvailableConfigs(f: (LibraryConfig) -> T?): T? {
        val t = f(this)
        if (t != null) {
            return t
        }

        val deps = dependencies
        if (deps.isEmpty()) {
            return null
        }

        val libraryManager = project.service<TypeCheckingService>().libraryManager
        return deps.mapFirstNotNull { dep -> (libraryManager.getRegisteredLibrary(dep.name) as? ArendRawLibrary)?.config?.let { f(it) } }
    }
}