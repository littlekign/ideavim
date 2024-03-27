/*
 * Copyright 2003-2023 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */
package com.maddyhome.idea.vim.ex.ranges

import com.maddyhome.idea.vim.api.VimCaret
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.getLineEndOffset
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.common.TextRange
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.TestOnly
import kotlin.math.min

/**
 * Handles the set of range values entered as part of an Ex command.
 */
public class Range {
  // This property should be private, but is used in tests
  @TestOnly
  public val addresses: MutableList<Address> = mutableListOf()

  private var defaultLine = -1

  /** Adds a range to the list */
  public fun addAddresses(range: Array<Address>) {
    addresses.addAll(range)
  }

  /** Gets the number of ranges in the list */
  public fun size(): Int = addresses.size

  /**
   * Sets the default line to be used by this range if no range was actually given by the user. -1 is used to
   * mean the current line.
   *
   * @param line The line or -1 for current line
   */
  public fun setDefaultLine(line: Int) {
    defaultLine = line
  }

  /**
   * If a command expects a line, Vim uses the last line of any range passed to the command
   *
   * @param editor  The editor to get the line for
   * @return The line number represented by the range
   */
  public fun getLine(editor: VimEditor): Int {
    return processRange(editor).endLine
  }

  /**
   * If a command expects a line, Vim uses the last line of any range passed to the command
   *
   * @param editor  The editor to get the line for
   * @param caret   The caret to use for current line, initial search line, etc. if required
   * @return The line number represented by the range
   */
  public fun getLine(editor: VimEditor, caret: VimCaret): Int {
    return processRange(editor, caret).endLine
  }

  // TODO: Consider removing this
  // Most commands use either a range or a line which is the last line in a range. There should be no need to get the
  // first line separate from a range
  public fun getFirstLine(editor: VimEditor, caret: VimCaret): Int {
    return processRange(editor, caret).startLine
  }

  /**
   * If a command expects a count, Vim uses the last line of the range passed to the command
   *
   * Note that the command may also have a count passed as an argument, which takes precedence over any range. This
   * function only returns the count from the range. It is up to the caller to decide which count to use.
   *
   * @param editor  The editor to get the count for
   * @param count   The count given at the end of the command or -1 if not provided
   * @return count if count != -1, else return end line of range
   */
  public fun getCount(editor: VimEditor, count: Int): Int {
    return if (count == -1) getLine(editor) else count
  }

  /**
   * If a command expects a count, Vim uses the last line of the range passed to the command
   *
   * Note that the command may also have a count passed as an argument, which takes precedence over any range. This
   * function only returns the count from the range. It is up to the caller to decide which count to use.
   *
   * @param editor  The editor to get the count for
   * @param caret   The caret to use for current line, initial search line, etc. if required
   * @param count   The count given at the end of the command or -1 if not provided
   * @return count if count != -1, else return end line of range
   */
  public fun getCount(editor: VimEditor, caret: VimCaret, count: Int): Int {
    return if (count == -1) getLine(editor, caret) else count
  }

  /**
   * Gets the line range represented by this Ex range. If a count is given, the range is the range end line through
   * count-1 lines. If no count is given (-1), the range is the range given by the user.
   *
   * @param editor  The editor to get the range for
   * @param count   The count given at the end of the command or -1 if not provided
   * @return The line range
   */
  public fun getLineRange(editor: VimEditor, count: Int): LineRange {
    val lineRange = processRange(editor)
    return if (count == -1) {
      lineRange
    } else {
      LineRange(lineRange.endLine, lineRange.endLine + count - 1)
    }
  }

  public fun getLineRange(editor: VimEditor, caret: VimCaret, count: Int): LineRange {
    val lineRange = processRange(editor, caret)
    return if (count == -1) lineRange else LineRange(lineRange.endLine, lineRange.endLine + count - 1)
  }

  /**
   * Gets the text range represented by this range. If a count is given, the range is the range end line through
   * count-1 lines. If no count is given (-1), the range is the range given by the user. The text range is based
   * on the line range but this is character based from the start of the first line to the end of the last line.
   *
   * @param editor  The editor to get the range for
   * @param count   The count given at the end of the command or -1 if no such count
   * @return The text range
   */
  // TODO: Consider removing this
  // TextRange isn't a Vim range, but offsets, so isn't related to Ranges. Consider an extension function on LineRange
  public fun getTextRange(editor: VimEditor, count: Int): TextRange {
    val lr = getLineRange(editor, count)
    val start = editor.getLineStartOffset(lr.startLine)
    val end = editor.getLineEndOffset(lr.endLine, true) + 1
    return TextRange(start, min(end, editor.fileSize().toInt()))
  }

  // TODO: Consider removing this
  // TextRange isn't a Vim range, but offsets, so isn't related to Ranges. Consider an extension function on LineRange
  public fun getTextRange(editor: VimEditor, caret: VimCaret, count: Int): TextRange {
    val lineRange = getLineRange(editor, caret, count)
    val start = editor.getLineStartOffset(lineRange.startLine)
    val end = editor.getLineEndOffset(lineRange.endLine, true) + 1
    return TextRange(start, min(end, editor.fileSize().toInt()))
  }

  private fun processRange(editor: VimEditor): LineRange {
    return processRange(editor, editor.primaryCaret())
  }

  private fun processRange(editor: VimEditor, caret: VimCaret): LineRange {
    // Start with the range being the current line
    var startLine = if (defaultLine == -1) caret.getBufferPosition().line else defaultLine
    var endLine = startLine

    // Now process each range component, moving the cursor if appropriate
    var count = 0
    var lastZero = false
    for (address in addresses) {
      startLine = endLine
      endLine = address.getLine(editor, caret, lastZero)
      if (address.isMove) {
        caret.moveToOffset(injector.motion.moveCaretToLineWithSameColumn(editor, endLine, caret))
      }

      // TODO: Reconsider lastZero. I don't think it helps, and might actually cause problems
      // Did that last address represent the start of the file?
      lastZero = endLine < 0
      ++count
    }

    // If only one address is given, make the start and end the same
    if (count == 1) startLine = endLine

    return LineRange(startLine, endLine)
  }

  @NonNls
  override fun toString(): String = "Ranges[addresses=$addresses]"
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is Range) return false

    if (defaultLine != other.defaultLine) return false
    if (addresses != other.addresses) return false

    return true
  }

  override fun hashCode(): Int {
    var result = defaultLine
    result = 31 * result + defaultLine
    result = 31 * result + addresses.hashCode()
    return result
  }
}
