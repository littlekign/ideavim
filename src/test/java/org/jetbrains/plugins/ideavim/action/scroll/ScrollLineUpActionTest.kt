/*
 * Copyright 2003-2022 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package org.jetbrains.plugins.ideavim.action.scroll

import com.maddyhome.idea.vim.VimPlugin
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.options.OptionConstants
import com.maddyhome.idea.vim.options.OptionScope
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimInt
import org.jetbrains.plugins.ideavim.SkipNeovimReason
import org.jetbrains.plugins.ideavim.TestWithoutNeovim
import org.jetbrains.plugins.ideavim.VimTestCase

/*
                                                       *CTRL-Y*
CTRL-Y                  Scroll window [count] lines upwards in the buffer.
                        The text moves downwards on the screen.
                        Note: When using the MS-Windows key bindings CTRL-Y is
                        remapped to redo.
 */
class ScrollLineUpActionTest : VimTestCase() {
  @TestWithoutNeovim(SkipNeovimReason.SCROLL)
  fun `test scroll single line up`() {
    configureByPages(5)
    setPositionAndScroll(29, 29)
    typeText(injector.parser.parseKeys("<C-Y>"))
    assertPosition(29, 0)
    assertVisibleArea(28, 62)
  }

  @TestWithoutNeovim(SkipNeovimReason.SCROLL)
  fun `test scroll line up will keep cursor on screen`() {
    configureByPages(5)
    setPositionAndScroll(29, 63)
    typeText(injector.parser.parseKeys("<C-Y>"))
    assertPosition(62, 0)
    assertVisibleArea(28, 62)
  }

  @TestWithoutNeovim(SkipNeovimReason.SCROLL)
  fun `test scroll line up will maintain current column at start of line with sidescrolloff`() {
    VimPlugin.getOptionService().setOptionValue(OptionScope.GLOBAL, OptionConstants.sidescrolloffName, VimInt(10))
    configureByPages(5)
    setPositionAndScroll(29, 63, 5)
    typeText(injector.parser.parseKeys("<C-Y>"))
    assertPosition(62, 5)
    assertVisibleArea(28, 62)
  }

  @TestWithoutNeovim(SkipNeovimReason.SCROLL)
  fun `test scroll count lines up`() {
    configureByPages(5)
    setPositionAndScroll(29, 29)
    typeText(injector.parser.parseKeys("10<C-Y>"))
    assertPosition(29, 0)
    assertVisibleArea(19, 53)
  }

  @TestWithoutNeovim(SkipNeovimReason.SCROLL)
  fun `test scroll count lines up will keep cursor on screen`() {
    configureByPages(5)
    setPositionAndScroll(29, 63)
    typeText(injector.parser.parseKeys("10<C-Y>"))
    assertPosition(53, 0)
    assertVisibleArea(19, 53)
  }

  @TestWithoutNeovim(SkipNeovimReason.SCROLL)
  fun `test too many lines up stops at zero`() {
    configureByPages(5)
    setPositionAndScroll(29, 29)
    typeText(injector.parser.parseKeys("100<C-Y>"))
    assertPosition(29, 0)
    assertVisibleArea(0, 34)
  }

  @TestWithoutNeovim(SkipNeovimReason.SCROLL)
  fun `test too many lines up stops at zero and keeps cursor on screen`() {
    configureByPages(5)
    setPositionAndScroll(59, 59)
    typeText(injector.parser.parseKeys("100<C-Y>"))
    assertPosition(34, 0)
    assertVisibleArea(0, 34)
  }

  @TestWithoutNeovim(SkipNeovimReason.SCROLL)
  fun `test scroll up uses scrolloff and moves cursor`() {
    VimPlugin.getOptionService().setOptionValue(OptionScope.GLOBAL, OptionConstants.scrolloffName, VimInt(10))
    configureByPages(5)
    setPositionAndScroll(20, 44)
    typeText(injector.parser.parseKeys("<C-Y>"))
    assertPosition(43, 0)
    assertVisibleArea(19, 53)
  }

  @TestWithoutNeovim(SkipNeovimReason.SCROLL)
  fun `test scroll up is not affected by scrolljump`() {
    VimPlugin.getOptionService().setOptionValue(OptionScope.GLOBAL, OptionConstants.scrolljumpName, VimInt(10))
    configureByPages(5)
    setPositionAndScroll(29, 63)
    typeText(injector.parser.parseKeys("<C-Y>"))
    assertPosition(62, 0)
    assertVisibleArea(28, 62)
  }

  @TestWithoutNeovim(SkipNeovimReason.SCROLL)
  fun `test scroll line up in visual mode`() {
    configureByPages(5)
    setPositionAndScroll(29, 29)
    typeText(injector.parser.parseKeys("Vjjjj" + "<C-Y>"))
    assertVisibleArea(28, 62)
  }

  @TestWithoutNeovim(SkipNeovimReason.SCROLL)
  fun `test scroll line up with virtual space`() {
    configureByLines(100, "    I found it in a legendary land")
    setEditorVirtualSpace()
    setPositionAndScroll(85, 90, 4)
    typeText(injector.parser.parseKeys("<C-Y>"))
    assertVisibleArea(84, 99)
  }

  @TestWithoutNeovim(SkipNeovimReason.SCROLL)
  fun `test scroll line up with virtual space and scrolloff`() {
    VimPlugin.getOptionService().setOptionValue(OptionScope.GLOBAL, OptionConstants.scrolloffName, VimInt(10))
    configureByLines(100, "    I found it in a legendary land")
    setEditorVirtualSpace()
    // Last line is scrolloff from top. <C-Y> should just move last line down
    setPositionAndScroll(89, 99, 4)
    typeText(injector.parser.parseKeys("<C-Y>"))
    assertVisibleArea(88, 99)
    assertVisualPosition(99, 4)
  }

  // This actually works, but the set up puts us in the wrong position
  @TestWithoutNeovim(SkipNeovimReason.SCROLL)
  fun `test scroll line up on last line with scrolloff`() {
    VimPlugin.getOptionService().setOptionValue(OptionScope.GLOBAL, OptionConstants.scrolloffName, VimInt(10))
    configureByLines(100, "    I found it in a legendary land")
    setEditorVirtualSpace()
    setPositionAndScroll(65, 99, 4)
    typeText(injector.parser.parseKeys("<C-Y>"))
    assertVisibleArea(64, 98)
    assertVisualPosition(88, 4) // Moves caret up by scrolloff
  }
}