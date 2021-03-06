package org.arend.psi.ext.impl

import com.intellij.lang.ASTNode
import com.intellij.psi.stubs.IStubElementType
import org.arend.ArendIcons
import org.arend.naming.reference.ClassReferable
import org.arend.naming.reference.GlobalReferable
import org.arend.naming.reference.LocatedReferable
import org.arend.psi.*
import org.arend.psi.ext.ArendFunctionalBody
import org.arend.psi.stubs.ArendCoClauseDefStub
import org.arend.term.FunctionKind
import org.arend.term.abs.Abstract
import org.arend.term.abs.AbstractDefinitionVisitor
import org.arend.typing.ReferableExtractVisitor
import javax.swing.Icon

abstract class CoClauseDefAdapter : DefinitionAdapter<ArendCoClauseDefStub>, ArendCoClauseDef {
    constructor(node: ASTNode) : super(node)

    constructor(stub: ArendCoClauseDefStub, nodeType: IStubElementType<*, *>) : super(stub, nodeType)

    val parentCoClause: ArendCoClause?
        get() = parent as? ArendCoClause

    override fun getNameIdentifier() = parentCoClause?.longName?.refIdentifierList?.lastOrNull()

    override fun getName() = stub?.name ?: parentCoClause?.longName?.refIdentifierList?.lastOrNull()?.referenceName

    override fun getPrec(): ArendPrec? = parentCoClause?.prec

    override fun getAlias(): ArendAlias? = null

    override val defIdentifier: ArendDefIdentifier?
        get() = null

    override val where: ArendWhere?
        get() = null

    override fun getParameters(): List<ArendNameTele> = nameTeleList

    override val body: ArendFunctionalBody?
        get() = coClauseBody

    override fun getClassReference(): ClassReferable? = resultType?.let { ReferableExtractVisitor().findClassReferable(it) }

    override fun getCoClauseElements(): List<Abstract.ClassFieldImpl> = emptyList()

    override fun getResultType(): ArendExpr? = returnExpr?.let { it.exprList.firstOrNull() ?: it.atomFieldsAccList.firstOrNull() }

    override fun getResultTypeLevel(): ArendExpr? = returnExpr?.let { it.exprList.getOrNull(1) ?: it.atomFieldsAccList.getOrNull(1) }

    override fun getTerm(): Abstract.Expression? = coClauseBody?.expr

    override fun getEliminatedExpressions(): List<ArendRefIdentifier> = coClauseBody?.elim?.refIdentifierList ?: emptyList()

    override fun getClauses(): List<ArendClause> = coClauseBody?.clauseList ?: emptyList()

    override fun getUsedDefinitions(): List<LocatedReferable> = emptyList()

    override fun withTerm() = coClauseBody?.fatArrow != null

    override fun isCowith() = coClauseBody?.cowithKw != null

    override fun getFunctionKind() = FunctionKind.COCLAUSE_FUNC

    override fun getImplementedField(): Abstract.Reference? = parentCoClause?.longName?.refIdentifierList?.lastOrNull()

    override fun getKind() = GlobalReferable.Kind.FUNCTION

    override fun <R : Any?> accept(visitor: AbstractDefinitionVisitor<out R>): R = visitor.visitFunction(this)

    override fun getIcon(flags: Int): Icon = ArendIcons.COCLAUSE_DEFINITION
}