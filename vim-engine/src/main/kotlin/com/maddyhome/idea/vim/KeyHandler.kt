/*
 * Copyright 2003-2023 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */
package com.maddyhome.idea.vim

import com.maddyhome.idea.vim.action.change.LazyVimCommand
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.globalOptions
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.Command
import com.maddyhome.idea.vim.command.CommandFlags
import com.maddyhome.idea.vim.command.MappingMode
import com.maddyhome.idea.vim.command.MappingProcessor
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.common.CurrentCommandState
import com.maddyhome.idea.vim.diagnostic.VimLogger
import com.maddyhome.idea.vim.diagnostic.trace
import com.maddyhome.idea.vim.diagnostic.vimLogger
import com.maddyhome.idea.vim.helper.vimStateMachine
import com.maddyhome.idea.vim.impl.state.toMappingMode
import com.maddyhome.idea.vim.key.CommandPartNode
import com.maddyhome.idea.vim.key.KeyConsumer
import com.maddyhome.idea.vim.key.KeyStack
import com.maddyhome.idea.vim.key.consumers.CharArgumentConsumer
import com.maddyhome.idea.vim.key.consumers.CommandConsumer
import com.maddyhome.idea.vim.key.consumers.CommandCountConsumer
import com.maddyhome.idea.vim.key.consumers.DeleteCommandConsumer
import com.maddyhome.idea.vim.key.consumers.DigraphConsumer
import com.maddyhome.idea.vim.key.consumers.EditorResetConsumer
import com.maddyhome.idea.vim.key.consumers.ModeInputConsumer
import com.maddyhome.idea.vim.key.consumers.RegisterConsumer
import com.maddyhome.idea.vim.key.consumers.SelectRegisterConsumer
import com.maddyhome.idea.vim.state.KeyHandlerState
import com.maddyhome.idea.vim.state.VimStateMachine
import com.maddyhome.idea.vim.state.mode.Mode
import com.maddyhome.idea.vim.state.mode.ReturnTo
import com.maddyhome.idea.vim.state.mode.returnTo
import javax.swing.KeyStroke

/**
 * This handles every keystroke that the user can argType except those that are still valid hotkeys for various Idea
 * actions. This is a singleton.
 */
// TODO for future refactorings (PR are welcome)
// 1. avoid using handleKeyRecursionCount & shouldRecord
// 2. maybe we can live without allowKeyMappings: Boolean & mappingCompleted: Boolean
public class KeyHandler {
  private val keyConsumers: List<KeyConsumer> = listOf(MappingProcessor, CommandCountConsumer(), DeleteCommandConsumer(), EditorResetConsumer(), CharArgumentConsumer(), RegisterConsumer(), DigraphConsumer(), CommandConsumer(), SelectRegisterConsumer(), ModeInputConsumer())
  private var handleKeyRecursionCount = 0

  public var keyHandlerState: KeyHandlerState = KeyHandlerState()
    private set

  public val keyStack: KeyStack = KeyStack()
  public val modalEntryKeys: MutableList<KeyStroke> = ArrayList()

  /**
   * This is the main key handler for the Vim plugin. Every keystroke not handled directly by Idea is sent here for
   * processing.
   *
   * @param editor  The editor the key was typed into
   * @param key     The keystroke typed by the user
   * @param context The data context
   */
  public fun handleKey(editor: VimEditor, key: KeyStroke, context: ExecutionContext, keyState: KeyHandlerState) {
    handleKey(editor, key, context, allowKeyMappings = true, mappingCompleted = false, keyState)
  }

  /**
   * Handling input keys with additional parameters
   *
   * @param allowKeyMappings - If we allow key mappings or not
   * @param mappingCompleted - if true, we don't check if the mapping is incomplete
   */
  public fun handleKey(
    editor: VimEditor,
    key: KeyStroke,
    context: ExecutionContext,
    allowKeyMappings: Boolean,
    mappingCompleted: Boolean,
    keyState: KeyHandlerState,
  ) {
    val result = processKey(key, editor, allowKeyMappings, mappingCompleted, KeyProcessResult.SynchronousKeyProcessBuilder(keyState))
    if (result is KeyProcessResult.Executable) {
      result.execute(editor, context)
    }
  }

  public fun processKey(
    key: KeyStroke,
    editor: VimEditor,
    allowKeyMappings: Boolean,
    mappingCompleted: Boolean,
    processBuilder: KeyProcessResult.KeyProcessResultBuilder,
  ): KeyProcessResult {
    synchronized(lock) {
      LOG.trace {
        """
        ------- Key Handler -------
        Start key processing. allowKeyMappings: $allowKeyMappings, mappingCompleted: $mappingCompleted
        Key: $key
      """.trimIndent()
      }
      val maxMapDepth = injector.globalOptions().maxmapdepth
      if (handleKeyRecursionCount >= maxMapDepth) {
        processBuilder.addExecutionStep { _, lambdaEditor, _ ->
          LOG.warn("Key handling, maximum recursion of the key received. maxdepth=$maxMapDepth")
          injector.messages.showStatusBarMessage(lambdaEditor, injector.messages.message("E223"))
          injector.messages.indicateError()
        }
        return processBuilder.build()
      }

      injector.messages.clearError()
      // We only record unmapped keystrokes. If we've recursed to handle mapping, don't record anything.
      val shouldRecord = MutableBoolean(handleKeyRecursionCount == 0 && injector.registerGroup.isRecording)

      handleKeyRecursionCount++
      try {
        val isProcessed = keyConsumers.any {
          it.consumeKey(
            key,
            editor,
            allowKeyMappings,
            mappingCompleted,
            processBuilder,
            shouldRecord
          )
        }
        if (isProcessed) {
          processBuilder.addExecutionStep { lambdaKeyState, lambdaEditor, lambdaContext ->
            finishedCommandPreparation(lambdaEditor, lambdaContext, key, shouldRecord, lambdaKeyState)
          }
        } else {
          // Key wasn't processed by any of the consumers, so we reset our key state
          // and tell IDE that the key is Unknown (handle key for us)
          onUnknownKey(editor, processBuilder.state)
          updateState(processBuilder.state)
          return KeyProcessResult.Unknown.apply {
            handleKeyRecursionCount-- // because onFinish will now be executed for unknown
          }
        }
      } finally {
        processBuilder.onFinish = { handleKeyRecursionCount-- }
      }
      return processBuilder.build()
    }
  }

  internal fun finishedCommandPreparation(
    editor: VimEditor,
    context: ExecutionContext,
    key: KeyStroke?,
    shouldRecord: MutableBoolean,
    keyState: KeyHandlerState,
  ) {
    // Do we have a fully entered command at this point? If so, let's execute it.
    val commandBuilder = keyState.commandBuilder

    if (commandBuilder.isReady) {
      LOG.trace("Ready command builder. Execute command.")
      executeCommand(editor, context, editor.vimStateMachine, keyState)
    }

    // Don't record the keystroke that stops the recording (unmapped this is `q`)
    if (shouldRecord.value && injector.registerGroup.isRecording && key != null) {
      injector.registerGroup.recordKeyStroke(key)
      modalEntryKeys.forEach { injector.registerGroup.recordKeyStroke(it) }
      modalEntryKeys.clear()
    }

    // This will update immediately, if we're on the EDT (which we are)
    injector.messages.updateStatusBar(editor)
    LOG.trace("----------- Key Handler Finished -----------")
  }

  private fun onUnknownKey(editor: VimEditor, keyState: KeyHandlerState) {
    keyState.commandBuilder.commandState = CurrentCommandState.BAD_COMMAND
    LOG.trace("Command builder is set to BAD")
    editor.resetOpPending()
    editor.vimStateMachine.resetRegisterPending()
    editor.isReplaceCharacter = false
    reset(keyState, editor.mode)
  }

  public fun setBadCommand(editor: VimEditor, keyState: KeyHandlerState) {
    onUnknownKey(editor, keyState)
    injector.messages.indicateError()
  }

  public fun isDuplicateOperatorKeyStroke(key: KeyStroke, mode: Mode, keyState: KeyHandlerState): Boolean {
    return isOperatorPending(mode, keyState) && keyState.commandBuilder.isDuplicateOperatorKeyStroke(key)
  }

  public fun isOperatorPending(mode: Mode, keyState: KeyHandlerState): Boolean {
    return mode is Mode.OP_PENDING && !keyState.commandBuilder.isEmpty
  }

  private fun executeCommand(
    editor: VimEditor,
    context: ExecutionContext,
    editorState: VimStateMachine,
    keyState: KeyHandlerState,
  ) {
    LOG.trace("Command execution")
    val command = keyState.commandBuilder.buildCommand()
    val operatorArguments = OperatorArguments(
      editor.mode is Mode.OP_PENDING,
      command.rawCount,
      editorState.mode,
    )

    // If we were in "operator pending" mode, reset back to normal mode.
    editor.resetOpPending()

    // Save off the command we are about to execute
    editorState.executingCommand = command
    val type = command.type
    if (type.isWrite) {
      if (!editor.isWritable()) {
        injector.messages.indicateError()
        reset(keyState, editorState.mode)
        LOG.warn("File is not writable")
        return
      }
    }
    if (injector.application.isMainThread()) {
      val action: Runnable = ActionRunner(editor, context, command, keyState, operatorArguments)
      val cmdAction = command.action
      val name = cmdAction.id
      if (type.isWrite) {
        injector.application.runWriteCommand(editor, name, action, action)
      } else if (type.isRead) {
        injector.application.runReadCommand(editor, name, action, action)
      } else {
        injector.actionExecutor.executeCommand(editor, action, name, action)
      }
    }
  }

  /**
   * Partially resets the state of this handler. Resets the command count, clears the key list, resets the key tree
   * node to the root for the current mode we are in.
   *
   * @param editor The editor to reset.
   */
  public fun partialReset(editor: VimEditor) {
    partialReset(keyHandlerState, editor.mode)
  }

  // TODO replace with com.maddyhome.idea.vim.state.KeyHandlerState#partialReset
  private fun partialReset(keyState: KeyHandlerState, mode: Mode) {
    keyState.mappingState.resetMappingSequence()
    keyState.commandBuilder.resetInProgressCommandPart(getKeyRoot(mode.toMappingMode()))
  }

  /**
   * Resets the state of this handler. Does a partial reset then resets the mode, the command, and the argument.
   *
   * @param editor The editor to reset.
   */
  // TODO replace with com.maddyhome.idea.vim.state.KeyHandlerState#reset
  public fun reset(editor: VimEditor) {
    partialReset(keyHandlerState, editor.mode)
    keyHandlerState.commandBuilder.resetAll(getKeyRoot(editor.mode.toMappingMode()))
  }

  // TODO replace with com.maddyhome.idea.vim.state.KeyHandlerState#reset
  public fun reset(keyState: KeyHandlerState, mode: Mode) {
    partialReset(keyState, mode)
    keyState.commandBuilder.resetAll(getKeyRoot(mode.toMappingMode()))
  }

  private fun getKeyRoot(mappingMode: MappingMode): CommandPartNode<LazyVimCommand> {
    return injector.keyGroup.getKeyRoot(mappingMode)
  }

  public fun updateState(keyState: KeyHandlerState) {
    this.keyHandlerState = keyState
  }

  /**
   * Completely resets the state of this handler. Resets the command mode to normal, resets, and clears the selected
   * register.
   *
   * @param editor The editor to reset.
   */
  public fun fullReset(editor: VimEditor) {
    injector.messages.clearError()
    editor.resetState()
    reset(keyHandlerState, editor.mode)
    injector.registerGroupIfCreated?.resetRegister()
    editor.removeSelection()
  }

  public fun setPromptCharacterEx(promptCharacter: Char) {
    val exEntryPanel = injector.exEntryPanel
    if (exEntryPanel.isActive()) {
      exEntryPanel.setCurrentActionPromptCharacter(promptCharacter)
    }
  }

  /**
   * This was used as an experiment to execute actions as a runnable.
   */
  internal class ActionRunner(
    val editor: VimEditor,
    val context: ExecutionContext,
    val cmd: Command,
    val keyState: KeyHandlerState,
    val operatorArguments: OperatorArguments,
  ) : Runnable {
    override fun run() {
      val editorState = VimStateMachine.getInstance(editor)
      keyState.commandBuilder.commandState = CurrentCommandState.NEW_COMMAND
      val register = cmd.register
      if (register != null) {
        injector.registerGroup.selectRegister(register)
      }
      injector.actionExecutor.executeVimAction(editor, cmd.action, context, operatorArguments)
      if (editorState.mode is Mode.INSERT || editorState.mode is Mode.REPLACE) {
        injector.changeGroup.processCommand(editor, cmd)
      }

      // Now the command has been executed let's clean up a few things.

      // By default, the "empty" register is used by all commands, so we want to reset whatever the last register
      // selected by the user was to the empty register
      injector.registerGroup.resetRegister()

      // If, at this point, we are not in insert, replace, or visual modes, we need to restore the previous
      // mode we were in. This handles commands in those modes that temporarily allow us to execute normal
      // mode commands. An exception is if this command should leave us in the temporary mode such as
      // "select register"
      val myMode = editorState.mode
      val returnTo = myMode.returnTo
      if (myMode is Mode.NORMAL && returnTo != null && !cmd.flags.contains(CommandFlags.FLAG_EXPECT_MORE)) {
        when (returnTo) {
          ReturnTo.INSERT -> {
            editor.mode = Mode.INSERT
          }

          ReturnTo.REPLACE -> {
            editor.mode = Mode.REPLACE
          }
        }
      }
      if (keyState.commandBuilder.isDone()) {
        getInstance().reset(keyState, editorState.mode)
      }
    }
  }

  public companion object {
    public val lock: Any = Object()
    private val LOG: VimLogger = vimLogger<KeyHandler>()

    internal fun <T> isPrefix(list1: List<T>, list2: List<T>): Boolean {
      if (list1.size > list2.size) {
        return false
      }
      for (i in list1.indices) {
        if (list1[i] != list2[i]) {
          return false
        }
      }
      return true
    }

    private val instance = KeyHandler()

    @JvmStatic
    public fun getInstance(): KeyHandler = instance
  }

  public data class MutableBoolean(public var value: Boolean)
}

/**
 * This class was created to manage Fleet input processing.
 * Fleet needs to synchronously determine if the key will be handled by the plugin or should be passed elsewhere.
 * The key processing itself will be executed asynchronously at a later time.
 */
public sealed interface KeyProcessResult {
  /**
   * Key input that is not recognized by IdeaVim and should be passed to IDE.
   */
  public object Unknown: KeyProcessResult

  /**
   * Key input that is recognized by IdeaVim and can be executed.
   * Key handling is a two-step process:
   * 1. Determine if the key should be processed and how (is it a command, mapping, or something else).
   * 2. Execute the recognized command.
   * This class should be returned after the first step is complete.
   * It will continue the key handling and finish the process.
   */
  public class Executable(
    private val originalState: KeyHandlerState,
    private val preProcessState: KeyHandlerState,
    private val processing: KeyProcessing,
  ): KeyProcessResult {

    public companion object {
      private val logger = vimLogger<KeyProcessResult>()
    }

    public fun execute(editor: VimEditor, context: ExecutionContext) {
      synchronized(KeyHandler.lock) {
        val keyHandler = KeyHandler.getInstance()
        if (keyHandler.keyHandlerState != originalState) {
          logger.warn("Unexpected editor state. Aborting command execution.")
        }
        processing(preProcessState, editor, context)
        keyHandler.updateState(preProcessState)
      }
    }
  }

  public abstract class KeyProcessResultBuilder {
    public abstract val state: KeyHandlerState
    protected val processings: MutableList<KeyProcessing> = mutableListOf()
    public var onFinish: (() -> Unit)? = null // FIXME I'm a dirty hack to support recursion counter

    public fun addExecutionStep(keyProcessing: KeyProcessing) {
      processings.add(keyProcessing)
    }

    public abstract fun build(): KeyProcessResult
  }

  // Works with existing state and modifies it during execution
  // It's the way IdeaVim worked for the long time and for this class we do not create
  // unnecessary objects and assume that the code will be executed immediately
  public class SynchronousKeyProcessBuilder(public override val state: KeyHandlerState): KeyProcessResultBuilder() {
    public override fun build(): KeyProcessResult {
      return Executable(state, state) { keyHandlerState, vimEditor, executionContext ->
        try {
          for (processing in processings) {
            processing(keyHandlerState, vimEditor, executionContext)
          }
        } finally {
          onFinish?.let { it() }
        }
      }
    }
  }

  // Created new state, nothing is modified during the builder work (key processing)
  // The new state will be applied later, when we run KeyProcess (it may not be run at all)
  public class AsyncKeyProcessBuilder(originalState: KeyHandlerState): KeyProcessResultBuilder() {
    private val originalState: KeyHandlerState = KeyHandler.getInstance().keyHandlerState
    public override val state: KeyHandlerState = originalState.clone()

    public override fun build(): KeyProcessResult {
      return Executable(originalState, state) { keyHandlerState, vimEditor, executionContext ->
        try {
          for (processing in processings) {
            processing(keyHandlerState, vimEditor, executionContext)
          }
        } finally {
          onFinish?.let { it() }
          KeyHandler.getInstance().updateState(state)
        }
      }
    }
  }
}

public typealias KeyProcessing = (KeyHandlerState, VimEditor, ExecutionContext) -> Unit
