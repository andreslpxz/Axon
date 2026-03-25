package com.bridge.accessibility

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.bridge.accessibility.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var secureSettings: SecureSettings

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        secureSettings = SecureSettings(requireContext())
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Load existing keys
        binding.groqApiKey.setText(secureSettings.getGroqKey())
        binding.hfApiKey.setText(secureSettings.getHFKey())

        binding.saveKeysButton.setOnClickListener {
            val groqKey = binding.groqApiKey.text.toString().trim()
            val hfKey = binding.hfApiKey.text.toString().trim()

            secureSettings.saveGroqKey(groqKey)
            secureSettings.saveHFKey(hfKey)

            android.widget.Toast.makeText(requireContext(), "Ajustes guardados correctamente", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
