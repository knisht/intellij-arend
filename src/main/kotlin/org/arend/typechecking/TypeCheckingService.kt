package org.arend.typechecking

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import org.arend.core.definition.Definition
import org.arend.error.DummyErrorReporter
import org.arend.ext.module.LongName
import org.arend.extImpl.DefinitionRequester
import org.arend.library.Library
import org.arend.library.LibraryManager
import org.arend.module.ArendPreludeLibrary
import org.arend.module.ModuleLocation
import org.arend.module.ModuleSynchronizer
import org.arend.yaml.YAMLFileListener
import org.arend.naming.reference.LocatedReferable
import org.arend.naming.reference.Referable
import org.arend.naming.reference.TCReferable
import org.arend.naming.scope.EmptyScope
import org.arend.naming.scope.LexicalScope
import org.arend.naming.scope.Scope
import org.arend.prelude.Prelude
import org.arend.psi.ArendDefFunction
import org.arend.psi.ArendFile
import org.arend.psi.ext.PsiLocatedReferable
import org.arend.psi.ext.TCDefinition
import org.arend.psi.ext.impl.ArendGroup
import org.arend.psi.ext.impl.ReferableAdapter
import org.arend.psi.listener.ArendDefinitionChangeListener
import org.arend.psi.listener.ArendDefinitionChangeService
import org.arend.resolving.ArendResolveCache
import org.arend.resolving.PsiConcreteProvider
import org.arend.typechecking.computation.ComputationRunner
import org.arend.typechecking.error.ErrorService
import org.arend.typechecking.error.NotificationErrorReporter
import org.arend.typechecking.execution.PsiElementComparator
import org.arend.typechecking.instance.provider.InstanceProviderSet
import org.arend.typechecking.order.dependency.DependencyCollector
import org.arend.util.FullName
import java.util.concurrent.ConcurrentHashMap

class TypeCheckingService(val project: Project) : ArendDefinitionChangeListener, DefinitionRequester {
    val dependencyListener = DependencyCollector()
    private val libraryErrorReporter = NotificationErrorReporter(project)
    val libraryManager = LibraryManager(ArendLibraryResolver(project), null, libraryErrorReporter, libraryErrorReporter, this)

    private val extensionDefinitions = HashMap<TCReferable, Boolean>()

    private val externalAdditionalNamesIndex = HashMap<String, ArrayList<PsiLocatedReferable>>()
    private val internalAdditionalNamesIndex = HashMap<String, ArrayList<PsiLocatedReferable>>()

    val tcRefMaps = ConcurrentHashMap<ModuleLocation, HashMap<LongName, TCReferable>>()

    val updatedModules = HashSet<ModuleLocation>()

    var isInitialized = false
        private set

    fun initialize(): Boolean {
        if (isInitialized) {
            return false
        }

        synchronized(ArendPreludeLibrary::class.java) {
            if (isInitialized) {
                return false
            }

            // Initialize prelude
            val preludeLibrary = ArendPreludeLibrary(project)
            this.preludeLibrary = preludeLibrary
            libraryManager.loadLibrary(preludeLibrary, null)
            preludeLibrary.prelude?.generatedModuleLocation = Prelude.MODULE_LOCATION

            if (Prelude.isInitialized()) {
                val tcRefMap = preludeLibrary.prelude?.tcRefMap
                if (tcRefMap != null) {
                    Prelude.forEach {
                        tcRefMap[it.referable.refLongName] = it.referable
                    }
                }
            }

            val concreteProvider = PsiConcreteProvider(project, DummyErrorReporter.INSTANCE, null)
            preludeLibrary.resolveNames(concreteProvider, libraryManager.libraryErrorReporter)
            Prelude.PreludeTypechecking(InstanceProviderSet(), concreteProvider, PsiElementComparator).typecheckLibrary(preludeLibrary)
            preludeLibrary.prelude?.let {
                fillAdditionalNames(it, true)
            }

            // Set the listener that updates typechecked definitions
            project.service<ArendDefinitionChangeService>().addListener(this)

            // Listen for YAML files changes
            YAMLFileListener(project).register()

            ModuleSynchronizer(project).install()

            isInitialized = true
        }

        return true
    }

    private var preludeLibrary: ArendPreludeLibrary? = null

    val prelude: ArendFile?
        get() = preludeLibrary?.prelude

    val preludeScope: Scope
        get() = prelude?.let { LexicalScope.opened(it) } ?: EmptyScope.INSTANCE

    fun getPsiReferable(referable: LocatedReferable): PsiLocatedReferable? {
        (referable.underlyingReferable as? PsiLocatedReferable)?.let { return it }
        return Scope.Utils.resolveName(preludeScope, referable.refLongName.toList()) as? PsiLocatedReferable
    }

    fun getDefinitionPsiReferable(definition: Definition) = getPsiReferable(definition.referable)

    fun reload() {
        libraryManager.reload {
            project.service<ArendResolveCache>().clear()
            externalAdditionalNamesIndex.clear()
            internalAdditionalNamesIndex.clear()
            extensionDefinitions.clear()

            ArendTypechecking.create(project)
        }
    }

    fun reloadInternal() {
        libraryManager.reloadInternalLibraries {
            internalAdditionalNamesIndex.clear()

            val it = extensionDefinitions.iterator()
            while (it.hasNext()) {
                if (it.next().value) {
                    it.remove()
                }
            }

            project.service<ErrorService>().clearAllErrors()
            project.service<ArendDefinitionChangeService>().incModificationCount()
            DaemonCodeAnalyzer.getInstance(project).restart()

            ArendTypechecking.create(project)
        }
    }

    override fun request(definition: Definition, library: Library) {
        extensionDefinitions[definition.referable] = !library.isExternal
    }

    fun fillAdditionalNames(group: ArendGroup, isExternal: Boolean) {
        for (subgroup in group.subgroups) {
            addAdditionalName(subgroup, isExternal)
            fillAdditionalNames(subgroup, isExternal)
        }
        for (referable in group.internalReferables) {
            addAdditionalName(referable, isExternal)
        }
    }

    private fun addAdditionalName(ref: PsiLocatedReferable, isExternal: Boolean) {
        (if (isExternal) externalAdditionalNamesIndex else internalAdditionalNamesIndex).computeIfAbsent(ref.refName) { ArrayList() }.add(ref)
    }

    fun getAdditionalReferables(name: String) = (internalAdditionalNamesIndex[name] ?: emptyList<PsiLocatedReferable>()) + (externalAdditionalNamesIndex[name] ?: emptyList())

    fun getAdditionalNames() = internalAdditionalNamesIndex.keys.union(externalAdditionalNamesIndex.keys)

    private fun resetErrors(def: Referable, removeTCRef: Boolean) {
        if (removeTCRef) {
            (def as? ReferableAdapter<*>)?.dropTCCache()
        }
        if (def is TCDefinition) {
            project.service<ErrorService>().clearTypecheckingErrors(def)
        }
    }

    private fun removeDefinition(referable: LocatedReferable, removeTCRef: Boolean): TCReferable? {
        if (referable is PsiElement && !referable.isValid) {
            return null
        }

        val curRef = referable.underlyingReferable
        val fullName = FullName(referable)
        val tcRefMap = fullName.modulePath?.let { tcRefMaps[it] }
        val tcReferable = tcRefMap?.get(fullName.longName)
        if (tcReferable == null) {
            resetErrors(curRef, removeTCRef)
            return null
        }

        if (extensionDefinitions.containsKey(tcReferable)) {
            service<ArendExtensionChangeListener>().notifyIfNeeded(project)
        }

        val prevRef = tcReferable.underlyingReferable
        if (curRef is PsiLocatedReferable && prevRef is PsiLocatedReferable && prevRef != curRef) {
            return null
        }
        if (removeTCRef) {
            tcRefMap.remove(fullName.longName)
        }
        resetErrors(curRef, removeTCRef)

        val tcTypecheckable = tcReferable.typecheckable
        tcTypecheckable.location?.let { updatedModules.add(it) }
        return tcTypecheckable
    }

    enum class LastModifiedMode { SET, SET_NULL, DO_NOT_TOUCH }

    private fun updateDefinition(referable: LocatedReferable, file: ArendFile, mode: LastModifiedMode, removeTCRef: Boolean) {
        if (mode != LastModifiedMode.DO_NOT_TOUCH && referable is TCDefinition) {
            val isValid = referable.isValid
            if (mode == LastModifiedMode.SET) {
                file.lastModifiedDefinition = if (isValid) referable else null
            } else {
                if (file.lastModifiedDefinition != referable) {
                    file.lastModifiedDefinition = null
                }
            }
        }

        val tcReferable = removeDefinition(referable, removeTCRef) ?: return
        val dependencies = synchronized(project) {
            dependencyListener.update(tcReferable)
        }
        for (ref in dependencies) {
            removeDefinition(ref, removeTCRef)
        }

        if ((referable as? ArendDefFunction)?.functionKw?.useKw != null) {
            (referable.parentGroup as? TCDefinition)?.let { updateDefinition(it, file, LastModifiedMode.DO_NOT_TOUCH, removeTCRef) }
        }
    }

    override fun updateDefinition(def: TCDefinition, file: ArendFile, isExternalUpdate: Boolean) {
        if (file.isReplFile) return
        if (ComputationRunner.getCancellationIndicator() is ArendCancellationIndicator) {
            synchronized(SyncObject) {
                (ComputationRunner.getCancellationIndicator() as? ArendCancellationIndicator)?.progress?.cancel()
                ComputationRunner.resetCancellationIndicator()
            }
        }

        if (!isExternalUpdate) {
            def.checkTCReferable()
        }
        updateDefinition(def, file, if (isExternalUpdate) LastModifiedMode.SET_NULL else LastModifiedMode.SET, !isExternalUpdate)
    }

    object SyncObject
}
