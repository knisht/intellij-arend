package org.vclang.lang.core.resolve

import org.vclang.lang.core.psi.*
import org.vclang.lang.core.psi.ext.VcNamedElement

object NamespaceProvider {

    fun forDefinition(
            definition: VcDefFunction,
            namespace: SimpleNamespace = SimpleNamespace()
    ): Namespace {
        forTelescope(definition.teleList, namespace)
        definition.where?.let { forWhere(it, namespace) }
        return namespace
    }

    fun forDefinition(
            definition: VcDefData,
            namespace: SimpleNamespace = SimpleNamespace()
    ): Namespace {
        definition.dataBody?.dataConstructors?.constructorList?.forEach { namespace.put(it) }
        forTelescope(definition.teleList, namespace)
        return namespace
    }

    fun forDefinition(
            definition: VcDefClass,
            namespace: SimpleNamespace = SimpleNamespace()
    ): Namespace {
        val definitions = definition.classStats?.classStatList?.mapNotNull { it.statement?.statDef }
        if (definitions != null) {
            forDefinitions(definitions, namespace)
            definition.classTeles?.teleList?.let { forTelescope(it, namespace) }
            definition.where?.let { forWhere(it, namespace) }
        }
        return namespace
    }

    fun forDefinition(
            definition: VcDefClassView,
            namespace: SimpleNamespace = SimpleNamespace()
    ): Namespace {
        definition.classViewFieldList.forEach { namespace.put(it) }
        return namespace
    }

    fun forDefinitions(
            definitions: List<VcStatDef>,
            namespace: SimpleNamespace = SimpleNamespace()
    ): Namespace {
        definitions
                .map { it.definition }
                .forEach {
                    if (it is VcDefData) {
                        val constructors = it.dataBody?.dataConstructors?.constructorList
                        constructors?.forEach { namespace.put(it) }
                    }
                    namespace.put(it as? VcNamedElement)
                }
        return namespace
    }

    fun forExpression(
            expr: VcLamExpr,
            namespace: SimpleNamespace = SimpleNamespace()
    ): Namespace = forTelescope(expr.teleList, namespace)

    fun forExpression(
            expr: VcPiExpr,
            namespace: SimpleNamespace = SimpleNamespace()
    ): Namespace = forTelescope(expr.teleList, namespace)

    fun forExpression(
            expr: VcSigmaExpr,
            namespace: SimpleNamespace = SimpleNamespace()
    ): Namespace = forTelescope(expr.teleList, namespace)

    fun forExpression(
            expr: VcNewExpr,
            parentScope: Scope,
            namespace: SimpleNamespace = SimpleNamespace()
    ): Namespace {
        expr.newKw ?: return namespace
        val className = expr
                .binOpArg
                ?.argumentBinOp
                ?.atomFieldsAcc
                ?.atom
                ?.literal
                ?.prefixName
                ?.referenceName ?: return namespace
        val classDef = parentScope.resolve(className)
        (classDef?.namespace as? SimpleNamespace)?.let { namespace.putAll(it) }
        return namespace
    }

    fun forPattern(
            pattern: VcPattern,
            namespace: SimpleNamespace = SimpleNamespace()
    ): Namespace {
//        pattern.variableList.forEach { namespace.put(it) }
//        pattern.atomPatternList
//                .map { it.pattern }
//                .filterNotNull()
//                .forEach { forPattern(it, namespace) }
        return namespace
    }

    private fun forTelescope(
            teles: Collection<VcTele>,
            namespace: SimpleNamespace = SimpleNamespace()
    ): Namespace {
        teles
                .map { it.literal?.prefixName }
                .filterNotNull()
                .forEach { namespace.put(it.referenceName!!, it) }
        teles
                .map { it.typedExpr }
                .filterNotNull()
                .flatMap { it.identifierOrUnknownList.map { it.identifier }.filterNotNull() }
                .forEach { namespace.put(it) }
        return namespace
    }

    private fun forWhere(where: VcWhere, namespace: SimpleNamespace) {
        val definitions = where.statementList.map { it.statDef }.filterNotNull()
        forDefinitions(definitions, namespace)
    }
}
