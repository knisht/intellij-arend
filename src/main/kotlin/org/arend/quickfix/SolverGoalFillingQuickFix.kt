package org.arend.quickfix

import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import org.arend.core.expr.visitor.ScopeDefinitionRenamer
import org.arend.naming.scope.ConvertingScope
import org.arend.psi.ArendExpr
import org.arend.refactoring.replaceExprSmart
import org.arend.term.concrete.Concrete
import org.arend.term.prettyprint.DefinitionRenamerConcreteVisitor
import org.arend.typechecking.TypeCheckingService
import org.arend.typechecking.error.ErrorService
import org.arend.typechecking.error.local.GoalError
import org.arend.typechecking.visitor.CheckTypeVisitor
import org.arend.ui.impl.ArendEditorUI

class SolverGoalFillingQuickFix(private val element: ArendExpr, private val goal: GoalError) : IntentionAction {
    override fun invoke(project: Project, editor: Editor, file: PsiFile?) {
        if (goal.result != null) {
            invokeOnConcrete(goal.result, project, editor)
        } else {
            if (goal.typecheckingContext == null) {
                cannotSolve(editor)
                return
            }
            goal.goalSolver.trySolve(CheckTypeVisitor.loadTypecheckingContext(goal.typecheckingContext, project.service<TypeCheckingService>().typecheckerState, project.service<ErrorService>()), goal.causeSourceNode, goal.expectedType, ArendEditorUI(project, editor)) {
                if (it !is Concrete.Expression) throw IllegalArgumentException()
                CommandProcessor.getInstance().executeCommand(project, {
                    invokeOnConcrete(it, project, editor)
                }, text, null, editor.document)
            }
        }
    }

    private fun cannotSolve(editor: Editor) {
        ApplicationManager.getApplication().invokeLater {
            if (!editor.isDisposed) {
                HintManager.getInstance().showErrorHint(editor, "Cannot solve goal")
            }
        }
    }

    private fun invokeOnConcrete(concrete: Concrete.Expression, project: Project, editor: Editor) {
        runReadAction {
            if (element.isValid && !editor.isDisposed) {
                val text = concrete.accept(DefinitionRenamerConcreteVisitor(ScopeDefinitionRenamer(ConvertingScope(project.service<TypeCheckingService>().newReferableConverter(false), element.scope))), null).toString()
                ApplicationManager.getApplication().runWriteAction {
                    replaceExprSmart(editor.document, element, null, element.textRange, null, concrete, text)
                }
            }
        }
    }

    override fun startInWriteAction() = false

    override fun getElementToMakeWritable(currentFile: PsiFile) = currentFile

    override fun getFamilyName() = "arend.goal"

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?) =
        element.isValid && (goal.result != null || goal.goalSolver?.willTrySolve(goal.causeSourceNode, goal.expectedType) == true)

    override fun getText() = if (goal.result != null) "Fill goal" else "Solve goal"
}