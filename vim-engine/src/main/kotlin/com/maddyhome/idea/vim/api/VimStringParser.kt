package com.maddyhome.idea.vim.api

import javax.swing.KeyStroke

interface VimStringParser {
  val plugKeyStroke: KeyStroke
  fun parseKeys(vararg strings: String): List<KeyStroke>
  fun stringToKeys(string: String): List<KeyStroke>
  fun toKeyNotation(keyStroke: KeyStroke): String
  fun toKeyNotation(keyStrokes: List<KeyStroke>): String
}