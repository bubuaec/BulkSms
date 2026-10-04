package com.example.bulksms

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Gravity
import android.widget.*
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.math.BigDecimal
import java.util.zip.ZipInputStream

class MainActivity : Activity() {
    companion object {
        const val MAX_NUMBERS = 50
        const val MAX_CHARS = 160
        const val DELAY_MS = 2000L
        const val REQ_FILE = 11
        const val REQ_SMS = 12
    }

    private var numbers: List<String> = emptyList()
    private lateinit var info: TextView
    private lateinit var counter: TextView
    private lateinit var msg: EditText
    private lateinit var log: TextView
    private lateinit var sendBtn: Button
    private val handler = Handler(Looper.getMainLooper())
    private var sending = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        val pick = Button(this).apply { text = "1. Upload Excel (.xlsx) / CSV"; setOnClickListener { pickFile() } }
        info = TextView(this).apply { text = "No file loaded (max $MAX_NUMBERS numbers)"; setPadding(0, pad / 2, 0, pad / 2) }
        msg = EditText(this).apply {
            hint = "2. Type message (max $MAX_CHARS characters)"
            filters = arrayOf(InputFilter.LengthFilter(MAX_CHARS))
            minLines = 4; gravity = Gravity.TOP
        }
        counter = TextView(this).apply { text = "0/$MAX_CHARS"; gravity = Gravity.END }
        msg.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { counter.text = "${s?.length ?: 0}/$MAX_CHARS" }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        sendBtn = Button(this).apply { text = "3. Send SMS"; setOnClickListener { onSendClicked() } }
        log = TextView(this)
        val scroll = ScrollView(this).apply { addView(log) }

        root.addView(pick); root.addView(info); root.addView(msg); root.addView(counter)
        root.addView(sendBtn); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun pickFile() {
        val i = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "text/csv", "text/comma-separated-values", "application/csv", "text/plain"))
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(i, REQ_FILE)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != REQ_FILE || res != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        try {
            val cells = contentResolver.openInputStream(uri)!!.use { readCells(it) }
            val all = cells.mapNotNull { normalise(it) }.distinct()
            numbers = all.take(MAX_NUMBERS)
            var t = "${numbers.size} valid number(s) loaded."
            if (all.size > MAX_NUMBERS) t += " File had ${all.size}; only first $MAX_NUMBERS kept."
            info.text = t
        } catch (e: Exception) {
            info.text = "Could not read file: ${e.message}"
        }
    }

    // Reads all cell values from .xlsx (first sheet) or CSV
    private fun readCells(input: InputStream): List<String> {
        val bytes = input.readBytes()
        if (bytes.size > 3 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            var shared = listOf<String>()
            var sheet: ByteArray? = null
            ZipInputStream(bytes.inputStream()).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    when (e.name) {
                        "xl/sharedStrings.xml" -> shared = parseShared(z.readBytes())
                        "xl/worksheets/sheet1.xml" -> sheet = z.readBytes()
                    }
                    e = z.nextEntry
                }
            }
            return parseSheet(sheet ?: throw Exception("sheet1 not found"), shared)
        }
        return String(bytes).split('\n', ',', ';', '\t')
    }

    private fun newParser(b: ByteArray) = XmlPullParserFactory.newInstance().newPullParser().apply {
        setInput(b.inputStream(), "UTF-8")
    }

    private fun parseShared(b: ByteArray): List<String> {
        val p = newParser(b); val out = mutableListOf<String>(); var sb = StringBuilder(); var inT = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> { if (p.name == "si") sb = StringBuilder(); if (p.name == "t") inT = true }
                XmlPullParser.TEXT -> if (inT) sb.append(p.text)
                XmlPullParser.END_TAG -> { if (p.name == "t") inT = false; if (p.name == "si") out.add(sb.toString()) }
            }
            ev = p.next()
        }
        return out
    }

    private fun parseSheet(b: ByteArray, shared: List<String>): List<String> {
        val p = newParser(b); val out = mutableListOf<String>()
        var type = ""; var capture = false; var buf = StringBuilder()
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "c" -> { type = p.getAttributeValue(null, "t") ?: ""; buf = StringBuilder() }
                    "v", "t" -> capture = true
                }
                XmlPullParser.TEXT -> if (capture) buf.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "v", "t" -> capture = false
                    "c" -> {
                        var s = buf.toString().trim()
                        if (type == "s") s = shared.getOrNull(s.toIntOrNull() ?: -1) ?: ""
                        else if (s.contains('E', true)) s = try { BigDecimal(s).toPlainString() } catch (e: Exception) { s }
                        if (s.isNotEmpty()) out.add(s)
                    }
                }
            }
            ev = p.next()
        }
        return out
    }

    // Accepts 10-digit Indian mobiles (6-9 start), optionally with 0 / 91 / +91 prefix
    private fun normalise(raw: String): String? {
        var d = raw.filter { it.isDigit() }
        if (d.length == 12 && d.startsWith("91")) d = d.substring(2)
        else if (d.length == 11 && d.startsWith("0")) d = d.substring(1)
        return if (d.length == 10 && d[0] in '6'..'9') "+91$d" else null
    }

    private fun onSendClicked() {
        val text = msg.text.toString().trim()
        when {
            sending -> toast("Already sending")
            numbers.isEmpty() -> toast("Upload a list first")
            text.isEmpty() -> toast("Type a message")
            else -> AlertDialog.Builder(this)
                .setTitle("Confirm")
                .setMessage("Send this message to ${numbers.size} numbers?\n\n$text")
                .setPositiveButton("Send") { _, _ -> ensurePermissionAndSend(text) }
                .setNegativeButton("Cancel", null).show()
        }
    }

    private fun ensurePermissionAndSend(text: String) {
        if (checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) startSending(text)
        else requestPermissions(arrayOf(Manifest.permission.SEND_SMS), REQ_SMS)
    }

    override fun onRequestPermissionsResult(r: Int, p: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(r, p, g)
        if (r == REQ_SMS && g.firstOrNull() == PackageManager.PERMISSION_GRANTED) startSending(msg.text.toString().trim())
        else toast("SMS permission denied")
    }

    private fun startSending(text: String) {
        sending = true; sendBtn.isEnabled = false; log.text = ""
        val sms = if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
        val list = numbers.toList()
        fun step(i: Int) {
            if (i >= list.size) {
                log.append("\nDone. ${list.size} message(s) handed to SIM.\n"); sending = false; sendBtn.isEnabled = true; return
            }
            try { sms.sendTextMessage(list[i], null, text, null, null); log.append("${i + 1}/${list.size} sent: ${list[i]}\n") }
            catch (e: Exception) { log.append("${i + 1}/${list.size} FAILED: ${list[i]} (${e.message})\n") }
            handler.postDelayed({ step(i + 1) }, DELAY_MS)
        }
        step(0)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
