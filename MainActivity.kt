package com.thala.assistant

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * Thala's screen, ears and voice.
 *
 *  - Ears:  the phone's own speech recognizer (English or Tamil, switch at the top).
 *  - Brain: Brain.kt, plain rules. No AI service, no API key.
 *  - Voice: the phone's own text-to-speech, tuned to a bright young girl's voice
 *           (higher pitch, a little faster). Tap "Voice" to pick another voice or change the pitch.
 *
 * Everything is drawn in code, so there are no layout files and no extra libraries.
 */
class MainActivity : Activity() {

    private companion object {
        const val REQ_FIRST = 1
        const val REQ_MIC = 2

        /** The girl-voice settings. 1.0 = the engine's normal voice. Changeable in the Voice menu. */
        const val DEFAULT_PITCH = 1.3f
        const val DEFAULT_RATE = 1.05f

        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    // ================= 1. THINGS THIS SCREEN REMEMBERS =================

    private lateinit var brain: Brain
    private lateinit var prefs: SharedPreferences

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var session = 0                 // counts listening rounds, so an old timer never cancels a new round
    private var tamilMode = false           // true = she listens for Tamil
    private var pitch = DEFAULT_PITCH
    private var rate = DEFAULT_RATE
    private var warnedNoTamilVoice = false
    @Volatile private var currentUtt = ""   // id of the sentence being spoken now
    private var pulse: ObjectAnimator? = null

    private lateinit var scroll: ScrollView
    private lateinit var chatBox: LinearLayout
    private lateinit var chips: LinearLayout
    private lateinit var statusLine: TextView
    private lateinit var input: EditText
    private lateinit var micButton: TextView
    private lateinit var langButton: TextView
    private lateinit var voiceButton: TextView

    // colours
    private val cBg = Color.parseColor("#14111F")
    private val cSurface = Color.parseColor("#262038")
    private val cText = Color.parseColor("#F4F0FF")
    private val cSub = Color.parseColor("#A79FC4")
    private val cPink = Color.parseColor("#FF6FB5")
    private val cPinkDeep = Color.parseColor("#C2408C")
    private val cPurple = Color.parseColor("#8E6BFF")

    // quick buttons under the chat: label to the sentence it sends
    private val chipsEn = listOf(
        "What can you do?" to "help",
        "Time" to "what is the time",
        "Battery" to "battery",
        "Torch on" to "torch on",
        "Timer 5 min" to "set a timer for 5 minutes",
        "Open YouTube" to "open youtube"
    )
    private val chipsTa = listOf(
        "உதவி" to "உதவி",
        "நேரம்" to "நேரம் என்ன",
        "பேட்டரி" to "பேட்டரி",
        "டார்ச் ஆன்" to "டார்ச் ஆன்",
        "5 நிமிட டைமர்" to "5 நிமிடம் டைமர் வை",
        "யூடியூப் திற" to "யூடியூப் திற"
    )

    // things Thala says by herself (English, Tamil)
    private val greeting = Reply(
        "Hi! I'm Thala. Tap the mic and talk to me.",
        "வணக்கம்! நான் தல. மைக்கைத் தட்டிப் பேசுங்கள்."
    )
    private val tipVoice = Reply(
        "Tip: tap Voice to pick the voice and pitch you like best.",
        "குறிப்பு: குரல் பொத்தானைத் தட்டி உங்களுக்குப் பிடித்த குரலையும் சுருதியையும் தேர்வு செய்யுங்கள்."
    )
    private val msgNoHear = Reply(
        "I didn't catch that. Tap the mic and try again.",
        "எனக்குக் கேட்கவில்லை. மைக்கைத் தட்டி மீண்டும் முயற்சி செய்யுங்கள்."
    )
    private val msgNoMic = Reply(
        "I need the microphone permission to hear you. You can also type.",
        "உங்களைக் கேட்க மைக்ரோஃபோன் அனுமதி வேண்டும். தட்டச்சும் செய்யலாம்."
    )
    private val msgNoRecognizer = Reply(
        "Voice input isn't available on this phone. Please type instead.",
        "இந்த போனில் குரல் உள்ளீடு இல்லை. தட்டச்சு செய்யுங்கள்."
    )
    private val msgNetwork = Reply(
        "I can't listen right now. Check the internet, or type instead.",
        "இப்போது கேட்க முடியவில்லை. இணையத்தைச் சரிபார்க்கவும் அல்லது தட்டச்சு செய்யுங்கள்."
    )
    private val msgLang = Reply(
        "This language isn't available for voice input on this phone.",
        "இந்த மொழி இந்த போனில் குரல் உள்ளீட்டிற்குக் கிடைக்கவில்லை."
    )
    private val msgBusy = Reply(
        "The microphone is busy. Please tap the mic again.",
        "மைக் பிஸியாக உள்ளது. மீண்டும் தட்டுங்கள்."
    )
    private val msgFailed = Reply(
        "Sorry, something went wrong.",
        "மன்னிக்கவும், ஏதோ தவறு நடந்துவிட்டது."
    )

    // ================= 2. START AND STOP =================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("thala", Context.MODE_PRIVATE)
        brain = Brain(this)
        tamilMode = prefs.getBoolean("tamil_mode", false)
        pitch = prefs.getFloat("pitch", DEFAULT_PITCH)
        rate = prefs.getFloat("rate", DEFAULT_RATE)

        window.statusBarColor = cBg
        window.navigationBarColor = cBg
        setContentView(buildUi())
        refreshLanguageUi()

        addBubble(tr(greeting.en, greeting.ta), false)
        if (prefs.getString("voice_en", null) == null) {
            addBubble(tr(tipVoice.en, tipVoice.ta), false)
        }

        tts = TextToSpeech(this) { code -> runOnUiThread { onTtsReady(code) } }
        askPermissionsOnce()
    }

    override fun onStop() {
        super.onStop()
        resetListening(true)
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    // ================= 3. THE SCREEN =================

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        // top bar: name + status on the left, language and voice buttons on the right
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val nameBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        nameBox.addView(TextView(this).apply {
            text = "Thala"
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(cText)
        })
        statusLine = TextView(this).apply {
            textSize = 13f
            setTextColor(cSub)
        }
        nameBox.addView(statusLine)
        header.addView(nameBox, LinearLayout.LayoutParams(0, WRAP, 1f))

        langButton = pill("")
        langButton.setOnClickListener { toggleLanguage() }
        header.addView(langButton)

        voiceButton = pill("")
        voiceButton.setOnClickListener { showVoiceDialog() }
        header.addView(voiceButton, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) })

        root.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))

        // the conversation; it scrolls to the newest message by itself
        chatBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(chatBox)
        }
        val keepBottom = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            scroll.post { scroll.smoothScrollTo(0, chatBox.height) }
        }
        chatBox.addOnLayoutChangeListener(keepBottom)
        scroll.addOnLayoutChangeListener(keepBottom)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(8) })

        // quick buttons
        chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        }
        root.addView(chipScroll, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })

        // typing row
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        input = EditText(this).apply {
            textSize = 16f
            setTextColor(cText)
            setHintTextColor(cSub)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            setSingleLine(true)
            background = rounded(cSurface, 24)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendTyped()
                    true
                } else {
                    false
                }
            }
        }
        row.addView(input, LinearLayout.LayoutParams(0, WRAP, 1f))

        val send = TextView(this).apply {
            text = "➤"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = circle(cPinkDeep)
            contentDescription = "Send"
            setOnClickListener { sendTyped() }
        }
        row.addView(send, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(8) })
        root.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })

        // the big mic
        micButton = TextView(this).apply {
            text = "🎤"
            textSize = 32f
            gravity = Gravity.CENTER
            contentDescription = "Talk to Thala"
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(cPink, cPurple)).apply {
                setShape(GradientDrawable.OVAL)
            }
            elevation = dp(6).toFloat()
            setOnClickListener { onMicTap() }
        }
        root.addView(micButton, LinearLayout.LayoutParams(dp(80), dp(80)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(12)
        })

        return root
    }

    private fun refreshLanguageUi() {
        langButton.text = if (tamilMode) "🌐 தமிழ்" else "🌐 English"
        voiceButton.text = if (tamilMode) "🎙 குரல்" else "🎙 Voice"
        input.hint = tr("Type here…", "இங்கே தட்டச்சு செய்யுங்கள்…")
        refreshChips()
        setStatus(idleText())
    }

    private fun refreshChips() {
        chips.removeAllViews()
        val items = if (tamilMode) chipsTa else chipsEn
        for ((label, sentence) in items) {
            val chip = pill(label)
            chip.setOnClickListener { runCommand(sentence) }
            chips.addView(chip, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(8) })
        }
    }

    private fun toggleLanguage() {
        tamilMode = !tamilMode
        prefs.edit().putBoolean("tamil_mode", tamilMode).apply()
        resetListening(true)
        refreshLanguageUi()
    }

    private fun addBubble(msg: String, fromUser: Boolean) {
        val bubble = TextView(this).apply {
            text = msg
            textSize = 16f
            setTextColor(if (fromUser) Color.WHITE else cText)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(if (fromUser) cPinkDeep else cSurface, 18)
            maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
        }
        val lp = LinearLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = if (fromUser) Gravity.END else Gravity.START
            topMargin = dp(8)
        }
        chatBox.addView(bubble, lp)
    }

    private fun setStatus(s: String) {
        statusLine.text = s
    }

    private fun idleText() = tr("Tap the mic and talk to me", "மைக்கைத் தட்டிப் பேசுங்கள்")

    private fun startPulse() {
        if (pulse != null) return
        pulse = ObjectAnimator.ofPropertyValuesHolder(
            micButton,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.16f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.16f)
        ).apply {
            duration = 650
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    private fun stopPulse() {
        pulse?.cancel()
        pulse = null
        micButton.scaleX = 1f
        micButton.scaleY = 1f
    }

    // ================= 4. LISTENING =================

    private fun onMicTap() {
        if (listening) {
            // finish now; if the phone never answers, reset after 3 seconds so the mic is never stuck
            val mine = session
            recognizer?.stopListening()
            micButton.postDelayed({ if (listening && session == mine) resetListening(true) }, 3000)
        } else {
            startListening()
        }
    }

    private fun startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askMic()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            notice(msgNoRecognizer)
            return
        }
        tts?.stop()
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (tamilMode) "ta-IN" else "en-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        session++
        listening = true
        setStatus(tr("Listening…", "கேட்கிறேன்…"))
        startPulse()
        r.startListening(i)
    }

    private fun resetListening(cancel: Boolean) {
        if (cancel) recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        listening = false
        stopPulse()
        setStatus(idleText())
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onEndOfSpeech() {
            runOnUiThread { if (listening) setStatus(tr("Thinking…", "யோசிக்கிறேன்…")) }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank()) runOnUiThread { if (listening) setStatus("“$text”") }
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            runOnUiThread { listenDone(text) }
        }

        override fun onError(error: Int) {
            runOnUiThread { listenFailed(error) }
        }
    }

    private fun listenDone(text: String?) {
        resetListening(false)
        if (text.isNullOrBlank()) {
            notice(msgNoHear)
        } else {
            runCommand(text)
        }
    }

    private fun listenFailed(error: Int) {
        resetListening(false)
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> notice(msgNoHear)

            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> askMic()

            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER -> notice(msgNetwork)

            12, 13 -> notice(msgLang)   // language not supported / not downloaded (Android 12+)

            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> notice(msgBusy)

            SpeechRecognizer.ERROR_CLIENT -> {}   // we cancelled it ourselves

            else -> notice(msgNoHear)
        }
    }

    // ================= 5. PERMISSIONS =================

    /** First launch only: ask for mic, contacts and calling in one go. */
    private fun askPermissionsOnce() {
        if (prefs.getBoolean("asked_all", false)) return
        prefs.edit().putBoolean("asked_all", true).apply()
        val need = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), REQ_FIRST)
    }

    private fun askMic() {
        val askedBefore = prefs.getBoolean("asked_mic", false)
        if (askedBefore && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            // "Don't ask again" was chosen: only the Settings screen can turn it on now
            notice(msgNoMic)
            openAppSettings()
            return
        }
        prefs.edit().putBoolean("asked_mic", true).apply()
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_MIC) return
        val at = permissions.indexOf(Manifest.permission.RECORD_AUDIO)
        val granted = at >= 0 && grantResults.getOrNull(at) == PackageManager.PERMISSION_GRANTED
        if (granted) startListening() else notice(msgNoMic)
    }

    private fun openAppSettings() {
        go(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    // ================= 6. UNDERSTANDING AND ANSWERING =================

    private fun sendTyped() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        runCommand(text)
    }

    /** One sentence in (spoken or typed): show it, let the Brain do the job, show and say the answer. */
    private fun runCommand(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        if (listening) resetListening(true)
        addBubble(text, true)
        val tamil = isTamil(text)
        val answer = try {
            brain.handle(text)
        } catch (e: Exception) {
            Log.e("Thala", "Brain failed on: $text", e)
            msgFailed
        }
        respond(answer, tamil)
    }

    /** Thala's own messages (errors, hints): in the language she is set to listen for. */
    private fun notice(r: Reply) = respond(r, tamilMode)

    private fun respond(r: Reply, tamil: Boolean) {
        addBubble(if (tamil) r.ta else r.en, false)
        speakReply(r, tamil)
    }

    /** She answers in the language you used: Tamil letters in -> Tamil out, otherwise English. */
    private fun isTamil(s: String) = s.any { it in '\u0B80'..'\u0BFF' }

    private fun tr(en: String, ta: String) = if (tamilMode) ta else en

    // ================= 7. THALA'S VOICE =================

    private fun onTtsReady(code: Int) {
        val engine = tts ?: return
        if (code != TextToSpeech.SUCCESS) {
            ttsReady = false
            addBubble(
                "I can't find a text-to-speech engine on this phone, so I can show answers but not say them. " +
                    "Install “Speech Recognition & Synthesis” by Google.",
                false
            )
            return
        }
        ttsReady = true
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                speechEnded(utteranceId)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                speechEnded(utteranceId)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                speechEnded(utteranceId)
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                speechEnded(utteranceId)
            }
        })
        speakReply(greeting, tamilMode)
    }

    /** Only the sentence that is current may reset the screen (a stopped older one must not). */
    private fun speechEnded(id: String?) {
        if (id != currentUtt) return
        runOnUiThread {
            if (!listening) {
                stopPulse()
                setStatus(idleText())
            }
        }
    }

    private fun speakReply(r: Reply, tamil: Boolean) {
        if (!ttsReady) return
        if (tamil) {
            if (say(r.ta, "ta")) return
            // no Tamil voice on this phone: say the English sentence so she still talks
            if (!warnedNoTamilVoice) {
                warnedNoTamilVoice = true
                toast("Tamil voice is not installed. Open Voice > More voices and install Tamil.")
            }
        }
        say(r.en, "en")
    }

    /** Says one sentence in "en" or "ta". False if the phone has no voice for that language. */
    private fun say(line: String, lang: String): Boolean {
        val engine = tts
        if (engine == null || !ttsReady || !prepareVoice(lang)) return false
        val id = "thala-" + System.nanoTime()
        currentUtt = id
        setStatus(tr("Speaking…", "பேசுகிறேன்…"))
        startPulse()
        val result = engine.speak(line, TextToSpeech.QUEUE_FLUSH, null, id)
        if (result != TextToSpeech.SUCCESS) {
            currentUtt = ""
            if (!listening) {
                stopPulse()
                setStatus(idleText())
            }
            return false
        }
        return true
    }

    /** Language + chosen voice + the girl-voice pitch and speed. False if that language has no voice here. */
    private fun prepareVoice(lang: String): Boolean {
        val engine = tts ?: return false
        val ok = if (lang == "ta") {
            engine.setLanguage(Locale.forLanguageTag("ta-IN")) >= TextToSpeech.LANG_AVAILABLE
        } else {
            engine.setLanguage(Locale.forLanguageTag("en-IN")) >= TextToSpeech.LANG_AVAILABLE ||
                engine.setLanguage(Locale.US) >= TextToSpeech.LANG_AVAILABLE
        }
        if (!ok) return false
        pickVoice(lang)?.let {
            try {
                engine.setVoice(it)
            } catch (e: Exception) {
                Log.w("Thala", "Could not use voice ${it.name}", e)
            }
        }
        engine.setPitch(pitch)
        engine.setSpeechRate(rate)
        return true
    }

    /** The voice you picked, else the first one on the list (female-sounding, Indian, offline voices come first). */
    private fun pickVoice(lang: String): Voice? {
        val list = voicesFor(lang)
        val saved = prefs.getString("voice_$lang", null)
        return list.firstOrNull { it.name == saved } ?: list.firstOrNull()
    }

    /** All installed voices of one language ("en" or "ta"). */
    private fun voicesFor(lang: String): List<Voice> {
        val engine = tts ?: return emptyList()
        val all: Set<Voice> = try {
            engine.voices ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }
        return all
            .filter { v ->
                v.locale.language.take(2) == lang &&
                    v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
            }
            .sortedWith(
                compareByDescending<Voice> { looksFemale(it.name) }
                    .thenByDescending { it.locale.country == "IN" || it.locale.country == "IND" }
                    .thenBy { it.isNetworkConnectionRequired }
                    .thenBy { it.name }
            )
    }

    /** Some engines put the gender in the voice name (e.g. "female" or Samsung's "smtf00"). */
    private fun looksFemale(name: String): Boolean {
        val n = name.lowercase(Locale.ROOT)
        return n.contains("female") || Regex("smtf\\d").containsMatchIn(n)
    }

    private fun sample(lang: String) {
        say(
            if (lang == "ta") "வணக்கம்! நான் தல. என் குரல் எப்படி இருக்கிறது?" else "Hi! I'm Thala. How do I sound?",
            lang
        )
    }

    /** Voice menu: pick a voice (tap to hear it), then tune pitch and speed. */
    private fun showVoiceDialog() {
        if (!ttsReady) {
            toast(tr("My voice is not ready yet. Try again in a moment.", "என் குரல் இன்னும் தயாராகவில்லை. சிறிது நேரம் கழித்து முயலுங்கள்."))
            return
        }
        val lang = if (tamilMode) "ta" else "en"
        val voices = voicesFor(lang)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        box.addView(TextView(this).apply {
            text = if (tamilMode) "தமிழ் குரல்கள் (தட்டிக் கேளுங்கள்)" else "English voices (tap to hear)"
            textSize = 13f
            setTextColor(cSub)
        })

        if (voices.isEmpty()) {
            box.addView(TextView(this).apply {
                text = tr(
                    "No voice found for this language. Tap “More voices” to install one.",
                    "இந்த மொழிக்கு குரல் இல்லை. “மேலும் குரல்கள்” என்பதைத் தட்டி நிறுவுங்கள்."
                )
                textSize = 15f
                setTextColor(cText)
                setPadding(0, dp(12), 0, dp(12))
            })
        } else {
            val names = voices.mapIndexed { i, v ->
                "Voice ${i + 1}  ·  ${v.locale.toLanguageTag()}" + (if (v.isNetworkConnectionRequired) "  (online)" else "")
            }
            val saved = prefs.getString("voice_$lang", null)
            val list = ListView(this).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_single_choice, names)
                choiceMode = ListView.CHOICE_MODE_SINGLE
                setItemChecked(maxOf(0, voices.indexOfFirst { it.name == saved }), true)
                setOnItemClickListener { _, _, pos, _ ->
                    prefs.edit().putString("voice_$lang", voices[pos].name).apply()
                    sample(lang)
                }
            }
            box.addView(list, LinearLayout.LayoutParams(MATCH, dp(220)))
        }

        addSlider(box, tr("Pitch (higher = younger)", "சுருதி (அதிகம் = இளமை)"), 0.8f, 1.8f, pitch) { v ->
            pitch = v
            prefs.edit().putFloat("pitch", v).apply()
            sample(lang)
        }
        addSlider(box, tr("Speed", "வேகம்"), 0.7f, 1.5f, rate) { v ->
            rate = v
            prefs.edit().putFloat("rate", v).apply()
            sample(lang)
        }

        val body = ScrollView(this).apply { addView(box) }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(tr("Voice", "குரல்"))
            .setView(body)
            .setPositiveButton(tr("Done", "சரி"), null)
            .setNeutralButton(tr("More voices", "மேலும் குரல்கள்")) { _, _ -> openVoiceSettings() }
            .show()
    }

    private fun addSlider(parent: LinearLayout, title: String, lo: Float, hi: Float, startValue: Float, onDone: (Float) -> Unit) {
        val label = TextView(this).apply {
            textSize = 14f
            setTextColor(cText)
            setPadding(0, dp(14), 0, 0)
        }
        fun showValue(v: Float) {
            label.text = title + ": " + String.format(Locale.US, "%.2f", v)
        }
        val bar = SeekBar(this).apply {
            max = 100
            progress = (((startValue - lo) / (hi - lo)) * 100f).toInt().coerceIn(0, 100)
        }
        showValue(startValue)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                showValue(lo + (hi - lo) * value / 100f)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {
                onDone(lo + (hi - lo) * (sb?.progress ?: 0) / 100f)
            }
        })
        parent.addView(label)
        parent.addView(bar)
    }

    /** Opens the phone's text-to-speech settings, where more voices (like Tamil) can be installed. */
    private fun openVoiceSettings() {
        if (go(Intent("com.android.settings.TTS_SETTINGS"))) return
        if (go(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))) return
        go(Intent(Settings.ACTION_SETTINGS))
    }

    // ================= 8. SMALL HELPERS =================

    private fun go(i: Intent): Boolean = try {
        startActivity(i)
        true
    } catch (e: Exception) {
        false
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.setCornerRadius(dp(radiusDp).toFloat())
        return d
    }

    private fun circle(color: Int): GradientDrawable {
        val d = GradientDrawable()
        d.setShape(GradientDrawable.OVAL)
        d.setColor(color)
        return d
    }

    private fun pill(label: String): TextView = TextView(this).apply {
        text = label
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(cText)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = rounded(cSurface, 20)
    }
}
