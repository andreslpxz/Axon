package com.bridge.accessibility

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val statusText = findViewById<TextView>(R.id.statusText)
        val enableBtn = findViewById<Button>(R.id.enableButton)

        enableBtn.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        updateStatus(statusText)
    }

    override fun onResume() {
        super.onResume()
        val statusText = findViewById<TextView>(R.id.statusText)
        updateStatus(statusText)
    }

    private fun updateStatus(statusText: TextView) {
        val serviceEnabled = isAccessibilityServiceEnabled()
        val serverRunning = IAccessibilityService.instance != null

        if (serviceEnabled && serverRunning) {
            statusText.text = buildString {
                appendLine("Estado: ACTIVO")
                appendLine("")
                appendLine("Servicio de accesibilidad: ON")
                appendLine("Servidor HTTP: ON (puerto 8080)")
                appendLine("")
                appendLine("Endpoints disponibles:")
                appendLine("  GET  http://localhost:8080/screen")
                appendLine("  POST http://localhost:8080/action")
                appendLine("  GET  http://localhost:8080/health")
                appendLine("")
                appendLine("Ejemplos de uso desde Termux:")
                appendLine("  curl http://localhost:8080/screen")
                appendLine("  curl -X POST http://localhost:8080/action \\")
                appendLine("    -H 'Content-Type: application/json' \\")
                appendLine("    -d '{\"action\":\"click\",\"x\":540,\"y\":960}'")
            }
        } else if (serviceEnabled) {
            statusText.text = buildString {
                appendLine("Estado: INICIANDO...")
                appendLine("")
                appendLine("Servicio de accesibilidad: ON")
                appendLine("Servidor HTTP: iniciando...")
            }
        } else {
            statusText.text = buildString {
                appendLine("Estado: INACTIVO")
                appendLine("")
                appendLine("El servicio de accesibilidad no está habilitado.")
                appendLine("")
                appendLine("Pulsa el botón para ir a Ajustes y activar:")
                appendLine("'AI Bridge Accessibility Service'")
            }
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val accessibilityEnabled = try {
            Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED)
        } catch (e: Settings.SettingNotFoundException) {
            return false
        }
        if (accessibilityEnabled != 1) return false

        val settingValue = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val componentName = "${packageName}/${IAccessibilityService::class.java.name}"
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(settingValue)
        while (splitter.hasNext()) {
            if (splitter.next().equals(componentName, ignoreCase = true)) return true
        }
        return false
    }
}
