package com.example.contactcleaner

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val PERMISSION_REQUEST_CODE = 100
    private lateinit var numbersList: MutableList<String>
    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val etPhoneNumber = findViewById<EditText>(R.id.etPhoneNumber)
        val btnAddNumber = findViewById<Button>(R.id.btnAddNumber)
        val lvNumbers = findViewById<ListView>(R.id.lvNumbers)

        numbersList = getSavedNumbers().toMutableList()
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, numbersList)
        lvNumbers.adapter = adapter

        btnAddNumber.setOnClickListener {
            val rawNum = etPhoneNumber.text.toString().replace("\\s+".toRegex(), "")
            if (rawNum.isNotEmpty() && !numbersList.contains(rawNum)) {
                numbersList.add(rawNum)
                saveNumbers(numbersList)
                adapter.notifyDataSetChanged()
                etPhoneNumber.text.clear()
            }
        }

        lvNumbers.setOnItemClickListener { _, _, position, _ ->
            numbersList.removeAt(position)
            saveNumbers(numbersList)
            adapter.notifyDataSetChanged()
            Toast.makeText(this, "Usunięto z listy zakazanych", Toast.LENGTH_SHORT).show()
        }

        checkPermissions()
    }

    private fun saveNumbers(list: List<String>) {
        val prefs = getSharedPreferences("CleanerPrefs", Context.MODE_PRIVATE)
        prefs.edit().putStringSet("blacklisted_numbers", list.toSet()).apply()
    }

    private fun getSavedNumbers(): Set<String> {
        val prefs = getSharedPreferences("CleanerPrefs", Context.MODE_PRIVATE)
        return prefs.getStringSet("blacklisted_numbers", emptySet()) ?: emptySet()
    }

    private fun checkPermissions() {
        val readPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
        val writePerm = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CONTACTS)

        if (readPerm != PackageManager.PERMISSION_GRANTED || writePerm != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS),
                PERMISSION_REQUEST_CODE
            )
        } else {
            startCleanerService()
        }
    }

    private fun startCleanerService() {
        val intent = Intent(this, ContactCleanerService::class.java)
        startService(intent)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startCleanerService()
        }
    }
}
