package com.bridge.accessibility

import android.content.Context
import android.content.SharedPreferences

class SecureSettings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("ai_bridge_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_GROQ_API = "groq_api_key"
        private const val KEY_HF_API = "hf_api_key"
    }

    fun saveGroqKey(key: String) {
        prefs.edit().putString(KEY_GROQ_API, key).apply()
    }

    fun getGroqKey(): String? {
        return prefs.getString(KEY_GROQ_API, null)
    }

    fun saveHFKey(key: String) {
        prefs.edit().putString(KEY_HF_API, key).apply()
    }

    fun getHFKey(): String? {
        return prefs.getString(KEY_HF_API, null)
    }
}
