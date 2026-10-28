package com.prompter.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * One step of a user macro. [kind] is one of:
 *  - "text":   [a1] = text to type, [a2] = "1" to submit (Enter) after it
 *  - "key":    [a1] = key name (enter, esc, PageUp, ...)
 *  - "combo":  [a1] = comma-separated mods (ctrl/alt/shift), [a2] = key
 *  - "action": [a1] = semantic action id (yes/no/clear/...)
 */
data class MacroStep(val kind: String, val a1: String, val a2: String = "")

/** A named button on the Commands screen running [steps] sequentially. */
data class Macro(val label: String, val steps: List<MacroStep>)

object Macros {

    /** Parses the editor DSL, one step per line:
     *  `text <msg>` · `text! <msg>` (submit) · `key <name>` ·
     *  `combo <mods> <key>` · `action <id>`. Unknown lines are skipped. */
    fun parse(source: String, label: String): Macro {
        val steps = source.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                when {
                    line.startsWith("text!") ->
                        MacroStep("text", line.removePrefix("text!").trim(), "1")
                    line.startsWith("text ") ->
                        MacroStep("text", line.removePrefix("text").trim(), "")
                    line.startsWith("key ") ->
                        MacroStep("key", line.removePrefix("key").trim())
                    line.startsWith("action ") ->
                        MacroStep("action", line.removePrefix("action").trim())
                    line.startsWith("combo ") -> {
                        val rest = line.removePrefix("combo").trim()
                        val key = rest.substringAfterLast(' ')
                        val mods = rest.substringBeforeLast(' ').replace(',', ' ')
                        if (key.isNotEmpty() && mods.isNotEmpty()) {
                            MacroStep("combo", mods, key)
                        } else null
                    }
                    else -> null
                }
            }
        return Macro(label.ifBlank { "macro" }, steps)
    }

    /** Renders steps back to the editor DSL. */
    fun render(macro: Macro): String = macro.steps.joinToString("\n") { s ->
        when (s.kind) {
            "text" -> if (s.a2 == "1") "text! ${s.a1}" else "text ${s.a1}"
            "key" -> "key ${s.a1}"
            "combo" -> "combo ${s.a1} ${s.a2}"
            "action" -> "action ${s.a1}"
            else -> ""
        }
    }

    fun toJson(macros: List<Macro>): String {
        val arr = JSONArray()
        macros.forEach { m ->
            val steps = JSONArray()
            m.steps.forEach { s ->
                steps.put(JSONObject().put("k", s.kind).put("a", s.a1).put("b", s.a2))
            }
            arr.put(JSONObject().put("label", m.label).put("steps", steps))
        }
        return arr.toString()
    }

    fun fromJson(raw: String?): List<Macro> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<Macro>(arr.length())
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                val stepsArr = m.optJSONArray("steps") ?: continue
                val steps = ArrayList<MacroStep>(stepsArr.length())
                for (j in 0 until stepsArr.length()) {
                    val s = stepsArr.optJSONObject(j) ?: continue
                    steps.add(MacroStep(s.optString("k"), s.optString("a"), s.optString("b")))
                }
                out.add(Macro(m.optString("label", "macro"), steps))
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }
}
