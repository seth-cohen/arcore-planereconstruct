package com.example.forwardthinking.arcore

import android.content.Intent
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.view.View
import com.google.ar.core.ArCoreApk
import kotlinx.android.synthetic.main.activity_main.*

const val EXTRA_MESSAGE = "com.example.ARCore.MESSAGE"

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        arTextView.text = if (checkARAbility()) "AR Available" else "No AR Functions"
    }

    private fun checkARAbility(): Boolean {
        val availability = ArCoreApk.getInstance().checkAvailability(this)
        return availability.isSupported
    }

    fun sendMessage(view: View) {
        val message = editText.text.toString()
        val showMessageActivityIntent = Intent(this, DisplayMessageActivity::class.java).apply {
            putExtra(EXTRA_MESSAGE, message)
        }
        startActivity(showMessageActivityIntent)
    }
}
