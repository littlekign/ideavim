/*
 * Copyright 2003-2022 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package org.jetbrains.plugins.ideavim.propertybased

import com.intellij.openapi.editor.Editor
import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.VimPlugin
import com.maddyhome.idea.vim.group.MotionGroup
import com.maddyhome.idea.vim.helper.vimStateMachine
import com.maddyhome.idea.vim.newapi.vim
import org.jetbrains.jetCheck.Generator
import org.jetbrains.jetCheck.ImperativeCommand
import org.jetbrains.plugins.ideavim.VimTestCase

abstract class VimPropertyTestBase : VimTestCase() {
  protected fun moveCaretToRandomPlace(env: ImperativeCommand.Environment, editor: Editor) {
    val pos = env.generateValue(Generator.integers(0, editor.document.textLength - 1), "Put caret at position %s")
    MotionGroup.moveCaret(editor, editor.caretModel.currentCaret, pos)
  }

  protected fun reset(editor: Editor) {
    editor.vim.vimStateMachine.mappingState.resetMappingSequence()
    VimPlugin.getKey().resetKeyMappings()

    KeyHandler.getInstance().fullReset(editor.vim)
    VimPlugin.getRegister().resetRegisters()
    editor.caretModel.runForEachCaret { it.moveToOffset(0) }

    editor.vim.vimStateMachine.resetDigraph()
    VimPlugin.getSearch().resetState()
    VimPlugin.getChange().reset()
  }
}