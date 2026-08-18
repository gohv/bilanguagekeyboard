// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.polyboard

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import helium314.keyboard.latin.R
import helium314.keyboard.polyboard.engine.MlKitEngine
import helium314.keyboard.polyboard.engine.PrepareState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The first-run disclosure (spec section 7) and the model management screen.
 *
 * Written with plain views rather than Compose. Upstream's settings are Compose with their own
 * navigation host and a settings registry; adding two screens into that would mean adapting to a
 * structure that has nothing to do with translation. A standalone activity keeps the fork's surface
 * small and easy to rebase.
 *
 * The disclosure is shown first and blocks everything else until accepted, because until it is
 * accepted no model may be downloaded and no translation may run.
 */
class PolyTranslationActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var root: LinearLayout
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        // targetSdk 36 means edge-to-edge, so without this the title sits under the status bar and
        // the last row sits under the navigation bar.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        render()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun render() {
        root.removeAllViews()
        if (!PolyPrefs.disclosureAccepted(this)) renderDisclosure() else renderManagement()
    }

    // ---------------- first-run disclosure ----------------

    /**
     * Prose, not legal boilerplate. This screen is the entire trust proposition: a keyboard that can
     * reach the network is one the user has to actively trust about keylogging, so it says exactly
     * what happens and exactly what does not.
     */
    private fun renderDisclosure() {
        title(getString(R.string.poly_disclosure_title))
        body(
            "PolyBoard translates what you type into another language, and it does the translating " +
                "here, on this phone."
        )
        body(
            "What you type is never sent anywhere. There is no server behind this keyboard, no " +
                "account, and no analytics. Nothing you write is uploaded, and nothing you write is " +
                "saved to disk by PolyBoard."
        )
        heading("Why the keyboard asks for internet access")
        body(
            "Translation needs a language model, and those models are downloaded from Google once " +
                "per language, roughly ${MlKitEngine.APPROX_MODEL_MB}MB each. That download is the " +
                "only reason PolyBoard has the INTERNET permission, and it is the only time the " +
                "keyboard touches the network."
        )
        body(
            "Downloads never start on their own. You pick the languages on the next screen. By " +
                "default they only happen on Wi-Fi. Once a model is on the phone, translation works " +
                "with the network off entirely, and you can check that with aeroplane mode."
        )
        body(
            "Every download is written to the log under the tag PolyBoard, so the claim above is " +
                "something you can verify rather than something you have to believe."
        )
        heading("What is honestly worse about this")
        body(
            "Translation quality is Google's on-device quality: good for ordinary messages, weaker " +
                "than a full online translator. Language pairs that do not involve English are " +
                "translated via English internally, which costs accuracy."
        )
        body("Translation is turned off automatically in password, email, URL, phone and number fields.")

        attribution()

        val accept = Button(this).apply {
            text = getString(R.string.poly_disclosure_accept)
            setOnClickListener {
                PolyPrefs.setDisclosureAccepted(this@PolyTranslationActivity, true)
                PolyLog.i("first-run disclosure accepted")
                render()
            }
        }
        root.addView(accept, wide())
    }

    // ---------------- model management ----------------

    private fun renderManagement() {
        title(getString(R.string.poly_models_title))

        val unmetered = CheckBox(this).apply {
            text = getString(R.string.poly_unmetered_only)
            isChecked = PolyPrefs.unmeteredOnly(this@PolyTranslationActivity)
            setOnCheckedChangeListener { _, checked ->
                PolyPrefs.setUnmeteredOnly(this@PolyTranslationActivity, checked)
            }
        }
        root.addView(unmetered, wide())

        val livePreview = CheckBox(this).apply {
            text = getString(R.string.poly_live_preview)
            isChecked = PolyPrefs.livePreview(this@PolyTranslationActivity)
            setOnCheckedChangeListener { _, checked ->
                PolyPrefs.setLivePreview(this@PolyTranslationActivity, checked)
            }
        }
        root.addView(livePreview, wide())
        hint(getString(R.string.poly_live_preview_hint))

        val incognito = CheckBox(this).apply {
            text = getString(R.string.poly_allow_incognito)
            isChecked = PolyPrefs.allowIncognitoApps(this@PolyTranslationActivity)
            setOnCheckedChangeListener { _, checked ->
                PolyPrefs.setAllowIncognitoApps(this@PolyTranslationActivity, checked)
            }
        }
        root.addView(incognito, wide())
        hint(getString(R.string.poly_allow_incognito_hint))

        val useStub = CheckBox(this).apply {
            text = getString(R.string.poly_use_stub)
            isChecked = PolyPrefs.engine(this@PolyTranslationActivity) == PolyPrefs.ENGINE_STUB
            setOnCheckedChangeListener { _, checked ->
                PolyPrefs.setEngine(
                    this@PolyTranslationActivity,
                    if (checked) PolyPrefs.ENGINE_STUB else PolyPrefs.ENGINE_MLKIT
                )
                render()
            }
        }
        root.addView(useStub, wide())

        if (PolyPrefs.engine(this) == PolyPrefs.ENGINE_STUB) {
            hint(getString(R.string.poly_stub_active))
            attribution()
            return
        }

        heading(getString(R.string.poly_models_heading))
        hint(getString(R.string.poly_models_size_note, MlKitEngine.APPROX_MODEL_MB))

        val listHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listHolder, wide())
        attribution()

        loadModelList(listHolder)
    }

    private fun loadModelList(holder: LinearLayout) {
        holder.removeAllViews()
        hintInto(holder, getString(R.string.poly_loading))
        scope.launch {
            val engine = MlKitEngine(requireWifi = PolyPrefs.unmeteredOnly(this@PolyTranslationActivity))
            val downloaded = withContext(Dispatchers.Default) { engine.downloadedLanguageTags() }
            engine.close()
            // Cache it so the keyboard can default the pair to a language the user actually has.
            PolyPrefs.setKnownModelLanguages(this@PolyTranslationActivity, downloaded)
            holder.removeAllViews()

            // Downloaded first, then the rest. With 58 languages an unsorted list is unusable.
            val all = withContext(Dispatchers.Default) {
                com.google.mlkit.nl.translate.TranslateLanguage.getAllLanguages()
                    .sortedWith(compareByDescending<String> { it in downloaded }
                        .thenBy { MlKitEngine.displayName(it) })
            }
            if (downloaded.isEmpty()) {
                hintInto(holder, getString(R.string.poly_no_models_yet))
            }
            all.forEach { tag -> holder.addView(modelRow(tag, tag in downloaded, holder), wide()) }
        }
    }

    private fun modelRow(tag: String, isDownloaded: Boolean, holder: LinearLayout): View {
        val label = TextView(this).apply {
            text = MlKitEngine.displayName(tag) + "  (" + tag + ")"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
        }
        val status = TextView(this).apply {
            text = if (isDownloaded)
                getString(R.string.poly_model_ready, MlKitEngine.APPROX_MODEL_MB)
            else getString(R.string.poly_model_absent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            alpha = 0.7f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        val action = Button(this).apply {
            text = getString(if (isDownloaded) R.string.poly_delete else R.string.poly_download)
            setOnClickListener {
                if (busy) return@setOnClickListener
                if (isDownloaded) deleteModel(tag, holder) else downloadModel(tag, holder)
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(status)
            addView(action)
        }
    }

    /**
     * Downloads the model for one language. English is fetched alongside it, because every ML Kit
     * model translates between its language and English, so a single non-English model on its own
     * cannot translate anything.
     */
    private fun downloadModel(tag: String, holder: LinearLayout) {
        busy = true
        Toast.makeText(this, getString(R.string.poly_downloading, MlKitEngine.displayName(tag)), Toast.LENGTH_SHORT).show()
        scope.launch {
            val engine = MlKitEngine(requireWifi = PolyPrefs.unmeteredOnly(this@PolyTranslationActivity))
            var failure: String? = null
            engine.preparePair("en", tag).collect { state ->
                when (state) {
                    is PrepareState.Failed -> failure = state.reason
                    else -> Unit
                }
            }
            engine.close()
            busy = false
            failure?.let {
                Toast.makeText(this@PolyTranslationActivity, getString(R.string.poly_download_failed, it), Toast.LENGTH_LONG).show()
            }
            loadModelList(holder)
        }
    }

    private fun deleteModel(tag: String, holder: LinearLayout) {
        busy = true
        scope.launch {
            val engine = MlKitEngine()
            val ok = engine.deleteModel(tag)
            engine.close()
            busy = false
            if (!ok) Toast.makeText(this@PolyTranslationActivity, R.string.poly_delete_failed, Toast.LENGTH_LONG).show()
            loadModelList(holder)
        }
    }

    // ---------------- small view helpers ----------------

    /** Required by Google's ML Kit terms when translation is offered in an app. */
    private fun attribution() {
        val t = TextView(this).apply {
            text = getString(R.string.poly_mlkit_attribution)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            alpha = 0.7f
            setPadding(0, dp(16), 0, dp(8))
        }
        root.addView(t, wide())
    }

    private fun title(text: String) = root.addView(TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(12))
    }, wide())

    private fun heading(text: String) = root.addView(TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(16), 0, dp(6))
    }, wide())

    private fun body(text: String) = root.addView(TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setPadding(0, 0, 0, dp(10))
    }, wide())

    private fun hint(text: String) = hintInto(root, text)

    private fun hintInto(parent: LinearLayout, text: String) = parent.addView(TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        alpha = 0.7f
        setPadding(0, 0, 0, dp(10))
    }, wide())

    private fun wide() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun dp(v: Int) = (resources.displayMetrics.density * v).toInt()
}
