package com.netha.keyboardultra

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.Future

data class ClipboardEntry(
    val text: String,
    val pinned: Boolean,
    val copiedAt: Long
)

class NethaKeyboardService : InputMethodService() {
    private lateinit var clipboard: ClipboardManager
    private var surface: KeyboardSurface? = null
    private val handler = Handler(Looper.getMainLooper())
    private val composing = StringBuilder()
    private val suggestionExecutor = Executors.newFixedThreadPool(2)
    private var suggestionTask: Future<*>? = null
    private var suggestionDebounce: Runnable? = null
    private var suggestionGeneration = 0
    private var composingSinhala = true
    private var onlineSuggestionsAllowed = false
    private var sinhalaMode = true
    private var shiftOn = false
    private var clipboardListening = false

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        captureClipboard()
    }

    override fun onCreate() {
        super.onCreate()
        clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        sinhalaMode = KeyboardPrefs.get(this).getBoolean(KeyboardPrefs.LANGUAGE_SINHALA, true)
    }

    override fun onCreateInputView(): View {
        val keyboard = KeyboardSurface(
            context = this,
            onLetter = { typeLetter(it) },
            onSuggestionSelected = { commitSuggestion(it) },
            onAction = { handleAction(it) },
            onHistoryRequest = { showClipboardHistory() },
            onPasteHistory = { pasteHistoryItem(it) },
            onPinHistory = { togglePinned(it) },
            onDeleteHistory = { deleteHistoryItem(it) }
        )
        surface = keyboard
        return keyboard
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        composing.clear()
        composingSinhala = sinhalaMode
        cancelOnlineSuggestions(clearUi = false)
        sinhalaMode = KeyboardPrefs.get(this).getBoolean(KeyboardPrefs.LANGUAGE_SINHALA, true)
        composingSinhala = sinhalaMode
        surface?.setLanguage(sinhalaMode)
        surface?.setSuggestions(emptyList(), "Online word suggestions")
        surface?.refreshAppearance()
        val inputType = info?.inputType ?: 0
        val inputClass = inputType and android.text.InputType.TYPE_MASK_CLASS
        val variation = inputType and android.text.InputType.TYPE_MASK_VARIATION
        val isPassword = variation == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        val isPrivateVariation = variation == android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == android.text.InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
            variation == android.text.InputType.TYPE_TEXT_VARIATION_URI
        val noSuggestions = (inputType and android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0
        onlineSuggestionsAllowed = KeyboardPrefs.onlineSuggestionsEnabled(this) &&
            inputClass == android.text.InputType.TYPE_CLASS_TEXT &&
            !isPassword && !isPrivateVariation && !noSuggestions
        if (!isPassword) {
            startClipboardListening()
        } else {
            stopClipboardListening()
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        finishComposing()
        stopClipboardListening()
        surface?.hideClipboardHistory()
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        stopClipboardListening()
        cancelOnlineSuggestions(clearUi = false)
        handler.removeCallbacksAndMessages(null)
        suggestionExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun startClipboardListening() {
        if (clipboardListening) return
        try {
            clipboard.addPrimaryClipChangedListener(clipboardListener)
            clipboardListening = true
        } catch (_: Exception) {
            clipboardListening = false
        }
    }

    private fun stopClipboardListening() {
        if (!clipboardListening) return
        try {
            clipboard.removePrimaryClipChangedListener(clipboardListener)
        } catch (_: Exception) { }
        clipboardListening = false
    }

    private fun captureClipboard() {
        try {
            val clip = clipboard.primaryClip ?: return
            if (clip.itemCount == 0) return
            val value = clip.getItemAt(0).coerceToText(this)?.toString()?.trim() ?: return
            if (value.isEmpty() || value.length > 4000) return
            val items = readHistory().toMutableList()
            if (items.firstOrNull()?.text == value) return
            items.add(0, ClipboardEntry(value, false, System.currentTimeMillis()))
            while (items.size > 35) {
                val lastUnpinned = items.indexOfLast { !it.pinned }
                if (lastUnpinned < 0) break
                items.removeAt(lastUnpinned)
            }
            saveHistory(items)
            surface?.updateClipboardHistory(items)
        } catch (_: Exception) {
            // Clipboard access can be restricted by Android or the current editor.
        }
    }

    private fun readHistory(): List<ClipboardEntry> {
        return try {
            val raw = KeyboardPrefs.get(this).getString(KeyboardPrefs.CLIP_HISTORY, "[]") ?: "[]"
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val value = item.optString("text")
                    if (value.isNotEmpty()) {
                        add(ClipboardEntry(value, item.optBoolean("pinned"), item.optLong("time")))
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveHistory(items: List<ClipboardEntry>) {
        try {
            val array = JSONArray()
            items.forEach { item ->
                array.put(JSONObject().put("text", item.text).put("pinned", item.pinned).put("time", item.copiedAt))
            }
            KeyboardPrefs.get(this).edit().putString(KeyboardPrefs.CLIP_HISTORY, array.toString()).apply()
        } catch (_: Exception) { }
    }

    private fun showClipboardHistory() {
        surface?.showClipboardHistory(readHistory())
    }

    private fun togglePinned(text: String) {
        val updated = readHistory().map { item ->
            if (item.text == text) item.copy(pinned = !item.pinned) else item
        }
        saveHistory(updated)
        surface?.showClipboardHistory(updated)
    }

    private fun deleteHistoryItem(text: String) {
        val updated = readHistory().filterNot { it.text == text }
        saveHistory(updated)
        surface?.showClipboardHistory(updated)
    }

    private fun pasteHistoryItem(text: String) {
        finishComposing()
        currentInputConnection?.commitText(text, 1)
        surface?.hideClipboardHistory()
    }

    private fun typeLetter(raw: String) {
        if (raw.length != 1) {
            typeText(raw)
            return
        }
        val wasShifted = shiftOn
        val value = if (wasShifted) raw.uppercase() else raw.lowercase()
        if (wasShifted) {
            shiftOn = false
            surface?.setShiftState(false)
        }

        if (!value[0].isLetter()) {
            typeText(value)
            return
        }

        if (composing.isNotEmpty() && composingSinhala != sinhalaMode) {
            finishComposing()
        }
        if (composing.isEmpty()) composingSinhala = sinhalaMode

        composing.append(value)
        val currentWord = composing.toString()
        val display = if (composingSinhala) {
            SinhalaTransliterator.convert(currentWord)
        } else {
            currentWord
        }
        currentInputConnection?.setComposingText(display, 1)
        scheduleOnlineSuggestions(currentWord)
    }

    private fun typeText(value: String) {
        finishComposing()
        currentInputConnection?.commitText(value, 1)
        cancelOnlineSuggestions(clearUi = true)
    }

    private fun commitSuggestion(word: String) {
        val connection = currentInputConnection ?: return
        // Replaces the active composing word without touching earlier message text.
        connection.commitText(word, 1)
        composing.clear()
        cancelOnlineSuggestions(clearUi = true)
    }

    private fun finishComposing(addTrailingSpace: Boolean = false) {
        val connection = currentInputConnection ?: return
        cancelOnlineSuggestions(clearUi = true)
        if (composing.isNotEmpty()) {
            val completedWord = if (composingSinhala) {
                SinhalaTransliterator.commit(composing.toString())
            } else {
                composing.toString()
            }
            composing.clear()
            // commitText finalizes the active composing region itself.
            // Avoid a second cross-process finishComposingText call.
            connection.commitText(completedWord + if (addTrailingSpace) " " else "", 1)
        } else if (addTrailingSpace) {
            connection.commitText(" ", 1)
        }
    }

    private fun scheduleOnlineSuggestions(prefix: String) {
        cancelOnlineSuggestions(clearUi = false)
        if (!onlineSuggestionsAllowed || prefix.length < 2 || prefix.length > 40 ||
            prefix.any { !it.isLetter() }) {
            surface?.setSuggestions(emptyList())
            return
        }

        val requestId = suggestionGeneration
        val useSinhala = composingSinhala
        surface?.setSuggestions(emptyList(), "Loading online suggestions…")
        val debounce = Runnable {
            suggestionTask = suggestionExecutor.submit {
                val candidates = OnlineSuggestionClient.fetch(prefix, useSinhala, 15)
                handler.post {
                    if (requestId == suggestionGeneration &&
                        composing.toString() == prefix &&
                        onlineSuggestionsAllowed &&
                        useSinhala == composingSinhala) {
                        surface?.setSuggestions(candidates)
                    }
                }
            }
        }
        suggestionDebounce = debounce
        handler.postDelayed(debounce, 230L)
    }

    private fun cancelOnlineSuggestions(clearUi: Boolean) {
        suggestionGeneration++
        suggestionDebounce?.let(handler::removeCallbacks)
        suggestionDebounce = null
        suggestionTask?.cancel(true)
        suggestionTask = null
        if (clearUi) surface?.setSuggestions(emptyList())
    }

    private fun deleteOne() {
        val connection = currentInputConnection ?: return
        if (composing.isNotEmpty()) {
            composing.deleteCharAt(composing.length - 1)
            if (composing.isEmpty()) {
                connection.setComposingText("", 1)
                connection.finishComposingText()
                cancelOnlineSuggestions(clearUi = true)
            } else {
                val currentWord = composing.toString()
                val display = if (composingSinhala) {
                    SinhalaTransliterator.convert(currentWord)
                } else {
                    currentWord
                }
                connection.setComposingText(display, 1)
                scheduleOnlineSuggestions(currentWord)
            }
            return
        }

        try {
            val selected = connection.getSelectedText(0)
            if (!selected.isNullOrEmpty()) {
                connection.commitText("", 1)
                cancelOnlineSuggestions(clearUi = true)
                return
            }
        } catch (_: Exception) { }

        try {
            if (Build.VERSION.SDK_INT >= 24) {
                connection.deleteSurroundingTextInCodePoints(1, 0)
            } else {
                connection.deleteSurroundingText(1, 0)
            }
        } catch (_: Exception) {
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        }
    }

    private fun handleAction(action: String) {
        when (action) {
            "SPACE" -> finishComposing(addTrailingSpace = true)
            "DELETE" -> deleteOne()
            "SHIFT" -> {
                shiftOn = !shiftOn
                surface?.setShiftState(shiftOn)
            }
            "LANGUAGE" -> {
                if (composing.isNotEmpty()) finishComposing()
                surface?.setSuggestions(emptyList())
                sinhalaMode = !sinhalaMode
                KeyboardPrefs.get(this).edit().putBoolean(KeyboardPrefs.LANGUAGE_SINHALA, sinhalaMode).apply()
                surface?.setLanguage(sinhalaMode)
            }
            "ENTER" -> {
                finishComposing()
                val editorInfo = currentInputEditorInfo
                val imeOptions = editorInfo?.imeOptions ?: EditorInfo.IME_ACTION_NONE
                val actionId = imeOptions and EditorInfo.IME_MASK_ACTION
                val isMultiLine = ((editorInfo?.inputType ?: 0) and
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
                val enterShouldInsertLineBreak = isMultiLine ||
                    (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0 ||
                    actionId == EditorInfo.IME_ACTION_NONE ||
                    actionId == EditorInfo.IME_ACTION_UNSPECIFIED

                if (enterShouldInsertLineBreak) {
                    currentInputConnection?.let { connection ->
                        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                    }
                } else {
                    currentInputConnection?.performEditorAction(actionId)
                }
            }
            "EMOJI" -> typeText("😊")
            "SYMBOLS" -> surface?.toggleSymbols()
            "SETTINGS" -> {
                try {
                    startActivity(android.content.Intent(this, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) { }
            }
            "CLIPBOARD" -> showClipboardHistory()
            "HIDE" -> requestHideSelf(0)
            else -> typeText(action)
        }
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (composing.isNotEmpty() && (newSelStart != candidatesEnd || newSelEnd != candidatesEnd)) {
            composing.clear()
            cancelOnlineSuggestions(clearUi = true)
        }
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        finishComposing()
    }
}
