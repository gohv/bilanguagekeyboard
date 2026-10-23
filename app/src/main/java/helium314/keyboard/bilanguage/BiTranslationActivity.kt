// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.bilanguage

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import helium314.keyboard.latin.R
import helium314.keyboard.bilanguage.cloud.ApiKeyStore
import helium314.keyboard.bilanguage.cloud.CloudConfig
import helium314.keyboard.bilanguage.cloud.CloudProvider
import helium314.keyboard.bilanguage.engine.CloudLlmEngine
import helium314.keyboard.bilanguage.engine.MlKitEngine
import helium314.keyboard.bilanguage.engine.PrepareState
import helium314.keyboard.bilanguage.llm.LocalLlmModelStore
import helium314.keyboard.bilanguage.llm.LocalLlmService
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
class BiTranslationActivity : Activity() {

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
        if (!BiPrefs.disclosureAccepted(this)) renderDisclosure() else renderManagement()
    }

    // ---------------- first-run disclosure ----------------

    /**
     * Prose, not legal boilerplate. This screen is the entire trust proposition: a keyboard that can
     * reach the network is one the user has to actively trust about keylogging, so it says exactly
     * what happens and exactly what does not.
     */
    private fun renderDisclosure() {
        title(getString(R.string.blk_disclosure_title))
        body(
            "BiLanguageKeyboard translates what you type into another language, and it does the translating " +
                "here, on this phone."
        )
        body(
            "What you type is never sent anywhere. There is no server behind this keyboard, no " +
                "account, and no analytics. Nothing you write is uploaded, and nothing you write is " +
                "saved to disk by BiLanguageKeyboard."
        )
        body(
            "The one exception is opt-in: if you choose the Cloud LLM engine and add your own API key, " +
                "the text you translate is sent to the provider you picked. The two on-device engines " +
                "never do that, and Cloud LLM is never selected for you."
        )
        heading("Why the keyboard asks for internet access")
        body(
            "The Google ML Kit option downloads a model from Google once per language, roughly " +
                "${MlKitEngine.APPROX_MODEL_MB}MB each. Those user-requested downloads are the only reason " +
                "BiLanguageKeyboard has the INTERNET permission, apart from the opt-in Cloud LLM engine. The " +
                "Local LLM option never downloads anything: you point it at a GGUF file already on the " +
                "phone, and it is read where it is."
        )
        body(
            "Downloads never start on their own. You pick the languages on the next screen. By " +
                "default they only happen on Wi-Fi. Once a model is on the phone, translation works " +
                "with the network off entirely, and you can check that with aeroplane mode."
        )
        body(
            "Every download is written to the log under the tag BiLanguageKeyboard, so the claim above is " +
                "something you can verify rather than something you have to believe."
        )
        heading("What is honestly worse about this")
        body(
            "Google ML Kit is compact and supports many languages, but is weaker than a full online " +
                "translator. A Local LLM can be much better, but it is only as good as the GGUF you choose, " +
                "uses far more memory, and the first translation waits for the model to load."
        )
        body("Translation is turned off automatically in password, email, URL, phone and number fields.")

        val accept = Button(this).apply {
            text = getString(R.string.blk_disclosure_accept)
            setOnClickListener {
                BiPrefs.setDisclosureAccepted(this@BiTranslationActivity, true)
                BiLog.i("first-run disclosure accepted")
                render()
            }
        }
        root.addView(accept, wide())
    }

    // ---------------- model management ----------------

    private fun renderManagement() {
        title(getString(R.string.blk_models_title))

        heading(getString(R.string.blk_engine_heading))
        val engines = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }
        val mlKit = RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.blk_engine_mlkit)
        }
        val localLlm = RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.blk_engine_local_llm)
            isEnabled = LocalLlmModelStore.isSupportedAbi()
        }
        val cloudLlm = RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.blk_engine_cloud_llm)
        }
        engines.addView(mlKit, wide())
        engines.addView(localLlm, wide())
        engines.addView(cloudLlm, wide())
        val selected = BiPrefs.engine(this)
        engines.check(
            when (selected) {
                BiPrefs.ENGINE_LOCAL_LLM -> localLlm.id
                BiPrefs.ENGINE_CLOUD_LLM -> cloudLlm.id
                else -> mlKit.id
            }
        )
        engines.setOnCheckedChangeListener { _, checkedId ->
            val engine = when (checkedId) {
                localLlm.id -> BiPrefs.ENGINE_LOCAL_LLM
                cloudLlm.id -> BiPrefs.ENGINE_CLOUD_LLM
                else -> BiPrefs.ENGINE_MLKIT
            }
            if (engine != BiPrefs.engine(this@BiTranslationActivity)) {
                BiPrefs.setEngine(this@BiTranslationActivity, engine)
                BiLog.i("translation engine changed to $engine")
                render()
            }
        }
        root.addView(engines, wide())
        hint(getString(R.string.blk_engine_hint))

        val engine = BiPrefs.engine(this)
        if (engine == BiPrefs.ENGINE_LOCAL_LLM) {
            renderLocalLlmManagement()
        } else if (engine == BiPrefs.ENGINE_CLOUD_LLM) {
            renderCloudLlmManagement()
        } else {
            val unmetered = CheckBox(this).apply {
                text = getString(R.string.blk_unmetered_only)
                isChecked = BiPrefs.unmeteredOnly(this@BiTranslationActivity)
                setOnCheckedChangeListener { _, checked ->
                    BiPrefs.setUnmeteredOnly(this@BiTranslationActivity, checked)
                }
            }
            root.addView(unmetered, wide())
        }

        val translateBox = CheckBox(this).apply {
            text = getString(R.string.blk_translate_box)
            isChecked = BiPrefs.translateBox(this@BiTranslationActivity)
            setOnCheckedChangeListener { _, checked ->
                BiPrefs.setTranslateBox(this@BiTranslationActivity, checked)
            }
        }
        root.addView(translateBox, wide())
        hint(getString(R.string.blk_translate_box_hint))

        val livePreview = CheckBox(this).apply {
            text = getString(R.string.blk_live_preview)
            isChecked = BiPrefs.livePreviewActive(this@BiTranslationActivity)
            isEnabled = engine != BiPrefs.ENGINE_CLOUD_LLM
            setOnCheckedChangeListener { _, checked ->
                BiPrefs.setLivePreview(this@BiTranslationActivity, checked)
            }
        }
        root.addView(livePreview, wide())
        hint(
            getString(
                if (engine == BiPrefs.ENGINE_CLOUD_LLM) R.string.blk_live_preview_cloud_off
                else R.string.blk_live_preview_hint
            )
        )

        val incognito = CheckBox(this).apply {
            text = getString(R.string.blk_allow_incognito)
            isChecked = BiPrefs.allowIncognitoApps(this@BiTranslationActivity)
            setOnCheckedChangeListener { _, checked ->
                BiPrefs.setAllowIncognitoApps(this@BiTranslationActivity, checked)
            }
        }
        root.addView(incognito, wide())
        hint(getString(R.string.blk_allow_incognito_hint))

        if (engine != BiPrefs.ENGINE_MLKIT) {
            return
        }

        heading(getString(R.string.blk_models_heading))
        hint(getString(R.string.blk_models_size_note, MlKitEngine.APPROX_MODEL_MB))

        val listHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listHolder, wide())
        attribution()

        loadModelList(listHolder)
    }

    /**
     * The GGUF files the keyboard has been pointed at, one active. Adding one only records the file and
     * takes read access; switching is a tap, and the model process loads the new one on next use.
     */
    private fun renderLocalLlmManagement() {
        heading(getString(R.string.blk_local_model_heading))
        hint(getString(R.string.blk_local_model_description))
        val models = LocalLlmModelStore.models(this)
        val active = LocalLlmModelStore.active(this)
        if (models.isEmpty()) body(getString(R.string.blk_local_model_missing))

        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        models.forEach { model ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val radio = RadioButton(this).apply {
                id = View.generateViewId()
                text = model.label + "\n" + modelDetails(model)
                isChecked = model.path == active?.path
                setOnClickListener {
                    if (model.path == LocalLlmModelStore.active(this@BiTranslationActivity)?.path) return@setOnClickListener
                    LocalLlmModelStore.setActive(this@BiTranslationActivity, model)
                    LocalLlmService.requestUnload(this@BiTranslationActivity)
                    BiLog.i("active local model changed")
                    render()
                }
            }
            val remove = Button(this).apply {
                text = getString(R.string.blk_local_model_remove)
                isEnabled = !busy
                setOnClickListener {
                    if (model.path == LocalLlmModelStore.active(this@BiTranslationActivity)?.path) {
                        LocalLlmService.requestUnload(this@BiTranslationActivity)
                    }
                    LocalLlmModelStore.remove(this@BiTranslationActivity, model)
                    Toast.makeText(this@BiTranslationActivity, R.string.blk_local_model_removed, Toast.LENGTH_SHORT).show()
                    render()
                }
            }
            row.addView(radio, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(remove)
            group.addView(row, wide())
        }
        root.addView(group, wide())

        root.addView(Button(this).apply {
            text = getString(R.string.blk_local_model_add)
            isEnabled = !busy
            setOnClickListener { openLocalModelPicker() }
        }, wide())
    }

    private fun modelDetails(model: LocalLlmModelStore.Model): String {
        val parts = mutableListOf<String>()
        if (model.modelName != null && model.modelName != model.fileName) parts += model.fileName
        model.architecture?.let { parts += it }
        if (model.bytes > 0) parts += "%.1f GB".format(model.bytes / 1e9)
        parts += getString(if (model.chat) R.string.blk_local_kind_chat else R.string.blk_local_kind_raw)
        return parts.joinToString(" · ")
    }

    /**
     * Provider, base URL, model and key. The key field is never filled back in: once saved, the key is
     * only identified by its last four characters, and an empty field on save keeps it.
     */
    private fun renderCloudLlmManagement() {
        heading(getString(R.string.blk_cloud_heading))
        body(getString(R.string.blk_cloud_warning))

        val saved = BiPrefs.cloudConfig(this)
        hint(getString(R.string.blk_cloud_provider))
        val providers = CloudProvider.entries
        val providerSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@BiTranslationActivity,
                android.R.layout.simple_spinner_dropdown_item,
                providers.map { it.label },
            )
            setSelection(providers.indexOf(saved.provider))
        }
        root.addView(providerSpinner, wide())

        hint(getString(R.string.blk_cloud_base_url))
        val baseUrl = EditText(this).apply {
            setText(saved.baseUrl)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        root.addView(baseUrl, wide())

        hint(getString(R.string.blk_cloud_model))
        val model = EditText(this).apply {
            setText(saved.model)
            hint = getString(R.string.blk_cloud_model_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
        }
        root.addView(model, wide())

        // Switching preset replaces URL and model with its defaults; the restored selection on first
        // layout must not clobber what the user saved.
        var currentProvider = saved.provider
        providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val picked = providers[position]
                if (picked == currentProvider) return
                currentProvider = picked
                baseUrl.setText(picked.defaultBaseUrl)
                model.setText(picked.defaultModel)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        hint(getString(R.string.blk_cloud_api_key))
        val keyHint = ApiKeyStore.hint(this)
        val apiKey = EditText(this).apply {
            hint = keyHint?.let { getString(R.string.blk_cloud_api_key_hint_saved, it) }
                ?: getString(R.string.blk_cloud_api_key_hint_empty)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        root.addView(apiKey, wide())
        hint(getString(R.string.blk_cloud_key_note))

        fun save() {
            BiPrefs.setCloudConfig(
                this,
                CloudConfig(currentProvider, baseUrl.text.toString(), model.text.toString()),
            )
            apiKey.text.toString().trim().takeIf { it.isNotEmpty() }?.let { ApiKeyStore.set(this, it) }
        }

        root.addView(Button(this).apply {
            text = getString(R.string.blk_cloud_save)
            setOnClickListener {
                save()
                Toast.makeText(this@BiTranslationActivity, R.string.blk_cloud_saved, Toast.LENGTH_SHORT).show()
                render()
            }
        }, wide())

        root.addView(Button(this).apply {
            text = getString(R.string.blk_cloud_test)
            isEnabled = !busy
            setOnClickListener {
                save()
                testCloudProvider()
            }
        }, wide())

        if (keyHint != null) {
            root.addView(Button(this).apply {
                text = getString(R.string.blk_cloud_remove_key)
                setOnClickListener {
                    ApiKeyStore.clear(this@BiTranslationActivity)
                    Toast.makeText(this@BiTranslationActivity, R.string.blk_cloud_key_removed, Toast.LENGTH_SHORT).show()
                    render()
                }
            }, wide())
        }
    }

    /** Sends a fixed sample, never anything the user typed, so the test itself discloses nothing. */
    private fun testCloudProvider() {
        busy = true
        render()
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                CloudLlmEngine(this@BiTranslationActivity).translate("Good morning", "en", "fr")
            }
            busy = false
            Toast.makeText(
                this@BiTranslationActivity,
                result.fold(
                    { getString(R.string.blk_cloud_test_ok, it) },
                    { getString(R.string.blk_cloud_test_failed, it.message ?: it.javaClass.simpleName) },
                ),
                Toast.LENGTH_LONG,
            ).show()
            render()
        }
    }

    @Suppress("DEPRECATION")
    private fun openLocalModelPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_LOCAL_MODEL)
    }

    @Deprecated("Uses the platform document picker for compatibility with plain Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_LOCAL_MODEL || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        busy = true
        render()
        scope.launch {
            val result = withContext(Dispatchers.IO) { LocalLlmModelStore.add(this@BiTranslationActivity, uri) }
            busy = false
            result.onSuccess {
                LocalLlmService.requestUnload(this@BiTranslationActivity)
                Toast.makeText(this@BiTranslationActivity, getString(R.string.blk_local_model_added, it.label), Toast.LENGTH_LONG).show()
            }.onFailure { error ->
                Toast.makeText(
                    this@BiTranslationActivity,
                    getString(R.string.blk_local_model_import_failed, error.message ?: "unknown error"),
                    Toast.LENGTH_LONG,
                ).show()
            }
            render()
        }
    }

    private fun loadModelList(holder: LinearLayout) {
        holder.removeAllViews()
        hintInto(holder, getString(R.string.blk_loading))
        scope.launch {
            val engine = MlKitEngine(requireWifi = BiPrefs.unmeteredOnly(this@BiTranslationActivity))
            val downloaded = withContext(Dispatchers.Default) { engine.downloadedLanguageTags() }
            engine.close()
            // Cache it so the keyboard can default the pair to a language the user actually has.
            BiPrefs.setKnownModelLanguages(this@BiTranslationActivity, downloaded)
            holder.removeAllViews()

            // Downloaded first, then the rest. With 58 languages an unsorted list is unusable.
            val all = withContext(Dispatchers.Default) {
                com.google.mlkit.nl.translate.TranslateLanguage.getAllLanguages()
                    .sortedWith(compareByDescending<String> { it in downloaded }
                        .thenBy { MlKitEngine.displayName(it) })
            }
            if (downloaded.isEmpty()) {
                hintInto(holder, getString(R.string.blk_no_models_yet))
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
                getString(R.string.blk_model_ready, MlKitEngine.APPROX_MODEL_MB)
            else getString(R.string.blk_model_absent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            alpha = 0.7f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        val action = Button(this).apply {
            text = getString(if (isDownloaded) R.string.blk_delete else R.string.blk_download)
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
        Toast.makeText(this, getString(R.string.blk_downloading, MlKitEngine.displayName(tag)), Toast.LENGTH_SHORT).show()
        scope.launch {
            val engine = MlKitEngine(requireWifi = BiPrefs.unmeteredOnly(this@BiTranslationActivity))
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
                Toast.makeText(this@BiTranslationActivity, getString(R.string.blk_download_failed, it), Toast.LENGTH_LONG).show()
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
            if (!ok) Toast.makeText(this@BiTranslationActivity, R.string.blk_delete_failed, Toast.LENGTH_LONG).show()
            loadModelList(holder)
        }
    }

    // ---------------- small view helpers ----------------

    /** Required by Google's ML Kit terms when translation is offered in an app. */
    private fun attribution() {
        val t = TextView(this).apply {
            text = getString(R.string.blk_mlkit_attribution)
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

    companion object {
        private const val REQUEST_LOCAL_MODEL = 4107
    }
}
