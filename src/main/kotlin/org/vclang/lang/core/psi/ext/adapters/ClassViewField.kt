package org.vclang.lang.core.psi.ext.adapters

import com.intellij.lang.ASTNode
import com.jetbrains.jetpad.vclang.term.Abstract
import com.jetbrains.jetpad.vclang.term.AbstractDefinitionVisitor
import org.vclang.lang.core.Surrogate
import org.vclang.lang.core.psi.VcClassViewField

abstract class ClassViewFieldAdapter(node: ASTNode) : DefinitionAdapter(node),
                                                      VcClassViewField {
    private var underlyingFieldName: String? = null
    private var ownView: ClassViewAdapter? = null
    private var underlyingField: Abstract.ClassField? = null

    fun reconstruct(
            position: Surrogate.Position?,
            name: String?,
            precedence: Abstract.Precedence?,
            underlyingFieldName: String?,
            ownView: ClassViewAdapter?
    ): ClassViewFieldAdapter {
        super.reconstruct(position, name, precedence)
        this.underlyingFieldName = underlyingFieldName
        this.ownView = ownView
        return this
    }

    override fun getUnderlyingFieldName(): String =
            underlyingFieldName ?: throw IllegalStateException()

    override fun getUnderlyingField(): Abstract.ClassField =
            underlyingField ?: throw IllegalStateException()

    fun setUnderlyingField(underlyingField: Abstract.ClassField?) {
        this.underlyingField = underlyingField
    }

    override fun getOwnView(): ClassViewAdapter = ownView ?: throw IllegalStateException()

    override fun <P, R> accept(visitor: AbstractDefinitionVisitor<in P, out R>, params: P): R =
            visitor.visitClassViewField(this, params)
}