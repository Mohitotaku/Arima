package com.kairem.arima

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val WORKER_URL = "https://arima-ai.mohitrls106.workers.dev/"

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private var rec: SpeechRecognizer? = null
    private val history = JSONArray()
    private val ui = Handler(Looper.getMainLooper())
    private val purple = Color.parseColor("#9D4EDD")

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        tts = TextToSpeech(this, this)
        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
        val match = LinearLayout.LayoutParams.MATCH_PARENT
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#120824"))
            setPadding(32, 64, 32, 32)
        }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        bar.addView(TextView(this).apply {
            text = "ARIMA"; textSize = 26f; setTextColor(purple); typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, wrap, 1f))
        log = TextView(this).apply { setTextColor(Color.parseColor("#E9D5FF")); textSize = 16f }
        scroll = ScrollView(this).apply { addView(log) }
        input = EditText(this).apply {
            hint = "Command likho..."; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        }
        val row = LinearLayout(this)
        row.addView(input, LinearLayout.LayoutParams(0, wrap, 1f))
        row.addView(btn("Send") { send() })
        row.addView(btn("MIC") { listen() })
        root.addView(bar, match, wrap)
        root.addView(scroll, LinearLayout.LayoutParams(match, 0, 1f))
        root.addView(row, match, wrap)
        setContentView(root)
    }

    private fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; setTextColor(Color.WHITE); setBackgroundColor(purple)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = 12 }
        setOnClickListener { onClick() }
    }

    override fun onInit(s: Int) {
        if (s == TextToSpeech.SUCCESS) {
            tts.setLanguage(Locale("en", "IN"))
            tts.setPitch(1.5f)          // higher pitch = cute anime-girl style
            tts.setSpeechRate(1.05f)
            ttsReady = true
            say("Arima", "Hello senpai! Main Arima hoon. Bolo, kya karna hai?")
        }
    }

    private fun say(who: String, msg: String, speak: Boolean = true) {
        ui.post {
            log.append("$who: $msg\n\n")
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
        if (speak && ttsReady) tts.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "arima")
    }

    private fun send() {
        val t = input.text.toString().trim()
        if (t.isNotEmpty()) { input.setText(""); handle(t) }
    }

    private fun listen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1); return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            say("Arima", "Is phone par voice recognition available nahi hai."); return
        }
        rec?.destroy()
        rec = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(r: Bundle?) {
                    r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { handle(it) }
                }
                override fun onError(e: Int) { say("Arima", "Sun nahi paayi, phir se bolo.") }
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(p: Bundle?) {}
                override fun onEvent(t: Int, p: Bundle?) {}
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            })
        }
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        if (r.isNotEmpty() && r[0] == PackageManager.PERMISSION_GRANTED) listen()
    }

    private fun handle(raw: String) {
        say("You", raw, false)
        val t = raw.lowercase(Locale.ROOT).trim()
        val reply = try { local(t) } catch (e: Exception) { "Ye kaam nahi ho paaya: ${e.message}" }
        if (reply != null) say("Arima", reply) else askAi(raw)
    }

    private fun has(t: String, vararg k: String) = k.any { t.contains(it) }

    private fun go(i: Intent) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i) }

    private fun web(url: String) = go(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    private fun global(action: Int, ok: String): String {
        val s = ArimaAccess.inst
        if (s == null) {
            go(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return "Pehle Accessibility settings me Arima Control ON karo, phir wapas bolo."
        }
        s.performGlobalAction(action)
        return ok
    }

    private fun openApp(name: String): String {
        if (name.isEmpty()) return "Kaunsa app kholna hai?"
        val pm = packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val hit = apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(name) }
            ?: return "'$name' naam ka app nahi mila."
        val launch = pm.getLaunchIntentForPackage(hit.activityInfo.packageName)
            ?: return "Ye app khul nahi sakta."
        go(launch)
        return "${hit.loadLabel(pm)} khol diya."
    }

    // Returns a reply if this was a phone command, or null so the AI brain answers instead.
    private fun local(t: String): String? {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val nums = Regex("\\d+").findAll(t).map { it.value }.toList()
        return when {
            has(t, "wifi", "wi-fi") -> { go(Intent(Settings.ACTION_WIFI_SETTINGS)); "Wi-Fi settings khol di." }
            has(t, "bluetooth") -> { go(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); "Bluetooth settings khol di." }
            has(t, "brightness", "display setting") -> { go(Intent(Settings.ACTION_DISPLAY_SETTINGS)); "Display settings khol di." }
            has(t, "setting") -> { go(Intent(Settings.ACTION_SETTINGS)); "Settings khol di." }
            has(t, "flashlight", "torch") -> {
                val on = !has(t, " off", "band", "bandh")
                val cm = getSystemService(CAMERA_SERVICE) as CameraManager
                cm.setTorchMode(cm.cameraIdList[0], on)
                if (on) "Torch on kar di." else "Torch band kar di."
            }
            has(t, "volume") -> {
                val up = has(t, "up", "increase", "badha", "zyada", "high")
                am.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI
                )
                if (up) "Volume badha diya." else "Volume kam kar diya."
            }
            has(t, "battery") -> {
                val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
                "Battery ${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)} percent hai."
            }
            has(t, "what time", "time kya", "kitne baje", "current time") ->
                "Abhi ${SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())} baje hain."
            has(t, "today's date", "what date", "date kya", "aaj ki date", "what day") ->
                "Aaj ${SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(Date())} hai."
            has(t, "timer") -> {
                val n = nums.firstOrNull()?.toInt() ?: return "Kitne minute ka timer?"
                val secs = if (has(t, "second", "sec")) n else n * 60
                go(Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, secs)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
                "Timer set kar diya."
            }
            has(t, "alarm") -> {
                val m = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)?").find(t)
                    ?: return "Kitne baje ka alarm? Jaise: set alarm 7:30 am"
                var h = m.groupValues[1].toInt()
                val min = m.groupValues[2].ifEmpty { "0" }.toInt()
                val ap = m.groupValues[3]
                if ((ap.startsWith("p") || has(t, "shaam", "raat", "evening")) && h < 12) h += 12
                if (ap.startsWith("a") && h == 12) h = 0
                go(Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, h)
                    .putExtra(AlarmClock.EXTRA_MINUTES, min)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
                "Alarm set ho gaya: %02d:%02d.".format(h, min)
            }
            t.startsWith("call") || has(t, "dial") -> {
                val d = t.filter { it.isDigit() }
                if (d.length < 3) "Number bolo, jaise: call 9876543210"
                else { go(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$d"))); "Dialer khol diya." }
            }
            t.startsWith("play ") -> {
                web("https://www.youtube.com/results?search_query=" + Uri.encode(t.removePrefix("play ")))
                "YouTube par dhoondh rahi hoon."
            }
            t.startsWith("search ") || t.startsWith("google ") -> {
                web("https://www.google.com/search?q=" + Uri.encode(t.substringAfter(" ")))
                "Search kar rahi hoon."
            }
            has(t, "navigate to", "directions to", "route to") -> {
                web("geo:0,0?q=" + Uri.encode(t.substringAfter(" to ")))
                "Maps khol rahi hoon."
            }
            has(t, "go home", "home screen", "home jao") ->
                global(AccessibilityService.GLOBAL_ACTION_HOME, "Home par aa gayi.")
            has(t, "go back", "press back", "peeche") ->
                global(AccessibilityService.GLOBAL_ACTION_BACK, "Back kar diya.")
            has(t, "recent apps", "recents") ->
                global(AccessibilityService.GLOBAL_ACTION_RECENTS, "Recent apps dikha diye.")
            has(t, "notification") ->
                global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "Notifications khol di.")
            has(t, "lock screen", "lock phone", "phone lock") ->
                global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, "Phone lock kar diya.")
            has(t, "screenshot") ->
                global(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT, "Screenshot le liya.")
            has(t, "open ", "launch ", "kholo", "khol do", "start ") ->
                openApp(t.replace(Regex("\\b(open|launch|start|kholo|khol|do|de|karo|please|app|the)\\b"), "").trim())
            else -> null
        }
    }

    // AI answers come from our own Cloudflare Worker. No API key lives in this app.
    private fun askAi(q: String) {
        if (WORKER_URL.contains("PASTE")) {
            say("Arima", "Ye command mujhe samajh nahi aaya. AI server abhi connect nahi hai.")
            return
        }
        history.put(JSONObject().put("role", "user").put("content", q))
        while (history.length() > 10) { history.remove(0); history.remove(0) }
        Thread {
            try {
                say("Arima", ask())
            } catch (e: Exception) {
                history.remove(history.length() - 1)
                say("Arima", e.message ?: "Kuch gadbad ho gayi, phir try karo.")
            }
        }.start()
    }

    private fun ask(): String {
        val c = URL(WORKER_URL).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 15000
        c.readTimeout = 40000
        c.setRequestProperty("content-type", "application/json")
        c.outputStream.use { it.write(JSONObject().put("messages", history).toString().toByteArray()) }
        val code = c.responseCode
        if (code == 429) throw Exception("Aaj ka free AI limit khatam ho gaya. Kal phir try karna. Phone commands abhi bhi chalenge.")
        if (code !in 200..299) throw Exception("AI abhi busy hai, thodi der baad try karo.")
        val txt = c.inputStream.bufferedReader().use { it.readText() }
        val ans = JSONObject(txt).optString("reply").trim()
        if (ans.isEmpty()) throw Exception("AI ne khaali jawab diya, phir se bolo.")
        history.put(JSONObject().put("role", "assistant").put("content", ans))
        return ans
    }

    override fun onDestroy() {
        tts.shutdown(); rec?.destroy()
        super.onDestroy()
    }
}
