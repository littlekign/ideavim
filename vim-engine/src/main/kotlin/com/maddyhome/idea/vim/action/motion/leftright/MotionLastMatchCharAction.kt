/*
 * Copyright 2003-2022 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */
package com.maddyhome.idea.vim.action.motion.leftright

import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimCaret
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.Argument
import com.maddyhome.idea.vim.command.MotionType
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.handler.Motion
import com.maddyhome.idea.vim.handler.MotionActionHandler

class MotionLastMatchCharAction : MotionActionHandler.ForEachCaret() {
  override fun getOffset(
    editor: VimEditor,
    caret: VimCaret,
    context: ExecutionContext,
    argument: Argument?,
    operatorArguments: OperatorArguments,
  ): Motion {
    val repeatLastMatchChar = injector.motion.repeatLastMatchChar(editor, caret, operatorArguments.count1)
    return if (repeatLastMatchChar < 0) Motion.Error else Motion.AbsoluteOffset(repeatLastMatchChar)
  }

  override val motionType: MotionType = MotionType.EXCLUSIVE
}

class MotionLastMatchCharReverseAction : MotionActionHandler.ForEachCaret() {
  override fun getOffset(
    editor: VimEditor,
    caret: VimCaret,
    context: ExecutionContext,
    argument: Argument?,
    operatorArguments: OperatorArguments,
  ): Motion {
    val repeatLastMatchChar = injector.motion.repeatLastMatchChar(editor, caret, -operatorArguments.count1)
    return if (repeatLastMatchChar < 0) Motion.Error else Motion.AbsoluteOffset(repeatLastMatchChar)
  }

  override val motionType: MotionType = MotionType.EXCLUSIVE
}