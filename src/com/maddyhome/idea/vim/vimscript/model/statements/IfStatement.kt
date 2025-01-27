package com.maddyhome.idea.vim.vimscript.model.statements

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.maddyhome.idea.vim.vimscript.model.Executable
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult
import com.maddyhome.idea.vim.vimscript.model.VimContext
import com.maddyhome.idea.vim.vimscript.model.expressions.Expression

data class IfStatement(val conditionToBody: List<Pair<Expression, List<Executable>>>) : Executable {

  override fun execute(editor: Editor, context: DataContext, vimContext: VimContext): ExecutionResult {
    var result: ExecutionResult = ExecutionResult.Success
    var statementsToExecute: List<Executable>? = null
    for ((condition, statements) in conditionToBody) {
      if (condition.evaluate(editor, context, vimContext).asBoolean()) {
        statementsToExecute = statements
        break
      }
    }
    if (statementsToExecute != null) {
      for (statement in statementsToExecute) {
        if (result is ExecutionResult.Success) {
          result = statement.execute(editor, context, vimContext)
        } else {
          break
        }
      }
    }
    return result
  }
}
