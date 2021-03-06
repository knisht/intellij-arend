package org.arend.actions

import com.intellij.codeInsight.actions.BaseCodeInsightAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiUtilCore
import com.intellij.util.ui.accessibility.ScreenReader
import org.arend.ArendLanguage
import org.jetbrains.annotations.Nls
import java.awt.event.KeyEvent


/**
 * @see [com.intellij.codeInsight.hint.actions.ShowExpressionTypeAction]
 */
abstract class ArendPopupAction : BaseCodeInsightAction() {
    companion object {
        @Nls const val NF = "(Normalized)"
    }

    /**
     * @see [com.intellij.codeInsight.hint.actions.ShowExpressionTypeAction.myRequestFocus]
     */
    protected var requestFocus = false
        private set

    init {
        isEnabledInModalContext = true
    }

    override fun beforeActionPerformedUpdate(e: AnActionEvent) {
        super.beforeActionPerformedUpdate(e)
        // The tooltip gets the focus if using a screen reader and invocation through a keyboard shortcut.
        requestFocus = ScreenReader.isActive() && e.inputEvent is KeyEvent
    }

    override fun isValidForFile(project: Project, editor: Editor, file: PsiFile): Boolean {
        val language = PsiUtilCore.getLanguageAtOffset(file, editor.caretModel.offset)
        return language == ArendLanguage.INSTANCE
    }
}