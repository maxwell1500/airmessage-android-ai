package me.tagavari.airmessage.activity

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import me.tagavari.airmessage.R
import me.tagavari.airmessage.helper.ExternalStorageHelper.exportText

class CrashReport : AppCompatActivity() {
    //Creating the values
    private var stackTrace: String? = null

    //Creating the callbacks
    private val createDocumentLauncher = registerForActivityResult<String?, Uri?>(
        ActivityResultContracts.CreateDocument(), ActivityResultCallback registerForActivityResult@{ uri: Uri? ->
            if (uri == null) {
                return@registerForActivityResult
            }
            exportText(this, stackTrace, uri)
        })

    override fun onCreate(savedInstanceState: Bundle?) {
        //Calling the super method
        super.onCreate(savedInstanceState)

        //Setting the content view
        setContentView(R.layout.activity_crashreport)

        //Getting the stack trace
        stackTrace = intent.getStringExtra(PARAM_STACKTRACE)

        //Setting the stack trace text
        val textViewStackTrace = findViewById<TextView>(R.id.label_stacktrace)
        textViewStackTrace.text = stackTrace
        textViewStackTrace.movementMethod = ScrollingMovementMethod()

        //For some reason this is needed to enable text selection
        textViewStackTrace.setTextIsSelectable(false)
        textViewStackTrace.setTextIsSelectable(true)

        findViewById<View?>(R.id.button_copy).setOnClickListener(View.OnClickListener { view: View? ->
            this.buttonCopy(
                view
            )
        })
        findViewById<View?>(R.id.button_export).setOnClickListener(View.OnClickListener { view: View? ->
            this.buttonExport(
                view
            )
        })
        findViewById<View?>(R.id.button_restart).setOnClickListener(View.OnClickListener { view: View? ->
            this.buttonRestart(
                view
            )
        })
    }

    private fun buttonCopy(view: View?) {
        //Getting the clipboard manager
        val clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager

        //Applying the clip data
        clipboardManager.setPrimaryClip(
            ClipData.newPlainText(
                "Crash report stack trace",
                stackTrace
            )
        )

        //Showing a confirmation toast
        Toast.makeText(this, R.string.message_textcopied, Toast.LENGTH_SHORT).show()
    }

    private fun buttonExport(view: View?) {
        createDocumentLauncher.launch("stacktrace.txt")
    }

    private fun buttonRestart(view: View?) {
        startActivity(
            Intent(
                this,
                Conversations::class.java
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    companion object {
        //Creating the constants
        const val PARAM_STACKTRACE: String = "stacktrace"
    }
}