package org.arend.highlight

import com.intellij.codeHighlighting.TextEditorHighlightingPassRegistrar
import com.intellij.codeInsight.daemon.impl.DefaultHighlightInfoProcessor
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import org.arend.editor.ArendOptions
import org.arend.psi.ArendFile
import org.arend.psi.ext.impl.ArendGroup

class SilentTypecheckerPassFactory(highlightingPassRegistrar: TextEditorHighlightingPassRegistrar, highlightingPassFactory: ArendHighlightingPassFactory) : BasePassFactory() {
    private val passId = highlightingPassRegistrar.registerTextEditorHighlightingPass(this, intArrayOf(highlightingPassFactory.passId), null, false, -1)
    private val ignoredPassIds = listOf(passId, highlightingPassFactory.passId)

    override fun createPass(file: ArendFile, group: ArendGroup, editor: Editor, textRange: TextRange): SilentTypecheckerPass {
        val isSmart = ArendOptions.instance.typecheckingMode == ArendOptions.TypecheckingMode.SMART
        return SilentTypecheckerPass(file, if (isSmart) file else group, editor, if (isSmart) TextRange(0, editor.document.textLength) else textRange, DefaultHighlightInfoProcessor(), ignoredPassIds)
    }

    override fun createHighlightingPass(file: PsiFile, editor: Editor) =
        if (ArendOptions.instance.typecheckingMode != ArendOptions.TypecheckingMode.OFF) super.createHighlightingPass(file, editor) else null

    override fun getPassId() = passId
}