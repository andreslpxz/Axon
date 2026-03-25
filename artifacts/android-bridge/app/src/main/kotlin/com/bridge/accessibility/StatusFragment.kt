package com.bridge.accessibility

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.bridge.accessibility.databinding.FragmentStatusBinding

class StatusFragment : Fragment() {
    private var _binding: FragmentStatusBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStatusBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.enableButton.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val serviceEnabled = isAccessibilityServiceEnabled()
        val serverRunning = IAccessibilityService.instance != null

        if (serviceEnabled && serverRunning) {
            binding.statusText.text = buildString {
                appendLine("Estado: ACTIVO")
                appendLine("")
                appendLine("Servicio de accesibilidad: ON")
                appendLine("Servidor HTTP: ON (puerto 8080)")
                appendLine("")
                appendLine("Endpoints disponibles:")
                appendLine("  GET  http://localhost:8080/screen")
                appendLine("  POST http://localhost:8080/action")
                appendLine("  GET  http://localhost:8080/health")
            }
        } else if (serviceEnabled) {
            binding.statusText.text = buildString {
                appendLine("Estado: INICIANDO...")
                appendLine("")
                appendLine("Servicio de accesibilidad: ON")
                appendLine("Servidor HTTP: iniciando...")
            }
        } else {
            binding.statusText.text = buildString {
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
        val context = requireContext()
        val accessibilityEnabled = try {
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED)
        } catch (e: Settings.SettingNotFoundException) {
            return false
        }
        if (accessibilityEnabled != 1) return false

        val settingValue = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val componentName = "${context.packageName}/${IAccessibilityService::class.java.name}"
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(settingValue)
        while (splitter.hasNext()) {
            if (splitter.next().equals(componentName, ignoreCase = true)) return true
        }
        return false
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
