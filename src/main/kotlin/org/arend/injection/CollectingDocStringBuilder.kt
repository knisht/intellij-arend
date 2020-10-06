package org.arend.injection

import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.openapi.util.TextRange
import org.arend.core.expr.Expression
import org.arend.ext.error.GeneralError
import org.arend.ext.prettyprinting.doc.*
import org.arend.typechecking.error.createHyperlinkInfo


class CollectingDocStringBuilder(private val builder: StringBuilder, private val error: GeneralError?) : DocStringBuilder(builder) {
    val textRanges = ArrayList<List<TextRange>>()
    val hyperlinks = ArrayList<Pair<TextRange,HyperlinkInfo>>()
    val expressions = ArrayList<Expression?>()
    private var last: ArrayList<TextRange>? = null

    override fun visitReference(doc: ReferenceDoc, newLine: Boolean): Void? {
        val start = builder.length
        super.visitReference(doc, newLine)
        val hyperlink = createHyperlinkInfo(doc.reference, error).first
        if (hyperlink != null) {
            hyperlinks.add(Pair(TextRange(start, builder.length), hyperlink))
        }
        last = null
        return null
    }

    override fun visitTermLine(doc: TermLineDoc, newLine: Boolean): Void? {
        expressions.add(doc.term as? Expression)
        val start = builder.length
        super.visitTermLine(doc, newLine)
        textRanges.add(listOf(TextRange(start, builder.length)))
        last = null
        return null
    }

    override fun visitPattern(doc: PatternDoc, newLine: Boolean): Void? {
        expressions.add(null)
        val start = builder.length
        super.visitPattern(doc, newLine)
        textRanges.add(listOf(TextRange(start, builder.length)))
        last = null
        return null
    }

    override fun visitText(doc: TextDoc, newLine: Boolean): Void? {
        if (doc !is TermTextDoc) {
            return super.visitText(doc, newLine)
        }

        if (doc.isFirst) {
            (doc.term as? Expression)?.let { expressions.add(it) }
        }

        if (doc.isFirst || last == null) {
            last = ArrayList()
            textRanges.add(last!!)
        }

        val start = builder.length
        super.visitText(doc, newLine)
        last!!.add(TextRange(start, builder.length))

        return null
    }
}