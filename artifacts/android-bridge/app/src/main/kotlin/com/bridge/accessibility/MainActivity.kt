package com.bridge.accessibility

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupWithNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.bridge.accessibility.databinding.ActivityMainBinding
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        // Connect Bottom Navigation with NavController
        binding.bottomNav.setupWithNavController(navController)

        // Configuration for drawer and toolbar
        val appBarConfiguration = AppBarConfiguration(
            setOf(R.id.nav_chat, R.id.nav_settings, R.id.nav_status),
            binding.drawerLayout
        )
        binding.toolbar.setupWithNavController(navController, appBarConfiguration)

        setupHistoryDrawer()
    }

    private fun setupHistoryDrawer() {
        val historyRecycler = binding.navView.findViewById<RecyclerView>(R.id.history_recycler_view)
        historyRecycler.layoutManager = LinearLayoutManager(this)

        // Mock history for now, but wired to UI
        val historyData = listOf("Chat de hoy", "Calculadora ayer", "Ajustes WiFi")
        historyRecycler.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val view = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_1, parent, false)
                return object : RecyclerView.ViewHolder(view) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                (holder.itemView as TextView).text = historyData[position]
                holder.itemView.setTextColor(0xFF00E5FF.toInt())
            }
            override fun getItemCount() = historyData.size
        }
    }
}
