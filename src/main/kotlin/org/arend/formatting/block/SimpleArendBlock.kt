package org.arend.formatting.block

import com.intellij.formatting.*
import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.TokenType.ERROR_ELEMENT
import com.intellij.psi.TokenType.WHITE_SPACE
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.psi.formatter.common.AbstractBlock
import org.arend.parser.ParserMixin.DOC_COMMENT
import org.arend.psi.*
import org.arend.psi.ArendElementTypes.*
import org.arend.util.mapFirstNotNull
import java.util.*

class SimpleArendBlock(node: ASTNode, settings: CommonCodeStyleSettings?, wrap: Wrap?, alignment: Alignment?, myIndent: Indent?, parentBlock: AbstractArendBlock?) :
        AbstractArendBlock(node, settings, wrap, alignment, myIndent, parentBlock) {

    companion object {
        val noWhitespace: Spacing = Spacing.createSpacing(0, 0, 0, false, 0)
        val oneSpaceNoWrap: Spacing = Spacing.createSpacing(1, 1, 0, false, 0)
        val oneSpaceWrap: Spacing = Spacing.createSpacing(1, 1, 0, true, 0)
        val oneCrlf: Spacing = Spacing.createSpacing(0, 0, 1, false, 0)
        val oneBlankLine: Spacing = Spacing.createSpacing(0, 0, 2, false, 1)
    }

    override fun getSpacing(child1: Block?, child2: Block): Spacing? {
        val nodePsi = myNode.psi

        if (nodePsi is ArendFunctionClauses) {
            if (child2 is SimpleArendBlock && child2.node.elementType == PIPE) return oneCrlf
        }

        if (nodePsi is ArendFunctionBody) {
            if (child1 is AbstractArendBlock && child1.node.elementType == FAT_ARROW) return oneSpaceWrap
            if (needsCrlfInCoClausesBlock(child1, child2)) return oneCrlf
            return super.getSpacing(child1, child2)
        }

        if (nodePsi is ArendNewExpr && needsCrlfInCoClausesBlock(child1, child2)) {
            val whitespace = nodePsi.lbrace?.nextSibling as? PsiWhiteSpace
            return if (whitespace != null && !whitespace.textContains('\n')) oneSpaceWrap else oneCrlf
        }

        if (isCoClauseOrLocalCoClause(nodePsi) && needsCrlfInCoClausesBlock(child1, child2)) return oneCrlf

        if (myNode.psi is ArendFunctionClauses || myNode.psi is ArendCoClauseBody) {
            if ((child1 is AbstractArendBlock && child1.node.elementType == LBRACE) xor
                    (child2 is AbstractArendBlock && child2.node.elementType == RBRACE))
                return oneCrlf
        }

        if (myNode.psi is ArendDefFunction) {
            if (child2 is AbstractArendBlock && child2.node.elementType == FUNCTION_BODY) {
                val child1node = (child1 as? AbstractArendBlock)?.node
                val child2node = (child2 as? AbstractArendBlock)?.node?.psi as? ArendFunctionBody
                if (child1node != null && child2node != null &&
                        !AREND_COMMENTS.contains(child1node.elementType) && child2node.fatArrow != null) return oneSpaceNoWrap
            } else if (child1 is AbstractArendBlock && child2 is AbstractArendBlock) {
                val child1et = child1.node.elementType
                val child2psi = child2.node.psi
                if (child1et == COLON && child2psi is ArendExpr) return oneSpaceWrap
            }
        }

        if (child1 is AbstractBlock && child2 is AbstractBlock) {
            val psi1 = child1.node.psi
            val psi2 = child2.node.psi
            val c1et = child1.node.elementType
            val c2et = child2.node.elementType
            val c1comment = child1 is DocCommentBlock

            if ((AREND_COMMENTS.contains(c1et) || c1comment) && (psi2 is ArendStatement || psi2 is ArendClassStat))
                return (if ((c1et == DOC_COMMENT || c1comment) && (psi2 is ArendStatement && psi2.statCmd == null)) oneCrlf else oneBlankLine)
            else if ((psi1 is ArendStatement || psi1 is ArendClassStat) && (AREND_COMMENTS.contains(c2et) || child2 is DocCommentBlock)) return oneBlankLine

            if (myNode.psi is ArendCaseExpr && (c1et == LBRACE || c2et == RBRACE)) return oneCrlf

            if (myNode.psi is ArendClause && (c1et == FAT_ARROW || c2et == FAT_ARROW)) return oneSpaceWrap

            if (myNode.psi is ArendCoClauseDef && psi1 is ArendNameTele && psi2 is ArendCoClauseBody) return oneSpaceWrap

            if ((nodePsi is ArendNameTele || nodePsi is ArendTypeTele) && (c1et == LBRACE || c2et == RBRACE || c1et == LPAREN || c2et == RPAREN)) return noWhitespace

            if ((myNode.psi is ArendDefinition || myNode.psi is ArendClassStat) && (psi2 is ArendPrec || psi2 is ArendDefIdentifier)) return oneSpaceNoWrap

            if (nodePsi is ArendPrec && (c1et in listOf(INFIX_LEFT_KW, INFIX_NON_KW, INFIX_RIGHT_KW) && c2et == NUMBER)) return oneSpaceNoWrap

            if (nodePsi is ArendDefClass) when {
                c1et == LBRACE && c2et == RBRACE -> return noWhitespace
                c1et == LBRACE && c2et == CLASS_STAT -> return oneCrlf
                c1et == CLASS_STAT && c2et == RBRACE -> return oneCrlf
                c1et == CLASS_STAT && c2et == CLASS_STAT -> if (psi1 is ArendClassStat && psi2 is ArendClassStat) when {
                    (psi1.definition != null) || (psi2.definition != null) -> return oneBlankLine
                }
            }

            return if (psi1 is ArendStatement && psi2 is ArendStatement) {
                if (psi1.statCmd == null || psi2.statCmd == null) oneBlankLine else oneCrlf /* Delimiting blank line between proper statements */
            } else if (psi1 is ArendStatement && c2et == RBRACE ||
                    c1et == LBRACE && (psi2 is ArendStatement || child2 is DocCommentBlock || c2et == DOC_COMMENT)) oneCrlf
            else if ((myNode.psi is ArendNsUsing || myNode.psi is ArendStatCmd)) { /* Spacing rules for hiding/using refs in namespace commands */
                when {
                    (c1et == LPAREN && (c2et == REF_IDENTIFIER || c2et == NS_ID || c2et == RPAREN)) ||
                            ((c1et == REF_IDENTIFIER || c1et == NS_ID) && (c2et == COMMA || c2et == RPAREN)) -> noWhitespace
                    c1et == COMMA ||
                            (c1et == LONG_NAME && c2et == NS_USING) ||
                            ((c1et == LONG_NAME || c1et == NS_USING) && c2et == HIDING_KW) ||
                            ((c1et == HIDING_KW || c1et == USING_KW) && c2et == LPAREN) -> oneSpaceWrap
                    else -> null
                }
            } else if (myNode.psi is ArendNsId && ((c1et == REF_IDENTIFIER && c2et == AS_KW) || (c1et == AS_KW && c2et == DEF_IDENTIFIER))) oneSpaceWrap
            else null
        }

        return null
    }

    override fun getChildAttributes(newChildIndex: Int): ChildAttributes {
        printChildAttributesContext(newChildIndex)

        val nodePsi = node.psi

        if (node.elementType == STATEMENT || nodePsi is ArendFile) return ChildAttributes.DELEGATE_TO_PREV_CHILD

        if (node.elementType == TUPLE && subBlocks.size > 1 && newChildIndex == 1)
            return ChildAttributes(Indent.getNormalIndent(), null)

        val prev2Child = if (newChildIndex > 1) subBlocks[newChildIndex - 2] else null
        val prevChild = if (newChildIndex > 0) subBlocks[newChildIndex - 1] else null
        val nextChild = if (newChildIndex < subBlocks.size) subBlocks[newChildIndex] else null

        if (prevChild is AbstractArendBlock) {
            val prevET = prevChild.node.elementType
            val prev2ET = if (prev2Child is AbstractArendBlock) prev2Child.node.elementType else null

            if (nodePsi is ArendWhere) {
                if (prevET == STATEMENT) return ChildAttributes.DELEGATE_TO_PREV_CHILD
                if (prevET == WHERE_KW || prevET == LBRACE || prevET == ERROR_ELEMENT) return ChildAttributes(Indent.getNormalIndent(), null)
            }

            // Definitions
            if ((nodePsi is ArendDefinition || nodePsi is ArendClassField ||
                            nodePsi is ArendPiExpr || nodePsi is ArendLamExpr || nodePsi is ArendSigmaExpr)
                    && newChildIndex <= subBlocks.size) {
                when (if (prevET == ERROR_ELEMENT) prev2ET else prevET) {
                    TYPE_TELE, NAME_TELE, FIELD_TELE -> {
                        val isLast = if (nextChild is AbstractArendBlock) when (nextChild.node.elementType) {
                            TYPE_TELE, NAME_TELE, FIELD_TELE -> false
                            else -> true
                        } else true
                        val align = (if (prevET == ERROR_ELEMENT) prev2Child else prevChild).let {
                            if (!isLast || it != null && hasLfBefore(it)) it?.alignment else null
                        }
                        return ChildAttributes(prevChild.indent, align)
                    }
                }
            }

            if ((nodePsi is ArendDefinition || nodePsi is ArendDefModule) && prevET == WHERE)
                return ChildAttributes.DELEGATE_TO_PREV_CHILD

            if (nodePsi is ArendDefClass) when (prevET) {
                DEF_IDENTIFIER, LONG_NAME -> return ChildAttributes(Indent.getNormalIndent(), null)
            }

            if (nodePsi is ArendDefData) when (prevET) {
                DEF_IDENTIFIER, UNIVERSE_EXPR, DATA_BODY, TYPE_TELE -> return ChildAttributes(Indent.getNormalIndent(), null)
            }

            if (nodePsi is ArendDefFunction) when (prevET) {
                FUNCTION_BODY -> return ChildAttributes.DELEGATE_TO_PREV_CHILD
            }

            if (nodePsi is ArendDefInstance) {
                when (prevChild.node.psi) {
                    is ArendReturnExpr -> return ChildAttributes(Indent.getNormalIndent(), null)
                    is ArendInstanceBody -> return ChildAttributes.DELEGATE_TO_PREV_CHILD
                    is ArendWhere -> return ChildAttributes(Indent.getNoneIndent(), null)
                }
            }

            // Data and function bodies
            if (nodePsi is ArendDataBody && prevChild.node.psi is ArendElim)
                return ChildAttributes(Indent.getNormalIndent(), null)

            if (nodePsi is ArendFunctionBody) {
                val indent = if (prevChild.node.psi is ArendExpr) return ChildAttributes.DELEGATE_TO_PREV_CHILD else
                    when (prevET) {
                        FUNCTION_CLAUSES, CO_CLAUSE -> return ChildAttributes.DELEGATE_TO_PREV_CHILD
                        else -> Indent.getNormalIndent()
                    }
                return ChildAttributes(indent, null)
            }

            if (nodePsi is ArendFunctionClauses) {
                val clauseAttributes = getChildAttributesInfo(prevChild, prevChild.subBlocks.size)
                if (!(clauseAttributes.childIndent?.type == Indent.Type.NONE && clauseAttributes.alignment == null)) return clauseAttributes
            }
            if (nodePsi is ArendClause || isCoClauseOrLocalCoClause(nodePsi)) return ChildAttributes.DELEGATE_TO_PREV_CHILD

            //Expressions
            when (node.elementType) {
                PI_EXPR, LAM_EXPR -> when (prevET) {
                    ERROR_ELEMENT -> if (newChildIndex > 1) {
                        val sB = subBlocks[newChildIndex - 2]
                        if (sB is AbstractBlock && sB.node.elementType == TYPE_TELE) return ChildAttributes(sB.indent, sB.alignment)
                    }
                    ARROW, FAT_ARROW, PI_KW, LAM_KW, TYPE_TELE, NAME_TELE -> {
                    }
                    else -> return ChildAttributes.DELEGATE_TO_PREV_CHILD
                }
                ARR_EXPR -> return ChildAttributes.DELEGATE_TO_PREV_CHILD
                TUPLE, IMPLICIT_ARGUMENT -> when (prevET) {
                    RPAREN, RBRACE -> {
                    }
                    COMMA -> subBlocks.mapFirstNotNull { it.alignment }?.let {
                        return ChildAttributes(Indent.getNormalIndent(), it)
                    }
                    else -> return ChildAttributes.DELEGATE_TO_PREV_CHILD
                }
                NEW_EXPR -> when (prevET) {
                    LBRACE -> {
                    }
                    else -> return ChildAttributes.DELEGATE_TO_PREV_CHILD
                }
                TUPLE_EXPR -> return ChildAttributes.DELEGATE_TO_PREV_CHILD
                LET_CLAUSE -> if (prevET == FAT_ARROW || prevET == ERROR_ELEMENT && prev2ET == FAT_ARROW) return ChildAttributes(Indent.getNormalIndent(true), null)
            }

            //General case

            val indent = when (prevET) {
                LBRACE -> Indent.getNormalIndent()
                ERROR_ELEMENT -> if (prev2ET == LBRACE) Indent.getNormalIndent() else prevChild.indent
                else -> prevChild.indent
            }

            return ChildAttributes(indent, prevChild.alignment)
        } else if (nodePsi is PsiErrorElement)
            return ChildAttributes(Indent.getNormalIndent(), null)

        return super.getChildAttributes(newChildIndex)
    }

    override fun buildChildren(): MutableList<Block> {
        val blocks = ArrayList<Block>()
        var child: ASTNode? = myNode.firstChildNode
        val alignment = Alignment.createAlignment()
        val alignment2 = Alignment.createAlignment()

        val nodePsi = myNode.psi
        val nodeET = myNode.elementType

        mainLoop@ while (child != null) {
            val childPsi = child.psi
            val childET = child.elementType

            if (childET != WHITE_SPACE) {
                val indent: Indent =
                        if (childPsi is ArendExpr || childPsi is PsiErrorElement) when (nodeET) {
                            CO_CLAUSE, LOCAL_CO_CLAUSE, LET_EXPR, LET_CLAUSE, CLAUSE, FUNCTION_BODY, CLASS_IMPLEMENT -> Indent.getNormalIndent()
                            PI_EXPR, SIGMA_EXPR, LAM_EXPR -> Indent.getContinuationIndent()
                            else -> Indent.getNoneIndent()
                        } else if (AREND_COMMENTS.contains(childET)) when (nodeET) {
                            CO_CLAUSE, LOCAL_CO_CLAUSE, LET_EXPR, LET_CLAUSE, CLAUSE, FUNCTION_BODY,
                            FUNCTION_CLAUSES, CLASS_IMPLEMENT, DATA_BODY, CONSTRUCTOR, DEF_CLASS, CASE_EXPR,
                            DEF_FUNCTION, NEW_EXPR, WHERE, DEF_DATA -> Indent.getNormalIndent()
                            else -> Indent.getNoneIndent()
                        } else if (nodeET == DEF_FUNCTION) {
                            val notFBodyWithClauses = if (childPsi is ArendFunctionBody) childPsi.fatArrow != null else true
                            if ((blocks.size > 0) && notFBodyWithClauses) Indent.getNormalIndent() else Indent.getNoneIndent()
                        } else when (childET) {
                            CO_CLAUSE, LOCAL_CO_CLAUSE, CONSTRUCTOR_CLAUSE, LET_CLAUSE, WHERE, TUPLE_EXPR, CLASS_STAT,
                            NAME_TELE, TYPE_TELE, FIELD_TELE, CASE_ARG -> Indent.getNormalIndent()
                            STATEMENT -> if (nodePsi is ArendFile) Indent.getNoneIndent() else Indent.getNormalIndent()
                            else -> Indent.getNoneIndent()
                        }

                val wrap: Wrap? =
                        if (nodeET == FUNCTION_BODY && childPsi is ArendExpr) Wrap.createWrap(WrapType.NORMAL, false) else null

                val align = when (myNode.elementType) {
                    LET_EXPR ->
                        if (AREND_COMMENTS.contains(childET)) alignment
                        else when (childET) {
                            LET_KW, LETS_KW, IN_KW -> alignment2
                            LET_CLAUSE -> alignment
                            else -> null
                        }
                    IMPLICIT_ARGUMENT -> when (childET) {
                        TUPLE_EXPR -> alignment
                        else -> null
                    }
                    TUPLE -> if ((nodePsi as ArendTuple).tupleExprList.size > 1) when (childET) {
                        TUPLE_EXPR -> alignment
                        else -> null
                    } else null
                    else -> when (childET) {
                        CO_CLAUSE, LOCAL_CO_CLAUSE, CLASS_STAT -> alignment
                        NAME_TELE, TYPE_TELE, FIELD_TELE, CASE_ARG -> alignment2
                        else -> null
                    }
                }

                if (childET == PIPE) when (nodeET) {
                    FUNCTION_CLAUSES, CO_CLAUSE_BODY, LET_EXPR, DATA_BODY, CONSTRUCTOR, DEF_CLASS, CASE_EXPR, CONSTRUCTOR_CLAUSE -> if (nodeET != CONSTRUCTOR_CLAUSE || blocks.size > 0) {
                        val clauseGroup = findClauseGroup(child, null)
                        if (clauseGroup != null) {
                            child = clauseGroup.first.treeNext
                            blocks.add(GroupBlock(settings, clauseGroup.second, null, alignment, Indent.getNormalIndent(), this))
                            continue@mainLoop
                        }
                    }
                }

                if (childET == DOC_COMMENT) {
                    blocks.add(processDocComment(settings, this, alignment, indent, child))
                    child = child.treeNext
                    continue@mainLoop
                }

                blocks.add(createArendBlock(child, wrap, align, indent))
            }
            child = child.treeNext
        }
        return blocks
    }

    private fun findClauseGroup(child: ASTNode, childAlignment: Alignment?): Pair<ASTNode, MutableList<Block>>? {
        var currChild: ASTNode? = child
        val groupNodes = ArrayList<Block>()
        while (currChild != null) {
            if (currChild.elementType != WHITE_SPACE) groupNodes.add(createArendBlock(currChild, null, childAlignment, Indent.getNoneIndent()))
            when (currChild.elementType) {
                CLAUSE, LET_CLAUSE, CONSTRUCTOR, CLASS_FIELD, CLASS_IMPLEMENT -> return Pair(currChild, groupNodes)
            }
            currChild = currChild.treeNext
        }
        return null
    }

    private fun getChildAttributesInfo(block: Block, index: Int): ChildAttributes {
        val childAttributes = block.getChildAttributes(index)

        if (childAttributes === ChildAttributes.DELEGATE_TO_PREV_CHILD) {
            val newBlock = block.subBlocks[index - 1]
            return getChildAttributesInfo(newBlock, newBlock.subBlocks.size)
        }

        return childAttributes
    }

    private fun processDocComment(settings: CommonCodeStyleSettings?, parent: AbstractArendBlock,
                                  globalAlignment: Alignment?, globalIndent: Indent, commentNode: ASTNode): AbstractArendBlock {
        val blocks = ArrayList<Block>()
        val oneSpaceIndent = Indent.getSpaceIndent(1)
        var startOffset = commentNode.startOffset
        var commentText = commentNode.text

        fun parseCommentPiece(index: Int, indent: Indent?, isDash: Boolean) {
            val endOffset = startOffset + index
            blocks.add(CommentPieceBlock(TextRange(startOffset, endOffset), null, indent, isDash))
            commentText = commentText.substring(index)
            startOffset = endOffset
        }

        fun skipWhitespace(whitespaceCondition: (Char) -> Boolean) {
            var k = 0
            while (k < commentText.length && whitespaceCondition(commentText[k])) k++
            commentText = commentText.substring(k)
            startOffset += k
        }

        parseCommentPiece(1, Indent.getNoneIndent(), false)
        while (commentText.isNotEmpty()) {
            if (commentText == "-}") {
                parseCommentPiece(commentText.length, oneSpaceIndent, false)
                break
            }

            if (commentText[0] == '-')
                parseCommentPiece(1, oneSpaceIndent, true)

            if (commentText[0] == ' ') {
                commentText = commentText.substring(1)
                startOffset += 1
            }

            var i = commentText.indexOf("\n")
            if (i == -1) i = commentText.length
            if (i > 0) parseCommentPiece(i, Indent.getNoneIndent(), false)

            skipWhitespace { c -> c.isWhitespace() }
        }

        return DocCommentBlock(settings, blocks, globalAlignment, globalIndent, parent)
    }

    private fun needsCrlfInCoClausesBlock(child1: Block?, child2: Block?): Boolean =
            child1 is SimpleArendBlock && child2 is SimpleArendBlock &&
                    (child1.node.elementType == LBRACE && isCoClauseOrLocalCoClause(child2.node.psi)
                            || isCoClauseOrLocalCoClause(child1.node.psi) && child2.node.elementType == RBRACE)


    private fun isCoClauseOrLocalCoClause(psi : PsiElement) = psi is ArendCoClause || psi is ArendLocalCoClause

    class DocCommentBlock(settings: CommonCodeStyleSettings?, blocks: ArrayList<Block>, globalAlignment: Alignment?, globalIndent: Indent, parent: AbstractArendBlock) : GroupBlock(settings, blocks, null, globalAlignment, globalIndent, parent) {
        override fun getSpacing(child1: Block?, child2: Block): Spacing? {
            if (child1 is CommentPieceBlock && child1.isDash &&
                    !(child2 is CommentPieceBlock && child2.isDash)) {
                return oneSpaceWrap
            }
            return null
        }
    }
}