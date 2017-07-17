package org.vclang.lang.core.psi.ext

import com.intellij.lang.ASTNode
import org.vclang.lang.core.psi.VcStatements
import org.vclang.lang.core.resolve.MergeScope
import org.vclang.lang.core.resolve.Namespace
import org.vclang.lang.core.resolve.NamespaceProvider
import org.vclang.lang.core.resolve.Scope

abstract class VcStatementsImplMixin(node: ASTNode) : VcCompositeElementImpl(node),
                                                      VcStatements {
    override val namespace: Namespace
        get() = NamespaceProvider.forDefinitions(
                statementList.map { it.statDef }.filterNotNull()
        )

    override val scope: Scope
        get() = statementList
                .map { it.statCmd }
                .filterNotNull()
                .map { it.scope }
                .fold(super.scope) { scope1, scope2 -> MergeScope(scope1, scope2) }
}