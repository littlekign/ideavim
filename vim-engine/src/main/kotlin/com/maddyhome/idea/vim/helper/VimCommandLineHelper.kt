/*
 * Copyright 2003-2022 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.helper

import com.maddyhome.idea.vim.api.VimEditor

interface VimCommandLineHelper {
  fun inputString(vimEditor: VimEditor, prompt: String, finishOn: Char?): String?
}