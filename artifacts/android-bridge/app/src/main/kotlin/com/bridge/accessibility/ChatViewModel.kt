package com.bridge.accessibility

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

enum class MessageType { USER, AGENT, PLAN }

data class ChatMessage(
    val content: String,
    val type: MessageType,
    val timestamp: Long = System.currentTimeMillis()
)

class ChatViewModel : ViewModel() {
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing

    private lateinit var secureSettings: SecureSettings
    private lateinit var memoryManager: SemanticMemoryManager
    private var pendingPlan: String? = null

    private val _isPlanMode = MutableStateFlow(true)
    val isPlanMode: StateFlow<Boolean> = _isPlanMode

    fun init(context: android.content.Context, settings: SecureSettings) {
        secureSettings = settings
        memoryManager = SemanticMemoryManager(context, settings)
    }

    fun togglePlanMode() {
        _isPlanMode.value = !_isPlanMode.value
    }

    private var currentTask: String? = null
    private var stepCount = 0
    private val maxSteps = 15

    fun sendMessage(text: String) {
        if (text.isBlank()) return

        viewModelScope.launch {
            _messages.value = _messages.value + ChatMessage(text, MessageType.USER)

            if (text.lowercase() == "hola") {
                _messages.value = _messages.value + ChatMessage("¡Hola! ¿En qué puedo ayudarte hoy?", MessageType.AGENT)
                return@launch
            }

            currentTask = text
            stepCount = 0
            startAgentLoop()
        }
    }

    private fun startAgentLoop() {
        viewModelScope.launch {
            _isProcessing.value = true
            while (stepCount < maxSteps) {
                stepCount++
                val success = processAgentStep()
                if (!success) break

                if (pendingPlan != null && _isPlanMode.value) break
            }
            if (stepCount >= maxSteps) {
                _messages.value = _messages.value + ChatMessage("⚠ Lo siento, no pude completar la tarea en 15 pasos. Me detuve para evitar un bucle infinito.", MessageType.AGENT)
            }
            _isProcessing.value = false
        }
    }

    private suspend fun processAgentStep(): Boolean {
        val task = currentTask ?: return false
        val apiKey = secureSettings.getGroqKey()
        if (apiKey.isNullOrEmpty()) {
            _messages.value = _messages.value + ChatMessage("Error: Configura tu API Key.", MessageType.AGENT)
            return false
        }

        return try {
            val service = IAccessibilityService.instance
            if (service == null) {
                _messages.value = _messages.value + ChatMessage("Error: Accesibilidad inactiva.", MessageType.AGENT)
                return false
            }

            val screen = service.getScreenJson()
            val observation = formatScreenForPrompt(screen)
            val relevantMemories = memoryManager.findRelevant(task)
            val memoryContext = if (relevantMemories.isNotEmpty()) {
                "\nRecordatorios relevantes:\n- " + relevantMemories.joinToString("\n- ")
            } else ""

            val response = RetrofitClient.groqService.chatCompletions(
                "Bearer $apiKey",
                GroqRequest(messages = listOf(
                    GroqMessage("system", buildSystemPrompt(task + memoryContext)),
                    GroqMessage("user", "$observation\n\nWhat is your next action?")
                ))
            )

            val content = response.choices.firstOrNull()?.message?.content ?: return false
            handleAgentResponse(content)
        } catch (e: Exception) {
            _messages.value = _messages.value + ChatMessage("Error: ${e.message}", MessageType.AGENT)
            false
        }
    }

    private suspend fun handleAgentResponse(content: String): Boolean {
        val thought = extractThought(content)
        val actionStr = extractAction(content)

        if (thought.isNotEmpty()) {
            _messages.value = _messages.value + ChatMessage(thought, MessageType.AGENT)
            memoryManager.addMemory(thought)
        }

        if (actionStr.isNotEmpty()) {
            if (actionStr.contains("FINISH", true)) {
                executeAction(actionStr)
                return false // Stop loop
            }

            if (_isPlanMode.value) {
                presentPlan(actionStr)
                return true // Pause loop for approval
            } else {
                val actionSuccess = executeAction(actionStr)
                if (!actionSuccess) {
                     _messages.value = _messages.value + ChatMessage("Fallé al ejecutar $actionStr, intentando de nuevo.", MessageType.AGENT)
                }
                kotlinx.coroutines.delay(1000)
                return true // Continue loop
            }
        }
        return false
    }

    private fun presentPlan(action: String) {
        pendingPlan = action
        _messages.value = _messages.value + ChatMessage("He planeado la siguiente acción: $action. ¿Deseas que proceda?", MessageType.PLAN)
    }

    fun approvePlan() {
        val action = pendingPlan ?: return
        pendingPlan = null
        viewModelScope.launch {
            _isProcessing.value = true
            executeAction(action)
            kotlinx.coroutines.delay(1000)
            startAgentLoop() // Resume loop
        }
    }

    private suspend fun executeAction(actionStr: String): Boolean {
        val service = IAccessibilityService.instance ?: return false

        return try {
            when {
                actionStr.contains("CLICK", true) -> {
                    val match = Regex("""CLICK\(\s*(\d+)\s*,\s*(\d+)\s*\)""", RegexOption.IGNORE_CASE).find(actionStr)
                    if (match != null) {
                        val x = match.groupValues[1].toFloat()
                        val y = match.groupValues[2].toFloat()
                        service.performClick(x, y)
                    } else false
                }
                actionStr.contains("TYPE", true) -> {
                    val match = Regex("""TYPE\(\s*"([^"]*)"\s*\)""", RegexOption.IGNORE_CASE).find(actionStr)
                    if (match != null) {
                        service.performInput(match.groupValues[1])
                    } else false
                }
                actionStr.contains("SWIPE", true) -> {
                    val match = Regex("""SWIPE\(\s*"([^"]*)"\s*\)""", RegexOption.IGNORE_CASE).find(actionStr)
                    if (match != null) {
                        val dir = match.groupValues[1].lowercase()
                        val (x, y, dx, dy) = when (dir) {
                            "up" -> arrayOf(540f, 1200f, 0f, -500f)
                            "down" -> arrayOf(540f, 300f, 0f, 500f)
                            "left" -> arrayOf(900f, 600f, -400f, 0f)
                            "right" -> arrayOf(200f, 600f, 400f, 0f)
                            else -> arrayOf(0f, 0f, 0f, 0f)
                        }
                        service.performScroll(x as Float, y as Float, dx as Float, dy as Float)
                    } else false
                }
                actionStr.contains("HOME", true) -> service.performGlobalAction("HOME")
                actionStr.contains("BACK", true) -> service.performGlobalAction("BACK")
                actionStr.contains("RECENTS", true) -> service.performGlobalAction("RECENTS")
                actionStr.contains("WAIT", true) -> {
                    val match = Regex("""WAIT\(\s*(\d+)\s*\)""", RegexOption.IGNORE_CASE).find(actionStr)
                    val ms = match?.groupValues?.get(1)?.toLong() ?: 1000L
                    kotlinx.coroutines.delay(ms)
                    true
                }
                actionStr.contains("FINISH", true) -> {
                    val match = Regex("""FINISH\(\s*"([^"]*)"\s*\)""", RegexOption.IGNORE_CASE).find(actionStr)
                    val msg = match?.groupValues?.get(1) ?: "Tarea finalizada"
                    _messages.value = _messages.value + ChatMessage("✓ $msg", MessageType.AGENT)
                    true
                }
                else -> false
            }
        } catch (e: Exception) {
            _messages.value = _messages.value + ChatMessage("Error en acción: ${e.message}", MessageType.AGENT)
            false
        }
    }

    private fun formatScreenForPrompt(screen: JSONObject): String {
        val packageName = screen.optString("package")
        val nodes = screen.optJSONArray("nodes") ?: return "No screen data"

        val sb = StringBuilder("Current Screen State:\nPackage: $packageName\nInteractive Elements (${nodes.length()} total):\n")
        for (i in 0 until nodes.length()) {
            val node = nodes.getJSONObject(i)
            sb.append("\n[$i] ${node.optString("cls")}\n")
            if (node.has("text")) sb.append("    Text: \"${node.getString("text")}\"\n")
            if (node.has("desc")) sb.append("    Description: \"${node.getString("desc")}\"\n")
            if (node.has("id")) sb.append("    ID: ${node.getString("id")}\n")
            val rect = node.getJSONObject("rect")
            sb.append("    Position: (${rect.getInt("x")}, ${rect.getInt("y")}) Size: ${rect.getInt("w")}x${rect.getInt("h")}\n")
            val attrs = mutableListOf<String>()
            if (node.optBoolean("clickable")) attrs.add("clickable")
            if (node.optBoolean("scrollable")) attrs.add("scrollable")
            if (node.optBoolean("editable")) attrs.add("editable")
            if (attrs.isNotEmpty()) sb.append("    Attributes: ${attrs.joinToString(", ")}\n")
        }
        return sb.toString()
    }

    private fun buildSystemPrompt(task: String): String {
        return """You are an autonomous agent controlling an Android device via accessibility API.

Your task: $task

IMPORTANT: If you cannot find an element or the action fails, explain why in your thought block.

You have access to the following actions:
- CLICK(x, y): Click at screen coordinates (x, y)
- TYPE("text"): Type text into the focused input field
- SWIPE("direction"): Perform a swipe gesture (up, down, left, right)
- WAIT(ms): Wait for specified milliseconds
- HOME(), BACK(), RECENTS(): Android system navigation actions
- FINISH("message"): Complete the task with a completion message

IMPORTANT:
1. Reason step-by-step: <think> reasoning </think>
2. Analyze the screen state carefully before deciding.
3. Use the correct action format.
4. When done, use FINISH("message").

Format:
<think>
your reasoning
</think>
Action: ACTION_TYPE(params)"""
    }

    private fun extractThought(content: String): String {
        val match = Regex("""<think>(.*?)</think>""", RegexOption.DOT_MATCHES_ALL).find(content)
        return match?.groupValues?.get(1)?.trim() ?: ""
    }

    private fun extractAction(content: String): String {
        return content.replace(Regex("""<think>.*?</think>""", RegexOption.DOT_MATCHES_ALL), "").trim()
    }
}
