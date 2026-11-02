package net.guteam.gupet

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private const val SETTINGS_UI_PREFS = "gupet_ui"
private const val KEY_LANG = "lang"
private const val KEY_THEME = "ui_theme"
private const val ASSET_URL = "file:///android_asset/gupet/index.html"

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var overlayPermLauncher: ActivityResultLauncher<Intent>
    private lateinit var skinPickerLauncher: ActivityResultLauncher<String>
    private lateinit var exportSkinLauncher: ActivityResultLauncher<String>
    private lateinit var framesPickerLauncher: ActivityResultLauncher<String>
    private lateinit var framePickerLauncher: ActivityResultLauncher<String>
    private var pendingFrameSkinId: String? = null
    private var pendingFrameName: String? = null
    private var pendingExportSkinId: String? = null
    private var pendingFramesSkinId: String? = null

    override fun attachBaseContext(newBase: Context) {
        val ui = newBase.getSharedPreferences(SETTINGS_UI_PREFS, MODE_PRIVATE)
        val code = when (ui.getString(KEY_LANG, "auto")) {
            "ru", "RU" -> "ru"
            "en", "EN" -> "en"
            else -> null
        }
        if (code == null) {
            super.attachBaseContext(newBase)
            return
        }
        val config = Configuration(newBase.resources.configuration)
        config.setLocale(Locale(code))
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // П1: edge-to-edge + цвета system bars под тему.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applySystemBarsTheme()

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowFileAccessFromFileURLs = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url.toString()
                    return if (url.startsWith("http://") || url.startsWith("https://")) {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        true
                    } else {
                        false
                    }
                }
            }
            webChromeClient = WebChromeClient()
            addJavascriptInterface(PrefsBridge(this@MainActivity), "AndroidPrefs")
            addJavascriptInterface(PetBridge(this@MainActivity), "AndroidPet")
        }
        setContentView(webView)

        overlayPermLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                val granted = Settings.canDrawOverlays(this)
                eval("window.onOverlayPermissionChanged($granted)")
            }
        skinPickerLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri == null) {
                    eval("window.onSkinImported('', false)")
                    return@registerForActivityResult
                }
                try {
                    val info = GUPetSkinStore.importSkin(this, uri)
                    eval("window.onSkinImported(${JSONObject.quote(info.name)}, true)")
                } catch (e: Exception) {
                    eval("window.onSkinImported('', false)")
                }
            }
        exportSkinLauncher =
            registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
                val id = pendingExportSkinId
                pendingExportSkinId = null
                if (uri == null || id == null) {
                    eval("window.onSkinExported(false)")
                    return@registerForActivityResult
                }
                try {
                    val bytes = GUPetSkinStore.exportZip(this, id)
                    contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: throw IllegalArgumentException("Нет потока")
                    eval("window.onSkinExported(true)")
                } catch (e: Exception) {
                    eval("window.onSkinExported(false)")
                }
            }
        framesPickerLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                val id = pendingFramesSkinId
                pendingFramesSkinId = null
                if (uri == null || id == null) {
                    eval("window.onSkinFramesMerged('', 0)")
                    return@registerForActivityResult
                }
                try {
                    val stream = contentResolver.openInputStream(uri)
                        ?: throw IllegalArgumentException("Нет потока")
                    val added = stream.use { GUPetSkinStore.mergeFramesFromZip(this, id, it) }
                    if (added > 0) {
                        PetPrefs.file(this).edit()
                            .putLong(PetPrefs.CUSTOM_SKIN_REVISION, System.currentTimeMillis())
                            .apply()
                    }
                    eval("window.onSkinFramesMerged(${JSONObject.quote(id)}, $added)")
                } catch (e: Exception) {
                    eval("window.onSkinFramesMerged('', 0)")
                }
            }

        framePickerLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                val id = pendingFrameSkinId
                val frame = pendingFrameName
                pendingFrameSkinId = null
                pendingFrameName = null
                if (uri == null || id == null || frame == null) {
                    eval("window.onFrameUploaded('', '', false)")
                    return@registerForActivityResult
                }
                try {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalArgumentException("Нет потока")
                    val ok = GUPetSkinStore.writeFramePng(this, id, frame, bytes)
                    eval("window.onFrameUploaded(${JSONObject.quote(id)}, ${JSONObject.quote(frame)}, $ok)")
                    if (ok) {
                        // PNG-кадры изменились при том же id: бампаем revision,
                        // чтобы listener перезагрузил bitmap скина.
                        PetPrefs.file(this).edit()
                            .putLong(PetPrefs.CUSTOM_SKIN_REVISION, System.currentTimeMillis())
                            .apply()
                        runCatching {
                            startService(
                                Intent(this, OverlayPetService::class.java)
                                    .setAction(GUPetContract.ACTION_SETTINGS_CHANGED)
                            )
                        }
                    }
                } catch (e: Exception) {
                    eval("window.onFrameUploaded('', '', false)")
                }
            }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript(
                    "(function(){var s=document.querySelectorAll(" +
                        "'.settings-overlay.active,.color-picker-overlay.active,.confirm-overlay.active'" +
                        ");if(!s.length)return false;" +
                        "s.forEach(function(e){e.classList.remove('active')});return true;})()"
                ) { r ->
                    if (r != "true") {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        webView.loadUrl(ASSET_URL)
    }

    override fun onResume() {
        super.onResume()
        // П3: при возврате из системных настроек обновить статусы разрешений.
        runCatching { webView.evaluateJavascript("window.refreshPermissions && window.refreshPermissions()", null) }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    fun eval(js: String) {
        runOnUiThread { runCatching { webView.evaluateJavascript(js, null) } }
    }

    fun launchOverlayPermission() {
        overlayPermLauncher.launch(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    fun launchSkinPicker() {
        skinPickerLauncher.launch("*/*")
    }

    fun pendingExportSkin(id: String) {
        pendingExportSkinId = id
        val safe = id.replace(Regex("[^a-z0-9_-]+"), "-").ifBlank { "skin" }
        exportSkinLauncher.launch("$safe.gupet")
    }

    fun pendingFramesSkin(id: String) {
        pendingFramesSkinId = id
        framesPickerLauncher.launch("*/*")
    }

    fun launchFramePicker(skinId: String, frameName: String) {
        pendingFrameSkinId = skinId
        pendingFrameName = frameName
        framePickerLauncher.launch("image/png")
    }

    /**
     * Красит status/navigation bars под тему из gupet_ui.
     * webView НЕ используется — только window.decorView, поэтому безопасно
     * вызывать в onCreate до инициализации webView.
     */
    fun applySystemBarsTheme() {
        val theme = getSharedPreferences(SETTINGS_UI_PREFS, MODE_PRIVATE)
            .getString(KEY_THEME, "dark") ?: "dark"
        val bg = if (theme == "light") Color.WHITE else Color.parseColor("#0b0b0b")
        window.statusBarColor = bg
        window.navigationBarColor = bg
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = (theme == "light")
            isAppearanceLightNavigationBars = (theme == "light")
        }
    }
}

class PrefsBridge(private val activity: MainActivity) {

    private val uiPrefs
        get() = activity.getSharedPreferences(SETTINGS_UI_PREFS, Context.MODE_PRIVATE)

    private fun android.content.SharedPreferences.Editor.putStringOrRemove(
        key: String,
        value: String?,
    ): android.content.SharedPreferences.Editor {
        if (value.isNullOrEmpty()) remove(key) else putString(key, value)
        return this
    }

    @JavascriptInterface
    fun getString(key: String): String? = uiPrefs.getString(key, null)

    @JavascriptInterface
    fun setString(key: String, value: String) {
        uiPrefs.edit().putString(key, value).apply()
        if (key == KEY_LANG) activity.runOnUiThread { activity.recreate() }
    }

    @JavascriptInterface
    fun setSystemBarsColor(hex: String) {
        // webView не трогаем — только decorView (см. applySystemBarsTheme).
        activity.runOnUiThread { activity.applySystemBarsTheme() }
    }

    @JavascriptInterface
    fun getStatusBarHeightPx(): Int {
        val id = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id != 0) runCatching { activity.resources.getDimensionPixelSize(id) }.getOrDefault(0) else 0
    }

    @JavascriptInterface
    fun setPhrases(json: String) {
        runCatching {
            val arr = JSONArray(json)
            val list = List(arr.length()) { i ->
                val p = arr.getJSONObject(i)
                Phrase(
                    id = p.optString("id", "p$i"),
                    text = p.optString("text", ""),
                    context = p.optString("context", "idle"),
                    weight = p.optInt("weight", 1),
                    enabled = p.optBoolean("enabled", true),
                )
            }.filter { it.text.isNotBlank() }
            PetPhrasesStore.save(activity, list)
        }
        // Сервис сбросит кэш реплик через свой слушатель настроек.
        runCatching {
            activity.startService(
                Intent(activity, OverlayPetService::class.java)
                    .setAction(GUPetContract.ACTION_SETTINGS_CHANGED)
            )
        }
    }

    @JavascriptInterface
    fun getPhrases(): String {
        val arr = JSONArray()
        PetPhrasesStore.load(activity).forEach { p ->
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("text", p.text)
                    .put("context", p.context)
                    .put("weight", p.weight)
                    .put("enabled", p.enabled)
            )
        }
        return arr.toString()
    }

    @JavascriptInterface
    fun getPetSettings(): String {
        val s = PetPrefs.load(activity)
        return JSONObject()
            .put("species", s.species.key)
            .put("customSkinId", s.customSkinId)
            .put("palette", s.palette)
            .put("colorHex", s.colorHex)
            .put("sizeDp", s.sizeDp.toDouble())
            .put("wander", s.wander)
            .put("phrases", s.phrases)
            .put("gravity", s.gravity)
            .put("shake", s.shake)
            .put("haptics", s.haptics)
            .put("edgeVibration", s.edgeVibration)
            .put("poop", s.poop)
            .put("sleepMinutes", s.sleepMinutes)
            .put("reactNotifications", s.reactNotifications)
            .put("dance", s.dance)
            .put("persistPosition", s.persistPosition)
            .put("maxPoops", s.maxPoops)
            .put("poopSize", s.poopSize.toDouble())
            .put("poopInterval", s.poopInterval.toDouble())
            .put("poopColor", s.poopColor)
            .put("poopFadeMs", s.poopFadeMs)
            .put("weight", s.weight.toDouble())
            .put("reactToPoop", s.reactToPoop)
            .put("reactToOtherPets", s.reactToOtherPets)
            .put("wakeOnPetTouch", s.wakeOnPetTouch)
            .put("petsCollide", s.petsCollide)
            .put("gravityStrength", s.gravityStrength.toDouble())
            .put("slideFriction", s.slideFriction.toDouble())
            .put("phraseColor", s.phraseColor)
            .put("phraseFontSize", s.phraseFontSize.toDouble())
            .put("phraseBgColor", s.phraseBgColor)
            .put("phraseBgAlpha", s.phraseBgAlpha.toDouble())
            .put("phraseOutlineColor", s.phraseOutlineColor)
            .put("phraseOutlineWidth", s.phraseOutlineWidth.toDouble())
            .put("phraseDurationMs", s.phraseDurationMs)
            .put("phraseFadeMs", s.phraseFadeMs)
            .put("phraseFadeStyle", s.phraseFadeStyle)
            .toString()
    }

    @JavascriptInterface
    fun setPetSettings(json: String) {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        val prefs = PetPrefs.file(activity)
        prefs.edit()
            .putString(PetPrefs.SPECIES, PetSpecies.fromKey(o.optString("species", PetSpecies.GUPET.key)).key)
            .putInt(PetPrefs.PALETTE, o.optInt("palette", prefs.getInt(PetPrefs.PALETTE, 0)))
            .putStringOrRemove(PetPrefs.COLOR_HEX, o.optString("colorHex", null))
            .putFloat(PetPrefs.SIZE_DP, o.optDouble("sizeDp", prefs.getFloat(PetPrefs.SIZE_DP, 4f).toDouble()).toFloat())
            .putBoolean(PetPrefs.WANDER, o.optBoolean("wander", prefs.getBoolean(PetPrefs.WANDER, true)))
            .putBoolean(PetPrefs.PHRASES, o.optBoolean("phrases", prefs.getBoolean(PetPrefs.PHRASES, true)))
            .putBoolean(PetPrefs.GRAVITY, o.optBoolean("gravity", prefs.getBoolean(PetPrefs.GRAVITY, false)))
            .putBoolean(PetPrefs.SHAKE, o.optBoolean("shake", prefs.getBoolean(PetPrefs.SHAKE, true)))
            .putBoolean(PetPrefs.HAPTICS, o.optBoolean("haptics", prefs.getBoolean(PetPrefs.HAPTICS, true)))
            .putBoolean(PetPrefs.EDGE_VIBRATION, o.optBoolean("edgeVibration", prefs.getBoolean(PetPrefs.EDGE_VIBRATION, true)))
            .putBoolean(PetPrefs.POOP, o.optBoolean("poop", prefs.getBoolean(PetPrefs.POOP, true)))
            .putInt(PetPrefs.SLEEP_MINUTES, o.optInt("sleepMinutes", prefs.getInt(PetPrefs.SLEEP_MINUTES, 3)))
            .putBoolean(PetPrefs.REACT_NOTIFICATIONS, o.optBoolean("reactNotifications", prefs.getBoolean(PetPrefs.REACT_NOTIFICATIONS, false)))
            .putBoolean(PetPrefs.DANCE, o.optBoolean("dance", prefs.getBoolean(PetPrefs.DANCE, true)))
            .putBoolean(PetPrefs.PERSIST_POSITION, o.optBoolean("persistPosition", prefs.getBoolean(PetPrefs.PERSIST_POSITION, true)))
            .putInt(PetPrefs.MAX_POOPS, o.optInt("maxPoops", prefs.getInt(PetPrefs.MAX_POOPS, 3)))
            .putFloat(PetPrefs.POOP_SIZE, o.optDouble("poopSize", prefs.getFloat(PetPrefs.POOP_SIZE, 1f).toDouble()).toFloat())
            .putFloat(PetPrefs.POOP_INTERVAL, o.optDouble("poopInterval", prefs.getFloat(PetPrefs.POOP_INTERVAL, 1f).toDouble()).toFloat())
            .putString(PetPrefs.POOP_COLOR, o.optString("poopColor", prefs.getString(PetPrefs.POOP_COLOR, "#8b5a2b")))
            .putInt(PetPrefs.POOP_FADE_MS, o.optInt("poopFadeMs", prefs.getInt(PetPrefs.POOP_FADE_MS, 500)))
            .putFloat(PetPrefs.WEIGHT, o.optDouble("weight", prefs.getFloat(PetPrefs.WEIGHT, 1f).toDouble()).toFloat())
            .putBoolean(PetPrefs.REACT_TO_POOP, o.optBoolean("reactToPoop", prefs.getBoolean(PetPrefs.REACT_TO_POOP, true)))
            .putBoolean(PetPrefs.REACT_TO_OTHER_PETS, o.optBoolean("reactToOtherPets", prefs.getBoolean(PetPrefs.REACT_TO_OTHER_PETS, true)))
            .putBoolean(PetPrefs.WAKE_ON_PET_TOUCH, o.optBoolean("wakeOnPetTouch", prefs.getBoolean(PetPrefs.WAKE_ON_PET_TOUCH, true)))
            .putBoolean(PetPrefs.PETS_COLLIDE, o.optBoolean("petsCollide", prefs.getBoolean(PetPrefs.PETS_COLLIDE, true)))
            .putFloat(PetPrefs.GRAVITY_STRENGTH, o.optDouble("gravityStrength", prefs.getFloat(PetPrefs.GRAVITY_STRENGTH, 1f).toDouble()).toFloat())
            .putFloat(PetPrefs.SLIDE_FRICTION, o.optDouble("slideFriction", prefs.getFloat(PetPrefs.SLIDE_FRICTION, 1f).toDouble()).toFloat())
            .putString(PetPrefs.PHRASE_COLOR, o.optString("phraseColor", prefs.getString(PetPrefs.PHRASE_COLOR, "#ffffff")))
            .putFloat(PetPrefs.PHRASE_FONT_SIZE, o.optDouble("phraseFontSize", prefs.getFloat(PetPrefs.PHRASE_FONT_SIZE, 14f).toDouble()).toFloat())
            .putString(PetPrefs.PHRASE_BG_COLOR, o.optString("phraseBgColor", prefs.getString(PetPrefs.PHRASE_BG_COLOR, "#0b0b0b")))
            .putFloat(PetPrefs.PHRASE_BG_ALPHA, o.optDouble("phraseBgAlpha", prefs.getFloat(PetPrefs.PHRASE_BG_ALPHA, 0.85f).toDouble()).toFloat())
            .putString(PetPrefs.PHRASE_OUTLINE_COLOR, o.optString("phraseOutlineColor", prefs.getString(PetPrefs.PHRASE_OUTLINE_COLOR, "#ff4d30")))
            .putFloat(PetPrefs.PHRASE_OUTLINE_WIDTH, o.optDouble("phraseOutlineWidth", prefs.getFloat(PetPrefs.PHRASE_OUTLINE_WIDTH, 2f).toDouble()).toFloat())
            .putInt(PetPrefs.PHRASE_DURATION_MS, o.optInt("phraseDurationMs", prefs.getInt(PetPrefs.PHRASE_DURATION_MS, 3000)))
            .putInt(PetPrefs.PHRASE_FADE_MS, o.optInt("phraseFadeMs", prefs.getInt(PetPrefs.PHRASE_FADE_MS, 400)))
            .putString(PetPrefs.PHRASE_FADE_STYLE, o.optString("phraseFadeStyle", prefs.getString(PetPrefs.PHRASE_FADE_STYLE, "fade")))
            .apply()
        // Сигнал сервису: мгновенно перечитать настройки/скин (как setPhrases/applySkin).
        // Один listener на prefs недостаточен: доставка может схлопываться,
        // а этот путь — самый частый (слайдеры/сегменты/цвета).
        runCatching {
            activity.startService(
                Intent(activity, OverlayPetService::class.java)
                    .setAction(GUPetContract.ACTION_SETTINGS_CHANGED)
            )
        }
    }
}

class PetBridge(private val activity: MainActivity) {

    private fun petPrefs() = PetPrefs.file(activity)

    @JavascriptInterface
    fun start() {
        activity.startForegroundService(
            Intent(activity, OverlayPetService::class.java).setAction(GUPetContract.ACTION_START)
        )
    }

    @JavascriptInterface
    fun stop() {
        activity.startService(
            Intent(activity, OverlayPetService::class.java).setAction(GUPetContract.ACTION_STOP)
        )
    }

    @JavascriptInterface
    fun isRunning(): Boolean = petPrefs().getBoolean(PetPrefs.SHOW, false)

    @JavascriptInterface
    fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(activity)

    @JavascriptInterface
    fun requestOverlayPermission() {
        activity.runOnUiThread { activity.launchOverlayPermission() }
    }

    @JavascriptInterface
    fun hasPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(
            activity, android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    @JavascriptInterface
    fun openOverlaySettings() {
        activity.runOnUiThread {
            runCatching {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${activity.packageName}")
                    )
                )
            }
        }
    }

    @JavascriptInterface
    fun openNotificationSettings() {
        activity.runOnUiThread {
            runCatching {
                activity.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            }
        }
    }

    @JavascriptInterface
    fun requestPostNotifications() {
        activity.runOnUiThread {
            if (Build.VERSION.SDK_INT >= 33) {
                runCatching {
                    activity.requestPermissions(
                        arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001
                    )
                }
            }
        }
    }

    @JavascriptInterface
    fun importSkin() {
        activity.runOnUiThread { activity.launchSkinPicker() }
    }

    @JavascriptInterface
    fun fetchRemoteSkins() {
        SkinMarket.fetch(activity, object : SkinMarket.FetchCallback {
            override fun onSuccess(items: List<SkinMarket.RemoteSkin>) {
                val array = JSONArray()
                items.forEach { array.put(it.toJson()) }
                activity.eval(
                    "window.onRemoteSkinsLoaded(" + JSONObject.quote(array.toString()) + ")"
                )
            }

            override fun onError(message: String) {
                activity.eval(
                    "window.onRemoteSkinsError(" + JSONObject.quote(message) + ")"
                )
            }
        })
    }

    @JavascriptInterface
    fun downloadRemoteSkin(url: String, filename: String) {
        SkinMarket.download(activity, url, object : SkinMarket.DownloadCallback {
            override fun onSuccess(result: SkinMarket.DownloadResult) {
                activity.runOnUiThread { activateDownloadedSkin(result.id) }
                activity.eval(
                    "window.onRemoteSkinDownloaded(" +
                        JSONObject.quote(result.toJson().toString()) + ")"
                )
            }

            override fun onError(message: String) {
                activity.eval(
                    "window.onRemoteSkinDownloadError(" + JSONObject.quote(message) + ")"
                )
            }
        })
    }

    @JavascriptInterface
    fun openUrl(url: String) {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return
        activity.runOnUiThread {
            runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }
    }

    private fun activateDownloadedSkin(id: String) {
        val frames = GUPetSkinStore.hasFrames(activity, id)
        val prefs = PetPrefs.file(activity)
        val editor = prefs.edit()
            .putString(PetPrefs.ACTIVE_SKIN, id)
            .putLong(PetPrefs.CUSTOM_SKIN_REVISION, System.currentTimeMillis())
        if (frames) {
            editor.putString(PetPrefs.SPECIES, PetSpecies.SKIN.key)
                .putString(PetPrefs.CUSTOM_SKIN_ID, id)
        } else {
            editor.putString(PetPrefs.SPECIES, PetSpecies.GUPET.key)
                .remove(PetPrefs.CUSTOM_SKIN_ID)
        }
        editor.apply()
        PetPrefs.saveActiveSkinIds(prefs, listOf(id))
        runCatching {
            activity.startService(
                Intent(activity, OverlayPetService::class.java)
                    .setAction(GUPetContract.ACTION_SETTINGS_CHANGED)
            )
        }
    }

    @JavascriptInterface
    fun listSkins(): String {
        val arr = JSONArray()
        GUPetSkinStore.list(activity).forEach { s ->
            arr.put(JSONObject().put("id", s.id).put("name", s.name).put("author", s.author))
        }
        return arr.toString()
    }

    @JavascriptInterface
    fun deleteSkin(id: String) {
        if (GUPetSkinStore.delete(activity, id)) {
            val prefs = petPrefs()
            if (prefs.getString(PetPrefs.CUSTOM_SKIN_ID, null) == id) {
                prefs.edit().remove(PetPrefs.CUSTOM_SKIN_ID).apply()
            }
        }
    }

    private fun needPetFirst(): Boolean {
        if (petPrefs().getBoolean(PetPrefs.SHOW, false)) return false
        activity.eval("window.GUPetUI.alert('Питомец выключен','Сначала включи питомца')")
        return true
    }

    @JavascriptInterface
    fun callPoop() {
        if (needPetFirst()) return
        activity.startForegroundService(
            Intent(activity, OverlayPetService::class.java).setAction(GUPetContract.ACTION_POOP_NOW)
        )
    }

    @JavascriptInterface
    fun cleanPoops() {
        if (needPetFirst()) return
        activity.startForegroundService(
            Intent(activity, OverlayPetService::class.java).setAction(GUPetContract.ACTION_CLEAN_POOPS)
        )
    }

    @JavascriptInterface
    fun selectSkin(id: String) {
        // Применить скин: запомнить id + переключить вид на SKIN.
        // Сервис подхватит через слушатель PetPrefs.
        val clean = id.ifBlank { null }
        PetPrefs.file(activity).edit()
            .putString(PetPrefs.CUSTOM_SKIN_ID, clean)
            .putString(PetPrefs.SPECIES, PetSpecies.SKIN.key)
            .putString(PetPrefs.ACTIVE_SKIN, clean)
            .apply()
        if (clean != null) PetPrefs.saveActiveSkinIds(PetPrefs.file(activity), listOf(clean))
    }

    @JavascriptInterface
    fun getActiveSkinIds(): String {
        val prefs = PetPrefs.file(activity)
        return org.json.JSONArray(PetPrefs.loadActiveSkinIds(prefs)).toString()
    }

    @JavascriptInterface
    fun setActiveSkinIds(json: String) {
        val prefs = PetPrefs.file(activity)
        val valid = SkinStore.list(activity).map { it.id }.toSet()
        val ids = runCatching {
            val arr = org.json.JSONArray(json)
            List(arr.length()) { i -> arr.optString(i, "").trim() }
        }.getOrDefault(emptyList())
            .filter { it.isNotBlank() && it in valid }.distinct()
            .ifEmpty { listOf(SkinStore.BUILTIN_ID) }
        PetPrefs.saveActiveSkinIds(prefs, ids)
        // Legacy: вид/скин = первый выбранный; сервис мгновенно перерисует.
        val first = ids.first()
        val frames = if (first == SkinStore.BUILTIN_ID) false
            else GUPetSkinStore.hasFrames(activity, first)
        val e = prefs.edit()
            .putString(
                PetPrefs.SPECIES,
                if (first == SkinStore.BUILTIN_ID || !frames) PetSpecies.GUPET.key
                else PetSpecies.SKIN.key
            )
            .putLong(PetPrefs.CUSTOM_SKIN_REVISION, System.currentTimeMillis())
        if (frames) e.putString(PetPrefs.CUSTOM_SKIN_ID, first)
        else e.remove(PetPrefs.CUSTOM_SKIN_ID)
        e.apply()
        runCatching {
            activity.startService(
                Intent(activity, OverlayPetService::class.java)
                    .setAction(GUPetContract.ACTION_SETTINGS_CHANGED)
            )
        }
    }

    @JavascriptInterface
    fun listFullSkins(): String {
        // При первом запуске active не задан — считаем выбранным встроенный.
        val prefs = PetPrefs.file(activity)
        val ids = PetPrefs.loadActiveSkinIds(prefs)
        val active = prefs.getString(PetPrefs.ACTIVE_SKIN, null) ?: ids.first()
        val arr = JSONArray()
        SkinStore.list(activity).forEach { s ->
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("name", s.name)
                    .put("author", s.author)
                    .put("builtin", s.builtin)
                    .put("hasSettings", s.hasSettings)
                    .put("hasFrames", s.hasFrames)
            )
        }
        return JSONObject().put("active", active).put("activeIds", org.json.JSONArray(ids)).put("skins", arr).toString()
    }

    @JavascriptInterface
    fun getSkin(id: String): String {
        if (id == SkinStore.BUILTIN_ID) {
            return JSONObject()
                .put("id", id)
                .put("name", "Стандартный GUPet")
                .put("author", "GUTeam")
                .put("builtin", true)
                .put("hasFrames", false)
                .put("settings", SkinStore.snapshotCurrent(activity))
                .toString()
        }
        val info = GUPetSkinStore.list(activity).firstOrNull { it.id == id } ?: return "{}"
        return JSONObject()
            .put("id", info.id)
            .put("name", info.name)
            .put("author", info.author)
            .put("builtin", false)
            .put("hasFrames", GUPetSkinStore.hasFrames(activity, id))
            .put("settings", GUPetSkinStore.readSettingsJson(activity, id) ?: JSONObject.NULL)
            .toString()
    }

    @JavascriptInterface
    fun createSkin(name: String, author: String): String {
        return try {
            val base = name.trim().lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .take(30)
                .ifBlank { "skin" }
            var id = base
            var n = 2
            while (GUPetSkinStore.skinFolder(activity, id) != null) {
                id = "$base-$n"
                n++
            }
            val info = GUPetSkinStore.createSettingsSkin(
                activity, id, name, author, SkinStore.snapshotCurrent(activity)
            )
            PetPrefs.file(activity).edit().putString(PetPrefs.ACTIVE_SKIN, info.id).apply()
            val prefs = PetPrefs.file(activity)
            val cur = PetPrefs.loadActiveSkinIds(prefs).toMutableList()
            if (!cur.contains(info.id)) cur.add(info.id)
            PetPrefs.saveActiveSkinIds(prefs, cur)
            JSONObject().put("ok", true).put("id", info.id).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "").toString()
        }
    }

    @JavascriptInterface
    fun saveSkinMeta(id: String, name: String, author: String): Boolean {
        if (id == SkinStore.BUILTIN_ID) return false
        return GUPetSkinStore.writeMeta(activity, id, name, author)
    }

    @JavascriptInterface
    fun snapshotSkin(id: String): Boolean {
        // Снапшот только реплик: настройки живут в файле скина (per-instance),
        // полный снапшот глобальных prefs сюда больше не пишется.
        if (id == SkinStore.BUILTIN_ID) return false
        return runCatching {
            val current = GUPetSkinStore.readSettingsJson(activity, id) ?: JSONObject()
            val arr = JSONArray()
            PetPhrasesStore.load(activity).forEach { p ->
                arr.put(
                    JSONObject()
                        .put("id", p.id)
                        .put("text", p.text)
                        .put("context", p.context)
                        .put("weight", p.weight)
                        .put("enabled", p.enabled)
                )
            }
            current.put("phrases", arr)
            GUPetSkinStore.writeSettingsJson(activity, id, current)
        }.getOrDefault(false)
    }

    /** Настройки скина из .gupet (для редактора). builtin → снапшот текущих. */
    @JavascriptInterface
    fun getSkinSettings(id: String): String {
        if (id == SkinStore.BUILTIN_ID) return SkinStore.snapshotCurrent(activity).toString()
        return (GUPetSkinStore.readSettingsJson(activity, id) ?: JSONObject()).toString()
    }

    /** Записать настройки в файл скина (редактор). Сервис подхватит через listener. */
    @JavascriptInterface
    fun setSkinSettings(id: String, json: String): Boolean {
        if (id == SkinStore.BUILTIN_ID) return false
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return false
        val ok = GUPetSkinStore.writeSettingsJson(activity, id, o)
        if (ok) {
            PetPrefs.file(activity).edit()
                .putLong(PetPrefs.CUSTOM_SKIN_REVISION, System.currentTimeMillis())
                .apply()
            runCatching {
                activity.startService(
                    Intent(activity, OverlayPetService::class.java)
                        .setAction(GUPetContract.ACTION_SETTINGS_CHANGED)
                )
            }
        }
        return ok
    }

    @JavascriptInterface
    fun deleteFullSkin(id: String): Boolean {
        if (id == SkinStore.BUILTIN_ID) return false
        val ok = GUPetSkinStore.delete(activity, id)
        if (ok) {
            val prefs = PetPrefs.file(activity)
            if (prefs.getString(PetPrefs.CUSTOM_SKIN_ID, null) == id) {
                prefs.edit().remove(PetPrefs.CUSTOM_SKIN_ID).apply()
            }
            if (prefs.getString(PetPrefs.ACTIVE_SKIN, null) == id) {
                prefs.edit().remove(PetPrefs.ACTIVE_SKIN).apply()
            }
            val rest = PetPrefs.loadActiveSkinIds(prefs).filter { it != id }
            PetPrefs.saveActiveSkinIds(prefs, rest)
            prefs.edit()
                .putLong(PetPrefs.CUSTOM_SKIN_REVISION, System.currentTimeMillis())
                .apply()
            runCatching {
                activity.startService(
                    Intent(activity, OverlayPetService::class.java)
                        .setAction(GUPetContract.ACTION_SETTINGS_CHANGED)
                )
            }
        }
        return ok
    }

    @JavascriptInterface
    fun applySkin(id: String): Boolean {
        val ok = runCatching { SkinStore.applySkin(activity, id) }.getOrDefault(false)
        if (ok) {
            // Гарантированный сигнал смены скина: revision всегда меняется,
            // listener перезагружает bitmap даже при PNG→PNG.
            PetPrefs.file(activity).edit()
                .putLong(PetPrefs.CUSTOM_SKIN_REVISION, System.currentTimeMillis())
                .apply()
            runCatching {
                activity.startService(
                    Intent(activity, OverlayPetService::class.java)
                        .setAction(GUPetContract.ACTION_SETTINGS_CHANGED)
                )
            }
        }
        return ok
    }

    @JavascriptInterface
    fun exportSkin(id: String) {
        activity.runOnUiThread {
            activity.pendingExportSkin(id)
        }
    }

    @JavascriptInterface
    fun getSkinPreviewUrl(id: String): String {
        if (id == SkinStore.BUILTIN_ID) return ""
        val folder = GUPetSkinStore.skinFolder(activity, id) ?: return ""
        val idle = java.io.File(folder, "idle.png")
        return if (idle.isFile) "file://" + idle.absolutePath else ""
    }

    @JavascriptInterface
    fun mergeSkinFrames(id: String) {
        activity.runOnUiThread {
            activity.pendingFramesSkin(id)
        }
    }

    @JavascriptInterface
    fun getFrameInfo(skinId: String, frameName: String): String {
        return GUPetSkinStore.frameInfo(activity, skinId, frameName).toString()
    }

    @JavascriptInterface
    fun pickFrame(skinId: String, frameName: String) {
        activity.runOnUiThread {
            activity.launchFramePicker(skinId, frameName)
        }
    }

    @JavascriptInterface
    fun deleteFrame(skinId: String, frameName: String) {
        activity.runOnUiThread {
            GUPetSkinStore.deleteFramePng(activity, skinId, frameName)
            PetPrefs.file(activity).edit()
                .putLong(PetPrefs.CUSTOM_SKIN_REVISION, System.currentTimeMillis())
                .apply()
            activity.eval("window.onFrameDeleted && window.onFrameDeleted()")
        }
    }

    @JavascriptInterface
    fun duplicateSkin(skinId: String) {
        activity.runOnUiThread {
            val newId = SkinStore.duplicate(activity, skinId)
            activity.eval("window.onSkinDuplicated && window.onSkinDuplicated('${newId ?: ""}')")
        }
    }

    @JavascriptInterface
    fun testPhrase(text: String) {        if (text.isBlank()) return
        if (!PetPrefs.file(activity).getBoolean(PetPrefs.SHOW, false)) return
        runCatching {
            activity.sendBroadcast(
                Intent(GUPetContract.ACTION_REACTION)
                    .setPackage(activity.packageName)
                    .putExtra(GUPetContract.EXTRA_MOOD, "say:$text")
            )
        }
    }

    @JavascriptInterface
    fun hasNotificationAccess(): Boolean {
        val enabled = Settings.Secure.getString(
            activity.contentResolver, "enabled_notification_listeners"
        ).orEmpty()
        return enabled.split(":").any { it.contains(activity.packageName) }
    }

    @JavascriptInterface
    fun requestNotificationAccess() {
        activity.runOnUiThread {
            runCatching { activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }
    }
}
