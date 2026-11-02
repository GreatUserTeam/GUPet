package net.guteam.gupet

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlin.collections.ArrayDeque
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class OverlayPetService : Service(), SensorEventListener, SharedPreferences.OnSharedPreferenceChangeListener {
    private enum class State { IDLE, WALK, PRESS, DRAG, PET, FLY, SLEEP, HANG, PEEK }
    private data class TouchPoint(val timeMs: Long, val x: Float, val y: Float)
    private data class TouchTrack(
        val downX: Float,
        val downY: Float,
        val downAt: Long,
        val offsetX: Float,
        val offsetY: Float,
        var anchorX: Float,
        var direction: Int = 0,
        var velocityX: Float = 0f,
        val samples: MutableList<TouchPoint> = mutableListOf(),
    )
    private data class PoopWindow(val view: View, val params: WindowManager.LayoutParams, val bornAt: Long)

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var windowManager: WindowManager
    private var settings = PetSettings(
        show = false,
        species = PetSpecies.GUPET,
        palette = 0,
        colorHex = null,
        sizeDp = 4f,
        wander = true,
        phrases = true,
        gravity = false,
        shake = true,
        haptics = true,
        edgeVibration = true,
        poop = true,
        maxPoops = 3,
        poopSize = 1f,
        poopInterval = 1f,
        poopColor = "#8b5a2b",
        poopFadeMs = 500,
        weight = 1f,
        reactToPoop = true,
        reactToOtherPets = true,
        wakeOnPetTouch = true,
        hideOnAppOpen = false,
        canHang = true,
        sleepMinutes = 3,
        reactNotifications = false,
        dance = true,
        customSkinId = null,
        customSkinRevision = 0L,
        activeSkinIds = listOf("builtin"),
        petsCollide = true,
        persistPosition = true,
        gravityStrength = 1f,
        slideFriction = 1f,
        phraseColor = "#ffffff",
        phraseFontSize = 14f,
        phraseBgColor = "#0b0b0b",
        phraseBgAlpha = 0.85f,
        phraseOutlineColor = "#ff4d30",
        phraseOutlineWidth = 2f,
        phraseDurationMs = 3000,
        phraseFadeMs = 400,
        phraseFadeStyle = "fade",
    )

    /** Один питомец на экране: свой View, скин и всё состояние физики/поведения. */
    private class PetInstance(
        val listId: Int,
        var skinId: String,
    ) {
        /** Снимок настроек этого питомца (из его скина). Глобальное — только в service.settings. */
        lateinit var settings: PetSettings
        var view: PetArtView? = null
        var params: WindowManager.LayoutParams? = null
        var winW = 0
        var winH = 0
        var unit = 0f
        var lastPoopReactAt = 0L
        var lastPetReactAt = 0L
        /** Скрыт за краем до первого тапа/power (hideOnAppOpen): PEEK без авто-выхода. */
        var hiddenUntilTap = false
        var posX = 0f
        var posY = 0f
        var lastLayoutX = Int.MIN_VALUE
        var lastLayoutY = Int.MIN_VALUE
        var positionReady = false
        var state = State.IDLE
        var touch: TouchTrack? = null
        var lastInteractionAt = 0L
        var nextActionAt = 0L
        var nextBlinkAt = 0L
        var blinkUntil = 0L
        var nextHeartAt = 0L
        var nextZAt = 0L
        var nextNoteAt = 0L
        var nextPoopAt = 0L
        var nextPosSaveAt = 0L
        var nextPeekAt = 0L
        var peekEndAt = 0L
        var peekPhaseUntil = 0L
        var peekSide = 0
        var peekOut = false
        var peekMoving = false
        var peekPending = 0
        var waveUntil = 0L
        var expression = PetExpression.OPEN
        var expressionUntil = 0L
        var squashUntil = 0L
        var mouthOpenUntil = 0L
        var hopActive = false
        var hopOffset = 0f
        var hopVelocity = 0f
        var hopCooldownUntil = 0L
        var hopBlockedUntil = 0L
        val hopTimes = ArrayDeque<Long>()
        var velocityX = 0f
        var velocityY = 0f
        var slideVelocity = 0f
        var walkTarget = 0f
        var look = 0
        var hangStartedAt = 0L
        var hangUntil = 0L
        var shakeDizzy = false
        var lastTapAt = 0L
        val recentTaps = mutableListOf<Long>()
        var nextExternalReactionAt = 0L
        var lastPhrase: String? = null
        var idlePose = PetPose.IDLE
        var idlePoseStartedAt = 0L
        var idlePoseUntil = 0L
        var bubbleUntil = 0L
        var bubbleVisible = false
        var bubbleFadeIn = false
        var bubbleView: TextView? = null
        var bubbleParams: WindowManager.LayoutParams? = null
        var loadedSkinKey: String? = null
        val particles = mutableListOf<PetParticle>()
    }

    private val pets = mutableListOf<PetInstance>()
    private var nextPetId = 1
    private fun firstPet(): PetInstance? = pets.firstOrNull()
    private val poops = mutableListOf<PoopWindow>()

    /** Ключ загруженного bitmap-скина: id@revision. Смена ключа = обязательная перезагрузка. */
    private fun skinKeyOf(skinId: String?, revision: Long): String? =
        if (skinId != null && skinId != SkinStore.BUILTIN_ID) "$skinId@$revision" else null
    private var poopBitmap: Bitmap? = null
    private var poopBitmapKey: String? = null

    /** PNG кучки скина или null (тогда процедурная из PetArtwork). */
    private fun currentPoopBitmap(skinId: String?): Bitmap? {
        if (skinId == null || skinId == SkinStore.BUILTIN_ID) {
            poopBitmap = null
            poopBitmapKey = null
            return null
        }
        val id = skinId
        val folder = GUPetSkinStore.skinFolder(this, id)
        val file = if (folder != null) java.io.File(folder, "poop.png") else null
        val key = "$id@${if (file != null && file.isFile) file.lastModified() else 0}"
        if (key == poopBitmapKey) return poopBitmap
        poopBitmapKey = key
        poopBitmap = if (file != null && file.isFile) {
            runCatching {
                val bytes = file.readBytes()
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        } else null
        return poopBitmap
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!running || pets.isEmpty()) return
            step()
            if (running && pets.isNotEmpty()) handler.postDelayed(this, nextFrameDelay())
        }
    }

    private var receiverRegistered = false
    private val privateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                GUPetContract.ACTION_REACTION -> {
                    val target = pets.firstOrNull() ?: return
                    reactToNotification(target, intent.getStringExtra(GUPetContract.EXTRA_MOOD) ?: "mail")
                }
                GUPetContract.ACTION_MEDIA -> setMediaPlaying(intent.getBooleanExtra(GUPetContract.EXTRA_PLAYING, false))
            }
        }
    }

    private var running = false
    private var configRegistered = false
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var sensorRegistered = false
    private var hasLastAccel = false
    private var lastAccelX = 0f
    private var lastAccelY = 0f
    private var lastAccelZ = 0f
    private val shakeHits = mutableListOf<Long>()
    private var shakePending = 0f
    private var tilt = 0f
    private var rotation = Surface.ROTATION_0
    private var mediaPlaying = false
    private var mediaSessionManager: MediaSessionManager? = null
    private var mediaSessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var mediaListenerRegistered = false
    private val activeMediaControllers = mutableListOf<MediaController>()
    private val mediaControllerCallbacks = mutableMapOf<MediaController, MediaController.Callback>()
    private var mediaRetryCount = 0

    private var screenWidth = 0
    private var screenHeight = 0
    private var safeTop = 0
    private var safeBottom = 0
    private var minX = 0f
    private var maxX = 0f
    private var minY = 0f
    private var maxY = 0f
    private var nextBoundsCheck = 0L
    private var windowWidth = 0
    private var windowHeight = 0
    private var unitPx = 0f

    private var lastTickAt = 0L
    private var phraseCache: List<Phrase>? = null

    override fun onCreate() {
        super.onCreate()
        prefs = PetPrefs.file(this)
        settings = PetPrefs.load(prefs)
        prefs.registerOnSharedPreferenceChangeListener(this)
        configRegistered = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        registerPrivateReceiver()
        lastTickAt = SystemClock.elapsedRealtime()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: GUPetContract.ACTION_START
        if (action == GUPetContract.ACTION_STOP) {
            prefs.edit().putBoolean(PetPrefs.SHOW, false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            prefs.edit().putBoolean(PetPrefs.SHOW, false).apply()
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        settings = PetPrefs.load(prefs)
        if (!settings.show) prefs.edit().putBoolean(PetPrefs.SHOW, true).apply()
        val hadPets = pets.isNotEmpty()
        syncPetsFromSettings()
        if (pets.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Power при живых питомцах — показать скрытых (hideOnAppOpen).
        // При свежем спавне (hadPets=false) скрытые остаются скрытыми.
        if (action == GUPetContract.ACTION_START && hadPets) {
            for (p in pets) {
                if (p.hiddenUntilTap) {
                    p.hiddenUntilTap = false
                    endPeek(p, SystemClock.elapsedRealtime())
                }
            }
        }

        when (action) {
            GUPetContract.ACTION_POOP_NOW -> pets.toList().forEach { poopNow(it, manual = true) }
            GUPetContract.ACTION_CLEAN_POOPS -> cleanAllPoops(animated = true)
            GUPetContract.ACTION_RECENTER -> recenter()
            GUPetContract.ACTION_SETTINGS_CHANGED -> applySettings(PetPrefs.load(prefs))
        }
        registerSensorIfNeeded()
        updateMediaMonitor()
        scheduleTick(0L)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(tick)
        if (configRegistered) {
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            configRegistered = false
        }
        unregisterPrivateReceiver()
        handler.removeCallbacks(mediaRetry)
        unregisterMediaMonitor()
        unregisterSensor()
        pets.firstOrNull()?.let { saveLastPosition(it) }
        cleanAllPoops(animated = false, count = false)
        removeAllPets()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (!running) return
        handler.post {
            val old = settings
            val fresh = PetPrefs.load(prefs)
            if (!fresh.show) {
                stopSelf()
                return@post
            }
            applySettings(fresh, old)
        }
    }

    private fun applySettings(fresh: PetSettings, old: PetSettings = settings) {
        settings = fresh
        phraseCache = null
        if (!fresh.show) {
            stopSelf()
            return
        }
        // Выбор скинов мог измениться — синхронизировать список питомцев.
        syncPetsFromSettings()
        // Настройки каждого инстанса — из файла его скина (1.2).
        for (p in pets) p.settings = loadPetSettings(p.skinId)
        resizePetWindow()
        for (p in pets) {
            val view = p.view ?: continue
            val ps = p.settings
            view.species = if (p.skinId != SkinStore.BUILTIN_ID && GUPetSkinStore.hasFrames(this, p.skinId)) PetSpecies.SKIN else PetSpecies.GUPET
            view.paletteIndex = ps.palette
            view.customPalette = ps.colorHex?.let { petPaletteFromColor(it) }
            val wantSkinKey = skinKeyOf(p.skinId, fresh.customSkinRevision)
            if (wantSkinKey != p.loadedSkinKey) {
                // Скин рисуется только в режиме species == SKIN (см. attachPet).
                // Ключ = id@revision: смена PNG→PNG всегда перезагружает bitmap
                // (revision бампается в applySkin/загрузке/удалении кадров),
                // восстановление после пропущенной загрузки — тоже.
                p.loadedSkinKey = wantSkinKey
                view.customSkin = if (wantSkinKey != null) {
                    GUPetSkinStore.load(this, p.skinId)
                } else {
                    null
                }
                view.invalidate()
            }
            if (!ps.phrases) hideBubble(p)
            if (p.state == State.SLEEP && (ps.sleepMinutes == 0 || SystemClock.elapsedRealtime() - p.lastInteractionAt < ps.sleepMinutes * 60_000L)) {
                p.state = State.IDLE
                setExpression(p, PetExpression.SURPRISED, 750L)
            }
            if (!ps.gravity) p.slideVelocity = 0f
        }
        // Цвет новых кучек задаёт спавнящий питомец (poopNow); существующие не трогаем.
        registerSensorIfNeeded()
        updateMediaMonitor()
        scheduleTick(0L)
    }

    private fun registerPrivateReceiver() {
        val filter = IntentFilter().apply {
            addAction(GUPetContract.ACTION_REACTION)
            addAction(GUPetContract.ACTION_MEDIA)
        }
        ContextCompat.registerReceiver(this, privateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    private fun unregisterPrivateReceiver() {
        if (!receiverRegistered) return
        try { unregisterReceiver(privateReceiver) } catch (_: Exception) { }
        receiverRegistered = false
    }

    /** Создать питомца для скина и добавить на экран. */
    private fun spawnPet(skinId: String, index: Int): PetInstance? {
        val p = PetInstance(nextPetId++, skinId)
        // settings ОБЯЗАН быть присвоен до resetPetRuntime: тот через
        // nextPoopDelay читает p.settings (lateinit) — иначе краш.
        p.settings = loadPetSettings(skinId)
        resetPetRuntime(p)
        if (!attachPet(p, index)) return null
        pets.add(p)
        val total = prefs.getInt(PetPrefs.PETS_COUNT, 0) + 1
        prefs.edit().putInt(PetPrefs.PETS_COUNT, total).apply()
        return p
    }

    /** Синхронизировать список питомцев с activeSkinIds: удалить лишних, создать новых.
     * Атомарно: все View добавляются скрытыми (alpha 0), затем ОДИН общий
     * fade-in 200мс для всех новых сразу. Удаляемые прячутся и снимаются тут же. */
    private fun syncPetsFromSettings() {
        val ids = PetPrefs.loadActiveSkinIds(prefs).ifEmpty { listOf(SkinStore.BUILTIN_ID) }
        var changed = false
        val it = pets.iterator()
        while (it.hasNext()) {
            val p = it.next()
            if (p.skinId !in ids) {
                removePet(p)
                it.remove()
                changed = true
            }
        }
        val added = mutableListOf<PetInstance>()
        ids.forEachIndexed { index, id ->
            if (pets.none { it.skinId == id }) {
                val p = spawnPet(id, index)
                if (p != null) {
                    added.add(p)
                    changed = true
                }
            }
        }
        if (added.isNotEmpty()) {
            for (p in added) {
                try {
                    p.view?.animate()?.alpha(1f)?.setDuration(200L)?.start()
                } catch (_: Exception) { p.view?.alpha = 1f }
            }
        }
        running = pets.isNotEmpty()
        if (changed) scheduleTick(0L)
    }

    private fun removePet(p: PetInstance) {
        hideBubble(p, animated = false)
        // Сначала скрыть, потом снять: мгновенное исчезновение без анимаций.
        try { p.view?.animate()?.cancel() } catch (_: Exception) { }
        p.view?.alpha = 0f
        removeOverlay(p.view)
        removeOverlay(p.bubbleView)
        p.view = null
        p.params = null
        p.bubbleView = null
        p.bubbleParams = null
    }

    private fun removeAllPets() {
        pets.toList().forEach { removePet(it) }
        pets.clear()
        running = false
    }

    /** Настройки инстанса: из файла его скина; builtin/без settings — глобальные PetPrefs (1.4). */
    private fun loadPetSettings(skinId: String): PetSettings {
        if (skinId == SkinStore.BUILTIN_ID) return PetPrefs.load(prefs)
        val json = GUPetSkinStore.readSettingsJson(this, skinId) ?: return PetPrefs.load(prefs)
        return PetPrefs.settingsFromJson(prefs, json)
    }

    /** Пересчитать размер окна инстанса из его sizeDp. */
    private fun updatePetWindowSize(p: PetInstance) {
        p.unit = dpFloat(p.settings.sizeDp)
        p.winW = (p.unit * 18f).toInt().coerceAtLeast(dp(60f))
        p.winH = (p.unit * 17f).toInt().coerceAtLeast(dp(52f))
    }

    private fun attachPet(p: PetInstance, index: Int): Boolean {
        if (p.view != null) return true
        p.settings = loadPetSettings(p.skinId)
        updatePetWindowSize(p)
        windowWidth = max(windowWidth, p.winW)
        windowHeight = max(windowHeight, p.winH)
        refreshBounds(force = true)
        val isBuiltin = p.skinId == SkinStore.BUILTIN_ID
        val hasFrames = !isBuiltin && GUPetSkinStore.hasFrames(this, p.skinId)
        val view = PetArtView(this).apply {
            species = if (hasFrames) PetSpecies.SKIN else PetSpecies.GUPET
            paletteIndex = p.settings.palette
            customPalette = p.settings.colorHex?.let { petPaletteFromColor(it) }
            customSkin = if (hasFrames) {
                GUPetSkinStore.load(this@OverlayPetService, p.skinId)
            } else {
                null
            }
            contentDescription = "Питомец GUPet"
            setOnClickListener { onTap(p, SystemClock.elapsedRealtime()) }
            setOnTouchListener { touchedView, event ->
                val isShortTap = event.actionMasked == MotionEvent.ACTION_UP && p.state == State.PRESS &&
                    SystemClock.elapsedRealtime() - (p.touch?.downAt ?: SystemClock.elapsedRealtime()) < LONG_PRESS_MS
                val handled = onPetTouch(p, event)
                if (isShortTap) touchedView.performClick()
                handled
            }
        }
        val params = overlayParams(p.winW, p.winH, touchable = true)
        try {
            windowManager.addView(view, params)
        } catch (_: Exception) {
            return false
        }
        p.view = view
        p.params = params
        // Появление — общим fade-in из syncPetsFromSettings (атомарно для всех).
        view.alpha = 0f
        p.loadedSkinKey = skinKeyOf(p.skinId, settings.customSkinRevision)
        if (!p.positionReady) restorePosition(p, index)
        updatePetLayout(p, force = true)
        val now = SystemClock.elapsedRealtime()
        // hideOnAppOpen: стартует скрытым за краем (PEEK без авто-выхода),
        // показывается тапом по выглядывающему краю или кнопкой power.
        if (p.settings.hideOnAppOpen) {
            val side = if (p.posX - minX < maxX - p.posX) -1 else 1
            startPeek(p, now, side)
            p.peekEndAt = Long.MAX_VALUE
            p.hiddenUntilTap = true
        }
        // Приветственная реплика — только у первого, чтобы не спамить пузырями.
        if (index == 0 && settings.phrases) showBubble(p, "hello")
        p.lastInteractionAt = now
        p.nextActionAt = now + 3_000L
        p.nextPoopAt = now + nextPoopDelay(p) + index * 20_000L
        registerSensorIfNeeded()
        return true
    }

    private fun overlayParams(width: Int, height: Int, touchable: Boolean): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(width, height, type, flags, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            this.width = width
            this.height = height
            packageName = this@OverlayPetService.packageName
            title = "GUPet"
        }
    }

    private fun refreshBounds(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now < nextBoundsCheck) return
        nextBoundsCheck = now + 1_000L
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val changed = force || metrics.widthPixels != screenWidth || metrics.heightPixels != screenHeight
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        safeTop = max(systemDimension("status_bar_height"), dp(20f))
        safeBottom = systemDimension("navigation_bar_height")
        minX = 0f
        maxX = max(0, screenWidth - windowWidth).toFloat()
        minY = safeTop.toFloat()
        maxY = max(minY, (screenHeight - safeBottom - windowHeight).toFloat())
        try {
            rotation = windowManager.defaultDisplay.rotation
        } catch (_: Exception) { }
        if (changed) {
            pets.forEachIndexed { index, p ->
                if (!p.positionReady) restorePosition(p, index)
                else if (p.state != State.PEEK) {
                    p.posX = p.posX.coerceIn(minX, maxX)
                    p.posY = p.posY.coerceIn(minY, maxY)
                }
                updatePetLayout(p, force = true)
            }
        }
    }

    private fun systemDimension(name: String): Int {
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id != 0) try { resources.getDimensionPixelSize(id) } catch (_: Exception) { 0 } else 0
    }

    private fun restorePosition(p: PetInstance, index: Int) {
        // Первый питомец восстанавливает сохранённую позицию, остальные — каскадом рядом.
        if (index == 0) {
            val fxNew = prefs.getFloat(PetPrefs.LAST_POS_X, -1f)
            val fyNew = prefs.getFloat(PetPrefs.LAST_POS_Y, -1f)
            if (settings.persistPosition && fxNew >= 0f && fyNew >= 0f) {
                p.posX = minX + (maxX - minX) * fxNew.coerceIn(0f, 1f)
                p.posY = minY + (maxY - minY) * fyNew.coerceIn(0f, 1f)
                p.positionReady = true
                return
            }
        }
        val fx = (prefs.getInt(PetPrefs.POS_X, 780) / 1_000f).coerceIn(0f, 1f)
        val fy = (prefs.getInt(PetPrefs.POS_Y, 720) / 1_000f).coerceIn(0f, 1f)
        val cascade = (index % 4) * p.winW * 0.55f
        p.posX = (minX + (maxX - minX) * fx + cascade).coerceIn(minX, maxX)
        p.posY = (minY + (maxY - minY) * fy).coerceIn(minY, maxY)
        p.positionReady = true
    }

    /**
     * Сохранение позиции для рестарта (доли 0..1, устойчивы к смене экрана).
     * Пишется в onDestroy и периодически в step(). Персистим первого питомца.
     */
    private fun saveLastPosition(p: PetInstance) {
        if (!settings.persistPosition) return
        val fx = if (maxX > minX) ((p.posX - minX) / (maxX - minX)).coerceIn(0f, 1f) else 0.5f
        val fy = if (maxY > minY) ((p.posY - minY) / (maxY - minY)).coerceIn(0f, 1f) else 0.6f
        prefs.edit()
            .putFloat(PetPrefs.LAST_POS_X, fx)
            .putFloat(PetPrefs.LAST_POS_Y, fy)
            .apply()
    }

    private fun savePosition(p: PetInstance) {
        // Персистим позицию первого питомца, чтобы рестарты были стабильны.
        if (pets.firstOrNull() !== p) return
        val fx = if (maxX > minX) ((p.posX - minX) / (maxX - minX)).coerceIn(0f, 1f) else 0.5f
        val fy = if (maxY > minY) ((p.posY - minY) / (maxY - minY)).coerceIn(0f, 1f) else 0.6f
        prefs.edit()
            .putInt(PetPrefs.POS_X, (fx * 1_000).toInt())
            .putInt(PetPrefs.POS_Y, (fy * 1_000).toInt())
            .apply()
    }

    private fun resizePetWindow() {
        val oldWidth = windowWidth
        for (p in pets) updatePetWindowSize(p)
        windowWidth = (pets.maxOfOrNull { it.winW } ?: windowWidth).coerceAtLeast(dp(60f))
        windowHeight = (pets.maxOfOrNull { it.winH } ?: windowHeight).coerceAtLeast(dp(52f))
        refreshBounds(force = true)
        for (p in pets) {
            val view = p.view ?: continue
            val params = p.params ?: continue
            val center = p.posX + oldWidth / 2f
            params.width = p.winW
            params.height = p.winH
            view.layoutParams = params
            p.posX = (center - p.winW / 2f).coerceIn(minX, maxX)
            p.posY = p.posY.coerceIn(minY, maxY)
            updatePetLayout(p, force = true)
        }
    }

    private fun updatePetLayout(p: PetInstance, force: Boolean = false) {
        val view = p.view ?: return
        val params = p.params ?: return
        val nextX = p.posX.toInt()
        val nextY = p.posY.toInt()
        if (!force && nextX == p.lastLayoutX && nextY == p.lastLayoutY) return
        params.x = nextX
        params.y = nextY
        try {
            windowManager.updateViewLayout(view, params)
            p.lastLayoutX = nextX
            p.lastLayoutY = nextY
        } catch (_: Exception) { }
    }

    private fun removeOverlay(view: View?) {
        if (view == null) return
        try { view.animate().cancel() } catch (_: Exception) { }
        try { windowManager.removeViewImmediate(view) } catch (_: Exception) { }
    }

    private fun setPosition(p: PetInstance, x: Float, y: Float, allowOffscreenX: Boolean = false) {
        p.posX = if (allowOffscreenX) x else x.coerceIn(minX, maxX)
        p.posY = y.coerceIn(minY - dp(3f), maxY)
        updatePetLayout(p)
    }

    private fun scheduleTick(delayMs: Long) {
        if (!running || pets.isEmpty()) return
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, delayMs.coerceAtLeast(0L))
    }

    private fun petIsMoving(p: PetInstance, now: Long): Boolean {
        return p.state == State.PRESS || p.state == State.DRAG || p.state == State.PET || p.state == State.FLY ||
            p.state == State.WALK || p.state == State.HANG || p.state == State.PEEK || p.hopActive || now < p.squashUntil ||
            p.particles.isNotEmpty()
    }

    private fun nextFrameDelay(): Long {
        val now = SystemClock.elapsedRealtime()
        val anyMoving = pets.any { petIsMoving(it, now) }
        val anySleep = pets.any { it.state == State.SLEEP }
        return when {
            anyMoving || mediaPlaying || shakePending > 0f -> 33L
            anySleep && !anyMoving -> 420L
            else -> 120L
        }
    }

    private fun resetPetRuntime(p: PetInstance) {
        val now = SystemClock.elapsedRealtime()
        p.state = State.IDLE
        p.touch = null
        p.lastInteractionAt = now
        p.nextActionAt = now + 3_500L
        p.idlePose = PetPose.IDLE
        p.idlePoseStartedAt = 0L
        p.idlePoseUntil = 0L
        p.nextBlinkAt = now + 2_000L
        p.blinkUntil = 0L
        p.nextHeartAt = now
        p.nextZAt = now + 700L
        p.nextNoteAt = now + 400L
        p.nextPoopAt = now + nextPoopDelay(p)
        p.nextPeekAt = now + 45_000L
        p.waveUntil = 0L
        p.expression = PetExpression.OPEN
        p.expressionUntil = 0L
        p.squashUntil = 0L
        p.mouthOpenUntil = 0L
        p.hopActive = false
        p.hopOffset = 0f
        p.hopVelocity = 0f
        p.velocityX = 0f
        p.velocityY = 0f
        p.slideVelocity = 0f
        p.look = 0
        p.peekSide = 0
        p.peekPending = 0
        p.peekOut = false
        p.peekMoving = false
        p.shakeDizzy = false
        p.lastTapAt = 0L
        p.recentTaps.clear()
        p.particles.clear()
    }

    private fun step() {
        val now = SystemClock.elapsedRealtime()
        refreshBounds()
        val dt = ((now - lastTickAt).coerceAtLeast(0L) / 1_000f).coerceIn(0f, 0.05f)
        lastTickAt = now
        if (pets.isEmpty()) return
        if (settings.petsCollide) resolveCollisions()
        val shakePower = if (shakePending > 0f) shakePending.coerceAtMost(40f) else 0f
        if (shakePower > 0f) shakePending = 0f
        for (p in pets.toList()) {
            if (p.view == null) continue
            stepPet(p, dt, now, shakePower)
        }
        val first = pets.firstOrNull()
        if (first != null && now >= first.nextPosSaveAt) {
            first.nextPosSaveAt = now + 8_000L
            saveLastPosition(first)
        }
    }

    /** Столкновения как от стены (k=0.55) с учётом массы (3.3, 3.7):
     * импульс делит отскок обратно массам + positional correction. */
    private fun resolveCollisions() {
        if (pets.size < 2) return
        for (i in pets.indices) {
            for (j in i + 1 until pets.size) {
                val a = pets[i]
                val b = pets[j]
                if (a.view == null || b.view == null) continue
                if (a.state == State.PEEK || b.state == State.PEEK) continue
                // Quick reject: оба спят — не толкаются.
                if (a.state == State.SLEEP && b.state == State.SLEEP) continue
                val minD = (a.winW + b.winW) / 2f * 0.75f
                val dx = (b.posX + b.winW / 2f) - (a.posX + a.winW / 2f)
                // Quick reject по осям — дешевле hypot/sqrt.
                if (dx >= minD || dx <= -minD) continue
                val dy = (b.posY + b.winH / 2f) - (a.posY + a.winH / 2f)
                if (dy >= minD || dy <= -minD) continue
                val dist = kotlin.math.hypot(dx, dy)
                if (dist >= minD || dist < 0.01f) continue
                val nx = dx / dist
                val ny = dy / dist
                val wa = a.settings.weight.coerceIn(0.1f, 5f)
                val wb = b.settings.weight.coerceIn(0.1f, 5f)
                // Относительная скорость вдоль нормали; расходятся — пропускаем.
                val rvx = b.velocityX - a.velocityX
                val rvy = b.velocityY - a.velocityY
                val vn = rvx * nx + rvy * ny
                if (vn <= 0f) {
                    val k = 0.55f
                    val jimp = -(1f + k) * vn / (1f / wa + 1f / wb)
                    if (a.state != State.DRAG) {
                        a.velocityX -= jimp * nx / wa
                        a.velocityY -= jimp * ny / wa
                    }
                    if (b.state != State.DRAG) {
                        b.velocityX += jimp * nx / wb
                        b.velocityY += jimp * ny / wb
                    }
                    if (a.state == State.IDLE || a.state == State.WALK || a.state == State.SLEEP) a.state = State.FLY
                    if (b.state == State.IDLE || b.state == State.WALK || b.state == State.SLEEP) b.state = State.FLY
                }
                // Выталкивание перекрытия пополам.
                val overlap = (minD - dist) * 0.5f
                if (a.state != State.DRAG) {
                    a.posX = (a.posX - nx * overlap).coerceIn(minX, maxX)
                    a.posY = (a.posY - ny * overlap).coerceIn(minY, maxY)
                    updatePetLayout(a)
                }
                if (b.state != State.DRAG) {
                    b.posX = (b.posX + nx * overlap).coerceIn(minX, maxX)
                    b.posY = (b.posY + ny * overlap).coerceIn(minY, maxY)
                    updatePetLayout(b)
                }
                reactToPetTouch(a, b)
            }
        }
    }

    /** Реакция на касание другого питомца (3.8г): HAPPY на 1 сек, cooldown 2 сек.
     * Спящий просыпается только при своём wakeOnPetTouch (задача 4). */
    private fun reactToPetTouch(a: PetInstance, b: PetInstance) {
        val now = SystemClock.elapsedRealtime()
        for (self in listOf(a, b)) {
            if (self.state == State.SLEEP) {
                if (!self.settings.wakeOnPetTouch) continue
                if (now < self.lastPetReactAt) continue
                self.lastPetReactAt = now + 2_000L
                self.state = State.IDLE
                setExpression(self, PetExpression.SURPRISED, 1_000L)
                continue
            }
            if (!self.settings.reactToOtherPets) continue
            if (now < self.lastPetReactAt) continue
            self.lastPetReactAt = now + 2_000L
            setExpression(self, PetExpression.HAPPY, 1_000L)
        }
    }

    /** Касается ли питомец какой-либо кучки (своей или чужой). */
    private fun petTouchesPoop(p: PetInstance): Boolean {
        if (p.view == null || poops.isEmpty()) return false
        val ax0 = p.posX
        val ay0 = p.posY
        val ax1 = p.posX + p.winW
        val ay1 = p.posY + p.winH
        for (entry in poops) {
            val pr = entry.params
            if (ax0 < pr.x + pr.width && ax1 > pr.x && ay0 < pr.y + pr.height && ay1 > pr.y) return true
        }
        return false
    }

    /** Реакция на касание какашки (3.8г): DIZZY + лёгкий отскок, cooldown 2.5 сек. */
    private fun reactToPoopTouch(p: PetInstance, now: Long) {
        if (!p.settings.reactToPoop) return
        if (now < p.lastPoopReactAt) return
        if (!petTouchesPoop(p)) return
        p.lastPoopReactAt = now + 2_500L
        setExpression(p, PetExpression.DIZZY, 1_500L)
        if (p.state == State.IDLE || p.state == State.WALK) {
            hop(p, dpFloat(150f))
        } else if (p.state == State.FLY) {
            p.velocityY -= dpFloat(220f)
        }
    }

    private fun stepPet(p: PetInstance, dt: Float, now: Long, shakePower: Float) {
        val track = p.touch
        if (p.state == State.PRESS && track != null && now - track.downAt >= LONG_PRESS_MS) beginPet(p, now)
        if (shakePower > 0f && p.settings.shake && p.state != State.PRESS && p.state != State.DRAG && p.state != State.PET) applyShake(p, now)

        when (p.state) {
            State.FLY -> stepFlight(p, dt, now)
            State.HANG -> stepHang(p, now)
            State.PEEK -> stepPeek(p, dt, now)
            State.PET -> {
                if (now >= p.nextHeartAt) {
                    p.nextHeartAt = now + 450L
                    spawnParticle(p, ParticleKind.HEART)
                    haptic(p, 18L)
                }
            }
            State.IDLE, State.WALK, State.SLEEP -> stepGround(p, dt, now)
            State.PRESS, State.DRAG -> Unit
        }

        if (p.hopActive) {
            p.hopOffset += p.hopVelocity * dt
            p.hopVelocity += dpFloat(2_500f) * dt
            if (p.hopOffset >= 0f) {
                p.hopOffset = 0f
                p.hopVelocity = 0f
                p.hopActive = false
                p.squashUntil = now + 110L
            }
        }
        updateParticles(p, now)
        render(p, now)
        updateBubble(p, now)
        reactToPoopTouch(p, now)
    }

    private fun stepGround(p: PetInstance, dt: Float, now: Long) {
        if (p.settings.gravity && p.state != State.SLEEP && p.posY < maxY - dp(1f) && !p.hopActive) {
            p.state = State.FLY
            p.velocityX = 0f
            p.velocityY = 0f
            return
        }
        if (!p.settings.gravity) {
            p.posY = p.posY.coerceIn(minY, maxY)
            p.slideVelocity = 0f
        } else if (p.posY < maxY - dp(1f) && !p.hopActive) {
            p.state = State.FLY
            p.velocityY = 0f
            return
        } else {
            p.posY = min(p.posY, maxY)
            p.slideVelocity += tilt * dpFloat(1_000f) * dt
            p.slideVelocity *= kotlin.math.exp(-2.6f * p.settings.slideFriction.coerceIn(0.1f, 3f) * dt)
            if (abs(p.slideVelocity) < dpFloat(7f)) p.slideVelocity = 0f
            if (p.slideVelocity != 0f) {
                p.posX += p.slideVelocity * dt
                if (p.posX < minX || p.posX > maxX) {
                    edgeVibrate(p, abs(p.slideVelocity))
                    p.posX = p.posX.coerceIn(minX, maxX)
                    p.slideVelocity = 0f
                } else if (abs(p.slideVelocity) > dpFloat(55f) && p.state == State.SLEEP) {
                    p.state = State.IDLE
                    setExpression(p, PetExpression.SURPRISED, 800L)
                }
                if (abs(p.slideVelocity) > dpFloat(60f)) {
                    p.state = State.IDLE
                    p.look = if (p.slideVelocity > 0) 1 else -1
                }
            }
        }

        if (p.state == State.WALK) {
            val direction = if (p.walkTarget >= p.posX) 1 else -1
            p.look = direction
            p.posX += direction * dpFloat(42f) * dt
            val reached = if (direction > 0) p.posX >= p.walkTarget else p.posX <= p.walkTarget
            val edge = (direction < 0 && p.posX <= minX) || (direction > 0 && p.posX >= maxX)
            if (reached || edge) {
                p.posX = p.posX.coerceIn(minX, maxX)
                p.state = State.IDLE
                p.nextActionAt = now + randomBetween(2_500L, 6_000L)
                val side = p.peekPending
                p.peekPending = 0
                if (side != 0 && ((side < 0 && p.posX <= minX + dp(4f)) || (side > 0 && p.posX >= maxX - dp(4f)))) {
                    startPeek(p, now, side)
                } else savePosition(p)
            }
            return
        }

        if (p.state == State.SLEEP) {
            if (now >= p.nextZAt) {
                p.nextZAt = now + 1_650L
                spawnParticle(p, ParticleKind.ZZZ)
            }
            return
        }

        if (now >= p.nextBlinkAt) {
            p.blinkUntil = now + 130L
            p.nextBlinkAt = now + if (Random.nextFloat() < 0.15f) 450L else randomBetween(2_000L, 5_500L)
        }

        if (p.settings.sleepMinutes > 0 && now - p.lastInteractionAt > p.settings.sleepMinutes * 60_000L &&
            !p.hopActive && !mediaPlaying && p.state == State.IDLE) {
            p.state = State.SLEEP
            p.look = 0
            p.nextZAt = now + 500L
            hideBubble(p)
            return
        }

        if (p.settings.poop && now >= p.nextPoopAt && p.state == State.IDLE && !p.hopActive) {
            poopNow(p, manual = false)
            return
        }

        if (mediaPlaying && p.settings.dance) {
            if (now >= p.nextNoteAt) {
                p.nextNoteAt = now + 1_050L
                spawnParticle(p, ParticleKind.NOTE)
            }
            return
        }

        if (now < p.nextActionAt || p.hopActive) return
        p.nextActionAt = now + randomBetween(3_500L, 9_000L)
        if (!p.settings.wander) {
            when (Random.nextInt(100)) {
                in 0..18 -> p.look = Random.nextInt(-1, 2)
                in 19..40 -> say(p, "idle")
                in 41..56 -> p.look = Random.nextInt(-1, 2)
                in 57..66 -> p.waveUntil = now + 1_600L
                in 67..75 -> playIdlePose(p, PetPose.STRETCH, 1_400L, "stretch", now)
                in 76..84 -> playIdlePose(p, PetPose.YAWN, 1_200L, "yawn", now)
                in 85..93 -> playIdlePose(p, PetPose.GROOM, 1_800L, "groom", now)
                else -> playIdlePose(p, PetPose.SPIN, 900L, "spin", now)
            }
            return
        }

        val idleLongEnough = now - p.lastInteractionAt > PEEK_AFTER_MS
        if (idleLongEnough && now >= p.nextPeekAt && Random.nextFloat() < 0.22f) {
            val side = if (p.posX - minX < maxX - p.posX) -1 else 1
            p.peekPending = side
            p.walkTarget = if (side < 0) minX else maxX
            p.state = State.WALK
            return
        }

        when (Random.nextInt(100)) {
            in 0..28 -> {
                val distance = dpFloat(Random.nextInt(45, 155).toFloat()) * if (Random.nextBoolean()) 1f else -1f
                p.walkTarget = (p.posX + distance).coerceIn(minX, maxX)
                if (abs(p.walkTarget - p.posX) > dpFloat(12f)) p.state = State.WALK
            }
            in 29..39 -> p.look = Random.nextInt(-1, 2)
            in 40..53 -> say(p, "idle")
            in 54..67 -> p.look = Random.nextInt(-1, 2)
            in 68..75 -> p.waveUntil = now + 1_600L
            in 76..81 -> playIdlePose(p, PetPose.STRETCH, 1_400L, "stretch", now)
            in 82..87 -> playIdlePose(p, PetPose.YAWN, 1_200L, "yawn", now)
            in 88..93 -> playIdlePose(p, PetPose.GROOM, 1_800L, "groom", now)
            else -> playIdlePose(p, PetPose.SPIN, 900L, "spin", now)
        }
    }

    private fun playIdlePose(p: PetInstance, pose: PetPose, durationMs: Long, phrase: String, now: Long) {
        p.idlePose = pose
        p.idlePoseStartedAt = now
        p.idlePoseUntil = now + durationMs
        p.nextActionAt = max(p.nextActionAt, now + durationMs + randomBetween(1_500L, 3_000L))
        say(p, phrase)
    }

    private fun stepFlight(p: PetInstance, dt: Float, now: Long) {
        p.posX += p.velocityX * dt
        p.posY += p.velocityY * dt
        if (p.settings.gravity) {
            // Тяжесть: вес 1.0 = как раньше, 5.0 — падение в ~3x быстрее, 0.1 — в ~0.5x медленнее.
            val weightK = (1f + (p.settings.weight.coerceIn(0.1f, 5f) - 1f) * 0.5f).coerceIn(0.5f, 3f)
            val g = dpFloat(2_450f) * p.settings.gravityStrength.coerceIn(0f, 2f) * weightK
            p.velocityY += g * dt
            p.velocityX *= kotlin.math.exp(-0.32f * p.settings.slideFriction.coerceIn(0.1f, 3f) * dt)
            p.velocityX += tilt * dpFloat(900f) * dt
        } else {
            val friction = kotlin.math.exp(-1.9f * p.settings.slideFriction.coerceIn(0.1f, 3f) * dt)
            p.velocityX *= friction
            p.velocityY *= friction
        }
        if (p.posX < minX) {
            edgeVibrate(p, abs(p.velocityX))
            p.posX = minX
            // Тихий удар — гасим скорость, чтобы не дёргаться у стены.
            p.velocityX = if (abs(p.velocityX) < dpFloat(60f)) 0f else abs(p.velocityX) * 0.55f
        } else if (p.posX > maxX) {
            edgeVibrate(p, abs(p.velocityX))
            p.posX = maxX
            p.velocityX = if (abs(p.velocityX) < dpFloat(60f)) 0f else -abs(p.velocityX) * 0.55f
        }

        if (p.posY < minY) {
            if (p.settings.canHang && p.velocityY < -dpFloat(260f) && !p.shakeDizzy) {
                edgeVibrate(p, abs(p.velocityY))
                startHang(p, now)
                return
            }
            // Потолок как стена: отскок без зависания.
            edgeVibrate(p, abs(p.velocityY))
            p.posY = minY
            p.velocityY = if (abs(p.velocityY) < dpFloat(60f)) 0f else abs(p.velocityY) * 0.55f
        } else if (p.posY > maxY) {
            edgeVibrate(p, abs(p.velocityY))
            p.posY = maxY
            if (p.settings.gravity && p.velocityY < dpFloat(390f)) {
                land(p, now)
            } else {
                p.velocityY = if (abs(p.velocityY) < dpFloat(60f)) {
                    0f
                } else {
                    -abs(p.velocityY) * if (p.settings.gravity) 0.42f else 0.55f
                }
                p.velocityX *= 0.78f
                p.squashUntil = now + 120L
            }
        }

        if (p.state == State.FLY && !p.settings.gravity && hypot(p.velocityX, p.velocityY) < dpFloat(42f)) land(p, now)
        if (p.shakeDizzy && now - p.lastInteractionAt > 1_200L && p.state == State.FLY && hypot(p.velocityX, p.velocityY) < dpFloat(160f)) {
            p.shakeDizzy = false
            setExpression(p, PetExpression.DIZZY, 2_000L)
            say(p, "dizzy")
        }
    }

    private fun land(p: PetInstance, now: Long) {
        p.state = State.IDLE
        p.velocityX = 0f
        p.velocityY = 0f
        // Жёсткая остановка: никакого дотягивания прыжка и минимум 2с до следующего.
        p.hopActive = false
        p.hopOffset = 0f
        p.hopVelocity = 0f
        p.hopCooldownUntil = now + 2_000L
        p.squashUntil = now + 140L
        p.nextActionAt = now + randomBetween(2_000L, 5_000L)
        if (p.shakeDizzy) {
            p.shakeDizzy = false
            setExpression(p, PetExpression.DIZZY, 2_500L)
            say(p, "dizzy")
        }
        savePosition(p)
    }

    private fun startHang(p: PetInstance, now: Long) {
        p.state = State.HANG
        p.velocityX = 0f
        p.velocityY = 0f
        p.posY = minY - p.unit * 1.5f
        p.hangStartedAt = now
        p.hangUntil = now + randomBetween(2_000L, 3_500L)
        haptic(p, 22L)
        if (Random.nextFloat() < 0.7f) say(p, "hang")
    }

    private fun stepHang(p: PetInstance, now: Long) {
        p.posX = p.posX.coerceIn(minX, maxX)
        p.posY = minY - p.unit * 1.5f
        if (now < p.hangUntil) return
        p.state = State.FLY
        p.velocityX = 0f
        p.velocityY = dpFloat(if (p.settings.gravity) 160f else 650f)
        setExpression(p, PetExpression.SURPRISED, 700L)
        haptic(p, 16L)
        if (Random.nextFloat() < 0.6f) say(p, "hang_fall")
    }

    private fun stepPeek(p: PetInstance, dt: Float, now: Long) {
        val hiddenUnits = 3.2f
        val visibleUnits = 7f
        val targetX = if (p.peekSide > 0) {
            screenWidth - p.unit * if (p.peekOut) visibleUnits else hiddenUnits
        } else {
            -p.winW + p.unit * if (p.peekOut) visibleUnits else hiddenUnits
        }
        val delta = targetX - p.posX
        val step = dpFloat(52f) * dt
        p.peekMoving = abs(delta) > 1f
        if (p.peekMoving) setPosition(p, p.posX + delta.coerceIn(-step, step), p.posY, allowOffscreenX = true)
        p.look = if (!p.peekOut && p.peekMoving) p.peekSide else -p.peekSide
        if (now < p.peekPhaseUntil || p.peekMoving) return
        if (now >= p.peekEndAt) {
            endPeek(p, now)
            return
        }
        if (!p.peekOut) {
            p.peekOut = true
            p.peekPhaseUntil = now + randomBetween(1_400L, 2_800L)
            if (Random.nextFloat() < 0.55f) say(p, "peek")
        } else {
            p.peekOut = false
            p.peekPhaseUntil = now + randomBetween(3_000L, 7_000L)
        }
    }

    private fun startPeek(p: PetInstance, now: Long, side: Int) {
        p.state = State.PEEK
        p.peekSide = side
        p.peekOut = false
        p.peekMoving = true
        p.peekPhaseUntil = now + randomBetween(2_500L, 5_000L)
        p.peekEndAt = now + randomBetween(25_000L, 45_000L)
        p.look = side
    }

    private fun endPeek(p: PetInstance, now: Long) {
        val side = p.peekSide
        p.peekSide = 0
        p.peekMoving = false
        p.nextPeekAt = now + randomBetween(60_000L, 120_000L)
        p.state = State.WALK
        p.walkTarget = if (side < 0) minX + dpFloat(30f) else maxX - dpFloat(30f)
        p.nextActionAt = now + randomBetween(3_000L, 6_000L)
    }

    private fun onPetTouch(p: PetInstance, event: MotionEvent): Boolean {
        val now = SystemClock.elapsedRealtime()
        val rawX = event.rawX
        val rawY = event.rawY
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Скрытый питомец (hideOnAppOpen) показывается первым тапом.
                if (p.hiddenUntilTap) {
                    p.hiddenUntilTap = false
                    p.lastInteractionAt = now
                    endPeek(p, now)
                    scheduleTick(0L)
                    return true
                }
                val wasSleeping = p.state == State.SLEEP
                val wasPeeking = p.state == State.PEEK
                p.lastInteractionAt = now
                val track = TouchTrack(
                    downX = rawX,
                    downY = rawY,
                    downAt = now,
                    offsetX = rawX - p.posX,
                    offsetY = rawY - p.posY,
                    anchorX = rawX,
                )
                track.samples.add(TouchPoint(now, rawX, rawY))
                p.touch = track
                p.hopActive = false
                p.hopOffset = 0f
                p.hopVelocity = 0f
                p.velocityX = 0f
                p.velocityY = 0f
                p.slideVelocity = 0f
                p.peekSide = 0
                p.peekPending = 0
                p.state = State.PRESS
                if (wasSleeping) {
                    setExpression(p, PetExpression.SURPRISED, 800L)
                    say(p, "wake")
                } else if (wasPeeking) {
                    p.nextPeekAt = now + randomBetween(60_000L, 120_000L)
                    setExpression(p, PetExpression.SURPRISED, 650L)
                    say(p, "found")
                }
                scheduleTick(0L)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val track = p.touch ?: return false
                addTouchSample(track, now, rawX, rawY)
                if (p.state == State.PRESS && hypot(rawX - track.downX, rawY - track.downY) > dpFloat(8f)) {
                    p.state = State.DRAG
                    p.peekPending = 0
                    haptic(p, 12L)
                    if (Random.nextFloat() < 0.25f) say(p, "drag")
                }
                if (p.state == State.DRAG) {
                    setPosition(p, rawX - track.offsetX, rawY - track.offsetY)
                    val first = track.samples.firstOrNull()
                    if (first != null && now - first.timeMs > 5L) track.velocityX = (rawX - first.x) / ((now - first.timeMs) / 1_000f)
                } else if (p.state == State.PET) {
                    val dx = rawX - track.anchorX
                    if (abs(dx) > dpFloat(10f)) {
                        val direction = if (dx > 0f) 1 else -1
                        if (track.direction != 0 && direction != track.direction) spawnParticle(p, ParticleKind.HEART)
                        track.direction = direction
                        track.anchorX = rawX
                    }
                }
                scheduleTick(0L)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val track = p.touch ?: return false
                p.touch = null
                p.lastInteractionAt = now
                val cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL
                when (p.state) {
                    State.DRAG -> releaseDrag(p, now, track, cancelled)
                    State.PET -> endPet(p, now)
                    State.PRESS -> {
                        if (!cancelled && now - track.downAt >= LONG_PRESS_MS) {
                            beginPet(p, now)
                            endPet(p, now)
                        } else {
                            p.state = State.IDLE
                        }
                    }
                    else -> p.state = State.IDLE
                }
                scheduleTick(0L)
                return true
            }
        }
        return true
    }

    private fun addTouchSample(track: TouchTrack, now: Long, x: Float, y: Float) {
        track.samples.add(TouchPoint(now, x, y))
        while (track.samples.size > 2 && now - track.samples.first().timeMs > 110L) track.samples.removeAt(0)
        while (track.samples.size > 6) track.samples.removeAt(0)
    }

    private fun releaseDrag(p: PetInstance, now: Long, track: TouchTrack, cancelled: Boolean) {
        var vx = 0f
        var vy = 0f
        val first = track.samples.firstOrNull()
        val last = track.samples.lastOrNull()
        if (!cancelled && first != null && last != null && last.timeMs - first.timeMs > 5L && now - last.timeMs < 100L) {
            val elapsed = (last.timeMs - first.timeMs) / 1_000f
            vx = (last.x - first.x) / elapsed
            vy = (last.y - first.y) / elapsed
        }
        val limit = dpFloat(6_000f)
        vx = vx.coerceIn(-limit, limit)
        vy = vy.coerceIn(-limit, limit)
        if (!cancelled && p.settings.canHang && abs(vy) > dpFloat(700f) && p.posY <= minY + p.unit * 2f) {
            startHang(p, now)
        } else if (!cancelled && (hypot(vx, vy) > dpFloat(700f) || p.settings.gravity)) {
            p.state = State.FLY
            p.velocityX = vx
            p.velocityY = vy
            if (hypot(vx, vy) > dpFloat(2_500f)) say(p, "throw")
        } else {
            p.state = State.IDLE
            p.squashUntil = now + 130L
            p.nextActionAt = now + randomBetween(2_000L, 5_000L)
            savePosition(p)
        }
    }

    private fun onTap(p: PetInstance, now: Long) {
        if (now - p.lastTapAt < DOUBLE_TAP_MS) {
            p.lastTapAt = 0L
            hop(p, dpFloat(360f))
            setExpression(p, PetExpression.HAPPY, 1_300L)
            repeat(3) { spawnParticle(p, ParticleKind.SPARK) }
            haptic(p, 14L)
            if (Random.nextFloat() < 0.4f) say(p, "jump")
            return
        }
        p.lastTapAt = now
        while (p.recentTaps.isNotEmpty() && now - p.recentTaps.first() > 2_500L) p.recentTaps.removeAt(0)
        p.recentTaps.add(now)
        if (p.recentTaps.size >= 6) {
            p.recentTaps.clear()
            setExpression(p, PetExpression.ANGRY, 2_000L)
            say(p, "annoyed", force = true)
            haptic(p, 18L)
            return
        }
        p.squashUntil = now + 130L
        setExpression(p, PetExpression.SURPRISED, 450L)
        hop(p, dpFloat(200f))
        haptic(p, 12L)
        if (Random.nextFloat() < 0.35f) say(p, "tap")
    }

    private fun beginPet(p: PetInstance, now: Long) {
        if (p.state != State.PRESS && p.state != State.PET) return
        p.state = State.PET
        p.nextHeartAt = now + 100L
        haptic(p, 12L)
    }

    private fun endPet(p: PetInstance, now: Long) {
        p.state = State.IDLE
        setExpression(p, PetExpression.HAPPY, 2_200L)
        p.nextActionAt = now + randomBetween(3_000L, 6_000L)
        val count = prefs.getInt(PetPrefs.PETS_COUNT, 0) + 1
        prefs.edit().putInt(PetPrefs.PETS_COUNT, count).apply()
        if (Random.nextFloat() < 0.65f) say(p, "pet")
    }

    /**
     * Прыжок. Верхушка спрайта в окне — на 3u от верха (topPad 2u + ряд ушей),
     * расширить окно вверх нельзя (отрисовка привязана к верху View в PetArtwork),
     * поэтому сила ограничивается запасом 2.5u: apex = v²/2g ≤ 2.5u.
     */
    private fun hop(p: PetInstance, strengthPx: Float) {
        if (p.state != State.IDLE && p.state != State.WALK) return
        val now = SystemClock.elapsedRealtime()
        // Кулдаун после приземления + антиспам-блок.
        if (now < p.hopCooldownUntil || now < p.hopBlockedUntil) return
        p.hopTimes.addLast(now)
        while (p.hopTimes.isNotEmpty() && now - p.hopTimes.first() > 3_000L) {
            p.hopTimes.removeFirst()
        }
        if (p.hopTimes.size >= 3) {
            p.hopBlockedUntil = now + 5_000L
            p.hopTimes.clear()
            return
        }
        val headroomPx = 2.5f * p.unit.coerceAtLeast(1f)
        // Плюс запас до верха экрана: верхушка спрайта не должна уходить за экран.
        val roomPx = (p.posY + 2f * p.unit.coerceAtLeast(1f)).coerceAtLeast(0f)
        val maxStrengthPx = min(
            kotlin.math.sqrt(2f * dpFloat(2_500f) * headroomPx),
            kotlin.math.sqrt(2f * dpFloat(2_500f) * roomPx),
        )
        p.hopActive = true
        p.hopVelocity = -strengthPx.coerceAtMost(maxStrengthPx)
        p.hopOffset = 0f
    }

    private fun setExpression(p: PetInstance, value: PetExpression, durationMs: Long) {
        p.expression = value
        p.expressionUntil = SystemClock.elapsedRealtime() + durationMs
    }

    private fun recenter() {
        if (pets.isEmpty()) return
        refreshBounds(force = true)
        pets.forEachIndexed { index, p -> recenterPet(p, index) }
        savePosition(pets.first())
        scheduleTick(0L)
    }

    private fun recenterPet(p: PetInstance, index: Int) {
        if (p.view == null) return
        p.posX = ((minX + maxX) / 2f + index * p.winW * 0.3f).coerceIn(minX, maxX)
        p.posY = (minY + maxY) / 2f
        p.state = State.IDLE
        p.velocityX = 0f
        p.velocityY = 0f
        p.slideVelocity = 0f
        p.peekSide = 0
        p.peekPending = 0
        p.lastInteractionAt = SystemClock.elapsedRealtime()
        updatePetLayout(p, force = true)
        hop(p, dpFloat(500f))
        repeat(3) { spawnParticle(p, ParticleKind.SPARK) }
        say(p, "hello")
    }

    private fun spawnParticle(p: PetInstance, kind: ParticleKind, originX: Float? = null, originY: Float? = null) {
        if (p.view == null) return
        while (p.particles.size >= MAX_PARTICLES) p.particles.removeAt(0)
        val now = SystemClock.elapsedRealtime()
        val width = p.winW.toFloat()
        val cell = p.unit * 0.72f
        val x = originX ?: (width / 2f - cell * 3f + Random.nextFloat() * cell * 5f)
        val y = originY ?: when (kind) {
            ParticleKind.ZZZ -> p.unit * 4.2f
            else -> p.unit * 3.1f
        }
        p.particles.add(
            PetParticle(
                kind = kind,
                x = x,
                y = y,
                driftX = (Random.nextFloat() - 0.5f) * dpFloat(32f),
                bornAtMs = now,
                durationMs = if (kind == ParticleKind.ZZZ) 1_600L else 1_100L,
            ),
        )
    }

    private fun updateParticles(p: PetInstance, now: Long) {
        val iterator = p.particles.iterator()
        while (iterator.hasNext()) {
            val particle = iterator.next()
            if (now - particle.bornAtMs >= particle.durationMs) iterator.remove()
        }
    }

    /** Базовый интервал кучек 150..330с, умноженный на множитель из настроек. */
    private fun nextPoopDelay(p: PetInstance): Long =
        (randomBetween(150_000L, 330_000L) * p.settings.poopInterval.coerceIn(0.25f, 4.0f)).toLong()

    private fun poopNow(p: PetInstance, manual: Boolean) {
        if ((!manual && !p.settings.poop) || p.view == null) return
        val now = SystemClock.elapsedRealtime()
        p.nextPoopAt = now + nextPoopDelay(p)
        if (poops.size >= settings.maxPoops.coerceAtLeast(1)) {
            if (!manual) return
            // Удаляем самую старую (первую в списке), новая встанет в конец.
            removePoop(poops.first(), animated = true, count = false)
        }
        val poopUnit = max(dp(3f).toFloat(), p.unit * 0.9f) * p.settings.poopSize.coerceIn(0.5f, 3.0f)
        val poopWidth = (poopUnit * 9f).toInt()
        val poopHeight = (poopUnit * 7f).toInt()
        val side = if (p.look == 0) if (Random.nextBoolean()) -1 else 1 else -p.look
        val centerX = p.posX + p.winW / 2f + side * p.winW * 0.42f
        val px = (centerX - poopWidth / 2f).coerceIn(0f, max(0, screenWidth - poopWidth).toFloat())
        val py = (p.posY + p.winH - poopHeight).coerceIn(minY, max(minY, (screenHeight - safeBottom - poopHeight).toFloat()))
        lateinit var view: View
        val bmp = currentPoopBitmap(p.skinId)
        if (bmp != null) {
            val iv = ImageView(this)
            iv.setImageBitmap(bmp)
            iv.scaleType = ImageView.ScaleType.FIT_XY
            iv.contentDescription = "Убрать кучку"
            iv.scaleX = 0.1f
            iv.scaleY = 0.1f
            iv.isClickable = true
            iv.setOnClickListener { v -> removePoopByView(v) }
            view = iv
        } else {
            val pv = PoopArtView(this) { removePoopByView(view) }
            pv.poopColorHex = p.settings.poopColor
            view = pv
        }
        val params = overlayParams(poopWidth, poopHeight, touchable = true).apply {
            x = px.toInt()
            y = py.toInt()
            alpha = 1f
            title = "GUPet — убрать кучку"
        }
        try {
            windowManager.addView(view, params)
        } catch (_: Exception) {
            return
        }
        val entry = PoopWindow(view, params, now)
        poops.add(entry)
        view.animate().scaleX(1f).scaleY(1f).setDuration(320L).start()
        repeat(2) { spawnParticle(p, ParticleKind.SPARK) }
        p.squashUntil = now + 180L
        setExpression(p, PetExpression.HAPPY, 1_700L)
        haptic(p, 18L)
        say(p, "poop")
        p.look = -side
        if (p.settings.wander && p.state == State.IDLE) {
            val target = (p.posX - side * dpFloat(Random.nextInt(40, 80).toFloat())).coerceIn(minX, maxX)
            if (abs(target - p.posX) > dpFloat(12f)) {
                p.walkTarget = target
                p.state = State.WALK
            }
        }
        p.nextActionAt = now + randomBetween(3_000L, 6_000L)
        scheduleTick(0L)
    }

    private fun removePoopByView(view: View) {
        val entry = poops.firstOrNull { it.view === view } ?: return
        removePoop(entry, animated = true, count = true)
    }

    /** Ближайший питомец к точке экрана (для эффектов уборки кучек). */
    private fun nearestPetTo(x: Float, y: Float): PetInstance? {
        return pets.filter { it.view != null }.minByOrNull {
            val cx = it.posX + windowWidth / 2f - x
            val cy = it.posY + windowHeight / 2f - y
            cx * cx + cy * cy
        } ?: firstPet()
    }

    private fun removePoop(entry: PoopWindow, animated: Boolean, count: Boolean) {
        if (!poops.remove(entry)) return
        entry.view.setOnClickListener(null)
        if (count) {
            val total = prefs.getInt(PetPrefs.POOPS_CLEANED, 0) + 1
            prefs.edit().putInt(PetPrefs.POOPS_CLEANED, total).apply()
            val near = nearestPetTo(entry.params.x.toFloat(), entry.params.y.toFloat())
            if (near != null) {
                repeat(3) { spawnParticle(near, ParticleKind.SPARK) }
                if (near.settings.phrases && Random.nextFloat() < 0.7f) say(near, "clean")
                haptic(near, 16L)
            }
        }
        val fadeMs = (nearestPetTo(entry.params.x.toFloat(), entry.params.y.toFloat())?.settings?.poopFadeMs
            ?: settings.poopFadeMs).coerceIn(0, 3000)
        if (animated && fadeMs > 0) {
            entry.view.animate()
                .scaleX(0.05f).scaleY(0.05f).alpha(0f).rotation(90f)
                .setDuration(fadeMs.toLong())
                .withEndAction { removeOverlay(entry.view) }
                .start()
        } else {
            removeOverlay(entry.view)
        }
    }

    private fun cleanAllPoops(animated: Boolean, count: Boolean = true) {
        if (poops.isEmpty()) return
        val copy = poops.toList()
        copy.forEach { removePoop(it, animated, count) }
    }

    private fun showBubble(p: PetInstance, category: String, force: Boolean = false) {
        if (!force && !p.settings.phrases) return
        val all = phraseCache ?: PetPhrasesStore.load(this).also { phraseCache = it }
        var pool = all.filter { it.enabled && it.context == category }
        if (pool.isEmpty()) pool = all.filter { it.enabled && it.context == "idle" }
        if (pool.isEmpty()) return
        var pick = pool.last()
        if (pool.size > 1) {
            var r = Random.nextInt(pool.sumOf { it.weight.coerceIn(1, 10) }.coerceAtLeast(1))
            for (p in pool) {
                r -= p.weight.coerceIn(1, 10)
                if (r < 0) {
                    pick = p
                    break
                }
            }
            if (pick.text == p.lastPhrase) pick = pool[(pool.indexOf(pick) + 1) % pool.size]
        }
        p.lastPhrase = pick.text
        showBubbleText(p, pick.text)
    }

    private fun say(p: PetInstance, category: String, force: Boolean = false) = showBubble(p, category, force)

    private fun showBubbleText(p: PetInstance, text: String) {
        val now = SystemClock.elapsedRealtime()
        val view = p.bubbleView ?: createBubbleView().also { p.bubbleView = it }
        styleBubbleView(p, view)
        view.animate().cancel()
        view.text = text
        view.alpha = 0f
        view.translationY = 0f
        view.scaleX = 1f
        view.scaleY = 1f
        view.visibility = View.VISIBLE
        p.bubbleVisible = true
        p.bubbleFadeIn = true
        p.bubbleUntil = now + p.settings.phraseDurationMs
        measureAndAddBubble(p, view)
    }

    private fun parsePhraseColor(hex: String, fallback: Int): Int =
        runCatching { android.graphics.Color.parseColor(hex) }.getOrNull() ?: fallback

    /** Применяет стиль реплик из настроек при каждом показе. Пузырь всегда горизонтальный. */
    private fun styleBubbleView(p: PetInstance, view: TextView) {
        val settings = p.settings
        view.textSize = settings.phraseFontSize
        view.setTextColor(parsePhraseColor(settings.phraseColor, 0xFFF4F1F8.toInt()))
        view.setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
        view.maxWidth = dp(220f)
        view.maxLines = 3
        view.includeFontPadding = false
        val bg = parsePhraseColor(settings.phraseBgColor, 0xFF29262E.toInt())
        val bgA = (255 * settings.phraseBgAlpha.coerceIn(0f, 1f)).toInt()
        view.background = GradientDrawable().apply {
            cornerRadius = dp(14f).toFloat()
            setColor((bgA shl 24) or (bg and 0x00FFFFFF))
            val w = dp(settings.phraseOutlineWidth).coerceAtLeast(0)
            if (w > 0) setStroke(w, parsePhraseColor(settings.phraseOutlineColor, 0xFFFF4D30.toInt()))
        }
    }

    private fun createBubbleView(): TextView {
        val dark = (resources.configuration.uiMode and 0x30) == 0x20
        return TextView(this).apply {
            textSize = 13f
            setTextColor(if (dark) 0xFFF4F1F8.toInt() else 0xFF2A2523.toInt())
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            maxWidth = dp(220f)
            maxLines = 3
            includeFontPadding = false
            background = GradientDrawable().apply {
                cornerRadius = dp(14f).toFloat()
                setColor(if (dark) 0xFF29262E.toInt() else 0xFFFFFBF8.toInt())
                setStroke(dp(1f).coerceAtLeast(1), if (dark) 0xFF514B57.toInt() else 0xFFE7D9D3.toInt())
            }
            elevation = dp(4f).toFloat()
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    private fun measureAndAddBubble(p: PetInstance, view: TextView) {
        val maxWidthPx = dp(220f)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(maxWidthPx, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val width = view.measuredWidth.coerceAtLeast(dp(42f))
        val height = view.measuredHeight.coerceAtLeast(dp(30f))
        val params = p.bubbleParams ?: overlayParams(width, height, touchable = false).apply {
            alpha = 0.78f
            title = "GUPet — реплика"
        }.also { p.bubbleParams = it }
        params.width = width
        params.height = height
        if (view.parent == null) {
            try {
                windowManager.addView(view, params)
            } catch (_: Exception) {
                p.bubbleView = null
                p.bubbleParams = null
                return
            }
        } else {
            try { windowManager.updateViewLayout(view, params) } catch (_: Exception) { }
        }
        positionBubble(p)
    }

    private fun positionBubble(p: PetInstance) {
        val view = p.bubbleView ?: return
        val params = p.bubbleParams ?: return
        if (view.width <= 0 && view.measuredWidth <= 0) return
        val width = if (view.width > 0) view.width else view.measuredWidth
        val height = if (view.height > 0) view.height else view.measuredHeight
        val x = (p.posX + p.winW / 2f - width / 2f).coerceIn(0f, max(0, screenWidth - width).toFloat())
        var y = p.posY + p.unit * 1.5f - height - dp(4f)
        if (y < safeTop) y = p.posY + p.winH + dp(4f)
        y = y.coerceIn(0f, max(0, screenHeight - height).toFloat())
        val ix = x.toInt()
        val iy = y.toInt()
        if (params.x == ix && params.y == iy) return
        params.x = ix
        params.y = iy
        try { windowManager.updateViewLayout(view, params) } catch (_: Exception) { }
    }

    private fun updateBubble(p: PetInstance, now: Long) {
        if (!p.bubbleVisible) return
        if (now >= p.bubbleUntil) {
            hideBubble(p)
            return
        }
        positionBubble(p)
        if (p.bubbleFadeIn) {
            p.bubbleFadeIn = false
            p.bubbleView?.animate()?.alpha(1f)?.setDuration(150L)?.start()
        }
    }

    private fun hideBubble(p: PetInstance, animated: Boolean = true) {
        val view = p.bubbleView ?: return
        p.bubbleVisible = false
        p.bubbleFadeIn = false
        p.bubbleView = null
        p.bubbleParams = null
        val fadeMs = p.settings.phraseFadeMs.coerceIn(0, 3000).toLong()
        if (!animated || fadeMs <= 0L || p.settings.phraseFadeStyle == "none") {
            view.animate().cancel()
            removeOverlay(view)
            return
        }
        val anim = view.animate().alpha(0f).setDuration(fadeMs)
        when (p.settings.phraseFadeStyle) {
            "slideUp" -> anim.translationYBy(-dp(16f).toFloat())
            "scale" -> {
                view.pivotX = (if (view.width > 0) view.width else view.measuredWidth) / 2f
                view.pivotY = (if (view.height > 0) view.height else view.measuredHeight) / 2f
                anim.scaleX(1.15f).scaleY(1.15f)
            }
        }
        anim.withEndAction { removeOverlay(view) }.start()
    }

    private fun render(p: PetInstance, now: Long) {
        val view = p.view ?: return
        val pose = when (p.state) {
            State.WALK -> PetPose.WALK
            State.PRESS -> PetPose.PRESS
            State.DRAG -> PetPose.DRAG
            State.PET -> PetPose.PET
            State.FLY -> PetPose.FLY
            State.SLEEP -> PetPose.SLEEP
            State.HANG -> PetPose.HANG
            State.PEEK -> PetPose.PEEK
            State.IDLE -> when {
                mediaPlaying && p.settings.dance -> musicPose(now)
                now < p.idlePoseUntil -> p.idlePose
                else -> PetPose.IDLE
            }
        }
        var face = when {
            p.state == State.SLEEP -> PetExpression.SLEEP
            now < p.expressionUntil -> p.expression
            now < p.blinkUntil && (p.state == State.IDLE || p.state == State.WALK || p.state == State.PEEK || p.state == State.HANG) -> PetExpression.BLINK
            else -> PetExpression.OPEN
        }
        var waveSide = 0
        if (pose == PetPose.PEEK && p.peekOut && !p.peekMoving && (now / 220L) % 2L == 0L) waveSide = if (p.peekSide < 0) -1 else 1
        if (now < p.waveUntil && (now / 240L) % 2L == 0L) waveSide = if ((now / 240L) % 4L == 0L) -1 else 1
        if (mediaPlaying && p.settings.dance && p.state == State.IDLE) {
            face = PetExpression.HAPPY
            waveSide = if ((now / 240L) % 2L == 0L) -1 else 1
        }
        var rotation = 0f
        when {
            p.state == State.FLY -> rotation = (p.velocityX / dpFloat(70f)).coerceIn(-25f, 25f)
            p.state == State.HANG -> rotation = (sin((now - p.hangStartedAt) / 1_000f * 3f) * 6f)
            pose == PetPose.SPIN && mediaPlaying && p.state == State.IDLE -> rotation = ((now % 2_400L) / 2_400f) * 360f
            pose == PetPose.SPIN && now < p.idlePoseUntil -> rotation = (((now - p.idlePoseStartedAt).coerceAtLeast(0L) % 900L) / 900f) * 360f
            p.slideVelocity != 0f -> rotation = (-p.slideVelocity / dpFloat(30f)).coerceIn(-12f, 12f)
            p.expression == PetExpression.DIZZY && now < p.expressionUntil -> rotation = sin(now / 100f) * 7f
            mediaPlaying && p.state == State.IDLE -> rotation = sin(now / 250f) * 5f
            p.state == State.DRAG && p.touch != null -> rotation = (p.touch?.velocityX ?: 0f).div(dpFloat(90f)).coerceIn(-18f, 18f)
        }
        val squash = when {
            p.hopActive && p.hopVelocity < 0f -> -1f
            now < p.squashUntil -> 1f
            else -> 0f
        }
        view.frame = PetFrame(
            pose = pose,
            expression = face,
            look = p.look,
            waveSide = waveSide,
            blush = p.state == State.PET || face == PetExpression.HAPPY || pose == PetPose.DANCE,
            mouthOpen = now < p.mouthOpenUntil || (p.state == State.PET && (now / 180L) % 2L == 0L),
            squash = squash,
            rotation = rotation,
            hopOffset = p.hopOffset,
            phase = now / 1_000f,
        )
        view.frameTimeMs = now
        view.particles = p.particles
        updatePetLayout(p)
    }

    private fun reactToNotification(p: PetInstance, mood: String) {
        // Тест реплики из редактора: "say:<текст>" показывает текст напрямую.
        if (mood.startsWith("say:")) {
            if (!running || p.view == null) return
            p.lastInteractionAt = SystemClock.elapsedRealtime()
            showBubbleText(p, mood.removePrefix("say:"))
            scheduleTick(0L)
            return
        }
        if (!p.settings.reactNotifications || !running || p.view == null) return
        val now = SystemClock.elapsedRealtime()
        if (now < p.nextExternalReactionAt || (p.state != State.IDLE && p.state != State.WALK && p.state != State.SLEEP && p.state != State.PEEK)) return
        p.nextExternalReactionAt = now + if (mood == "mail") 5_000L else 3_000L
        p.lastInteractionAt = now
        if (p.state == State.SLEEP) p.state = State.IDLE
        if (p.state == State.PEEK) endPeek(p, now)
        when (mood) {
            "love" -> {
                setExpression(p, PetExpression.HAPPY, 2_000L)
                repeat(3) { spawnParticle(p, ParticleKind.HEART) }
                say(p, "love")
            }
            "laugh" -> {
                setExpression(p, PetExpression.HAPPY, 1_500L)
                hop(p, dpFloat(400f))
                spawnParticle(p, ParticleKind.SPARK)
                say(p, "laugh")
            }
            "hi" -> {
                p.waveUntil = now + 1_800L
                say(p, "hi")
            }
            else -> {
                p.look = if (Random.nextBoolean()) 1 else -1
                setExpression(p, PetExpression.SURPRISED, 700L)
                hop(p, dpFloat(290f))
                say(p, "mail")
            }
        }
        p.nextActionAt = max(p.nextActionAt, now + 2_500L)
        scheduleTick(0L)
    }

    private val mediaRetry = object : Runnable {
        override fun run() {
            if (running && pets.any { it.settings.dance }) updateMediaMonitor()
        }
    }

    private fun hasMediaSessionAccess(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        val expected = ComponentName(this, PetNotificationListenerService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
    }

    private fun updateMediaMonitor() {
        if (!running || pets.none { it.settings.dance } || !hasMediaSessionAccess()) {
            handler.removeCallbacks(mediaRetry)
            mediaRetryCount = 0
            unregisterMediaMonitor()
            setMediaPlaying(false)
            return
        }
        val manager = getSystemService(MediaSessionManager::class.java) ?: return
        val component = ComponentName(this, PetNotificationListenerService::class.java)
        if (!mediaListenerRegistered) {
            val listener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
                syncMediaControllers(controllers)
            }
            try {
                manager.addOnActiveSessionsChangedListener(listener, component, handler)
                mediaSessionManager = manager
                mediaSessionsListener = listener
                mediaListenerRegistered = true
                mediaRetryCount = 0
            } catch (_: SecurityException) {
                scheduleMediaRetry()
                return
            } catch (_: RuntimeException) {
                scheduleMediaRetry()
                return
            }
        }
        try {
            syncMediaControllers(manager.getActiveSessions(component))
        } catch (_: SecurityException) {
            scheduleMediaRetry()
        } catch (_: RuntimeException) {
            scheduleMediaRetry()
        }
    }

    private fun scheduleMediaRetry() {
        if (!running || pets.none { it.settings.dance } || !hasMediaSessionAccess() || mediaRetryCount >= 8) return
        mediaRetryCount++
        handler.removeCallbacks(mediaRetry)
        handler.postDelayed(mediaRetry, 1_250L)
    }

    private fun syncMediaControllers(controllers: List<MediaController>?) {
        val current = controllers.orEmpty().filter { it.packageName != packageName }
        val keep = current.toSet()
        mediaControllerCallbacks.keys.filter { it !in keep }.toList().forEach { controller ->
            val callback = mediaControllerCallbacks.remove(controller) ?: return@forEach
            try { controller.unregisterCallback(callback) } catch (_: RuntimeException) { }
        }
        current.forEach { controller ->
            if (!mediaControllerCallbacks.containsKey(controller)) {
                val callback = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        refreshMediaPlayback()
                    }
                    override fun onSessionDestroyed() {
                        handler.post { refreshActiveMediaSessions() }
                    }
                }
                try {
                    controller.registerCallback(callback, handler)
                    mediaControllerCallbacks[controller] = callback
                } catch (_: RuntimeException) { }
            }
        }
        activeMediaControllers.clear()
        activeMediaControllers.addAll(current)
        refreshMediaPlayback()
    }

    private fun refreshActiveMediaSessions() {
        val manager = mediaSessionManager ?: return
        try {
            syncMediaControllers(manager.getActiveSessions(ComponentName(this, PetNotificationListenerService::class.java)))
        } catch (_: SecurityException) {
            scheduleMediaRetry()
        } catch (_: RuntimeException) {
            scheduleMediaRetry()
        }
    }

    private fun refreshMediaPlayback() {
        val playing = activeMediaControllers.any { controller ->
            when (controller.playbackState?.state) {
                PlaybackState.STATE_PLAYING,
                PlaybackState.STATE_BUFFERING,
                PlaybackState.STATE_FAST_FORWARDING,
                PlaybackState.STATE_REWINDING -> true
                else -> false
            }
        }
        setMediaPlaying(playing)
    }

    private fun unregisterMediaMonitor() {
        val manager = mediaSessionManager
        val listener = mediaSessionsListener
        if (manager != null && listener != null && mediaListenerRegistered) {
            try { manager.removeOnActiveSessionsChangedListener(listener) } catch (_: RuntimeException) { }
        }
        mediaControllerCallbacks.toList().forEach { (controller, callback) ->
            try { controller.unregisterCallback(callback) } catch (_: RuntimeException) { }
        }
        mediaControllerCallbacks.clear()
        activeMediaControllers.clear()
        mediaSessionManager = null
        mediaSessionsListener = null
        mediaListenerRegistered = false
    }

    private fun musicPose(now: Long): PetPose = when ((now / 700L) % 10L) {
        4L -> PetPose.STRETCH
        7L -> PetPose.SPIN
        9L -> PetPose.GROOM
        else -> PetPose.DANCE
    }

    private fun setMediaPlaying(playing: Boolean) {
        if (!running || mediaPlaying == playing) return
        if (playing && pets.none { it.settings.dance }) return
        mediaPlaying = playing
        val now = SystemClock.elapsedRealtime()
        if (playing) {
            for (p in pets) {
                p.lastInteractionAt = now
                p.nextNoteAt = now + 250L
                p.idlePoseUntil = 0L
                p.idlePose = PetPose.IDLE
                if (p.state == State.SLEEP) p.state = State.IDLE
                if (p.state == State.PEEK) endPeek(p, now)
                say(p, "music")
            }
        } else {
            for (p in pets) {
                p.idlePoseUntil = 0L
                p.idlePose = PetPose.IDLE
                p.nextActionAt = now + randomBetween(2_000L, 4_000L)
                if (Random.nextFloat() < 0.7f) say(p, "music_end")
            }
        }
        scheduleTick(0L)
    }

    private fun registerSensorIfNeeded() {
        val shouldListen = running && pets.any { it.settings.gravity || it.settings.shake }
        if (shouldListen && !sensorRegistered) {
            val manager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
            val sensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
            if (manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)) {
                sensorManager = manager
                accelerometer = sensor
                sensorRegistered = true
                hasLastAccel = false
                shakeHits.clear()
            }
        } else if (!shouldListen && sensorRegistered) {
            unregisterSensor()
        }
    }

    private fun unregisterSensor() {
        if (sensorRegistered) {
            try { sensorManager?.unregisterListener(this) } catch (_: Exception) { }
        }
        sensorManager = null
        accelerometer = null
        sensorRegistered = false
        hasLastAccel = false
        shakeHits.clear()
        tilt = 0f
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!running || pets.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        val ax = event.values[0]
        val ay = event.values[1]
        val az = event.values[2]
        val hadPrevious = hasLastAccel
        val previousX = lastAccelX
        val previousY = lastAccelY
        val previousZ = lastAccelZ
        lastAccelX = ax
        lastAccelY = ay
        lastAccelZ = az
        hasLastAccel = true
        if (settings.shake && hadPrevious && pets.any { it.state != State.PRESS && it.state != State.DRAG && it.state != State.PET }) {
            val dx = ax - previousX
            val dy = ay - previousY
            val dz = az - previousZ
            val jerk = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
            if (jerk > SHAKE_JERK) {
                shakeHits.removeAll { now - it > SHAKE_WINDOW_MS }
                shakeHits.add(now)
                if (shakeHits.size >= SHAKE_HITS) {
                    shakeHits.clear()
                    shakePending = max(shakePending, jerk)
                    scheduleTick(0L)
                }
            }
        }
        if (settings.gravity) {
            val sx = when (rotation) {
                Surface.ROTATION_90 -> ay
                Surface.ROTATION_180 -> ax
                Surface.ROTATION_270 -> -ay
                else -> -ax
            }
            val sy = when (rotation) {
                Surface.ROTATION_90 -> -ax
                Surface.ROTATION_180 -> -ay
                Surface.ROTATION_270 -> ax
                else -> ay
            }
            val raw = if (sy > 2f) (sx / EARTH_G).coerceIn(-1f, 1f) else 0f
            tilt = tilt * 0.7f + raw * 0.3f
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun applyShake(p: PetInstance, now: Long) {
        val power = shakePending.coerceAtMost(40f)
        shakePending = 0f
        val wasSleeping = p.state == State.SLEEP
        p.state = State.FLY
        p.hopActive = false
        p.hopOffset = 0f
        p.hopVelocity = 0f
        p.slideVelocity = 0f
        val speed = dpFloat(900f + power * 48f)
        val angle = Random.nextFloat() * (Math.PI.toFloat() * 2f)
        p.velocityX = cos(angle) * speed
        p.velocityY = sin(angle) * speed - if (p.settings.gravity) dpFloat(650f) else 0f
        p.shakeDizzy = true
        p.lastInteractionAt = now
        setExpression(p, PetExpression.SURPRISED, 900L)
        haptic(p, 26L)
        if (wasSleeping) say(p, "wake") else if (Random.nextFloat() < 0.6f) say(p, "shake")
    }

    /**
     * Вибрация при ударе о край. Сила удара нормируется в [0..1]
     * относительно ~900dp/s; слабые касания (<0.12) игнорируются.
     */
    private fun edgeVibrate(p: PetInstance, impactSpeedPx: Float) {
        if (!p.settings.edgeVibration) return
        val dpUnit = dpFloat(1f).coerceAtLeast(1f)
        val strength = (impactSpeedPx / dpUnit / 900f).coerceIn(0f, 1f)
        if (strength < 0.12f) return
        try {
            val vibrator = getSystemService(Vibrator::class.java) ?: return
            if (!vibrator.hasVibrator()) return
            val durationMs = (20 + 40 * strength).toLong()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(durationMs, (255 * strength).toInt().coerceIn(1, 255))
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
        } catch (_: Exception) {
        }
    }

    private fun haptic(p: PetInstance, durationMs: Long) {
        if (!p.settings.haptics) return
        try {
            val vibrator = getSystemService(Vibrator::class.java) ?: return
            if (!vibrator.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs.coerceIn(8L, 35L), VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs.coerceIn(8L, 35L))
            }
        } catch (_: Exception) {
            firstPet()?.view?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        }
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density + 0.5f).toInt().coerceAtLeast(1)
    private fun dpFloat(value: Float): Float = value * resources.displayMetrics.density
    private fun randomBetween(min: Long, max: Long): Long = if (max <= min) min else Random.nextLong(min, max)

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Питомец GUPet", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Показывает, что питомец работает поверх приложений"
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, OverlayPetService::class.java).setAction(GUPetContract.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(openIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notification_stop), stopIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "pixpet_overlay"
        private const val NOTIFICATION_ID = 24011
        private const val MAX_PARTICLES = 14
        private const val LONG_PRESS_MS = 420L
        private const val DOUBLE_TAP_MS = 320L
        private const val PEEK_AFTER_MS = 45_000L
        private const val SHAKE_JERK = 14f
        private const val SHAKE_HITS = 4
        private const val SHAKE_WINDOW_MS = 900L
        private const val EARTH_G = 9.81f

        internal val PHRASES = mapOf(
            "hello" to listOf("Привет!", "Я GUPet!", "О, это ты!", "Ну здравствуй, человек", "Я уже заждался", "Давай проведём день вместе", "Мяу! То есть… привет!", "Привет-привет, я на посту!"),
            "idle" to listOf("Чем займёмся?", "Хм…", "Думаю…", "Можно я тут посижу?", "Не забудь попить воды", "Пип-пип!", "*оглядывается*", "Пишу код… шучу", "Интересно, что там дальше", "Я охраняю экран", "Кажется, я слышал шорох", "Кто-нибудь видел мой хвост?", "Пора размять лапки", "Проверка пикселей: всё на месте", "А у тебя уютный экран", "Я считаю облака. Раз… пиксель… два…", "Надо бы устроить маленькое приключение", "Я здесь, если что", "Люблю тихие минутки", "Кажется, день обещает быть хорошим", "Не отвлекайся, я присмотрю за всем", "Хочу чего-нибудь интересного", "*подмигивает*", "Секунду, я красиво сяду", "Какой сегодня чудесный фон!", "Здесь так много места для прогулки", "Пиксель к пикселю — и красота", "У меня лапки, но планы большие", "Ты занят? Я могу подождать", "Пожалуй, немного помурчу"),
            "tap" to listOf("Ой!", "Эй!", "Чего?", "Я тут!", "Хи!", "Поймал!", "Это было неожиданно", "Тук-тук, я отвечаю", "Лапкой привет!", "Не щекочи меня", "Опять ты!", "Ты хотел что-то сказать?"),
            "annoyed" to listOf("Хватит тыкать!", "Щекотно же!", "Ну всё, обиделся", "Эй, полегче!", "Я вообще-то нежный", "Лапки просят передышку", "Ещё раз — и я спрячусь", "Так, это уже третий тык!", "Осторожно, я могу рассмеяться", "Ну ладно, ещё разок"),
            "pet" to listOf("Мрр…", "Ещё!", "Приятно ♥", "Хи-хи", "Лучший день!", "♥♥♥", "Вот это сервис!", "Попал в любимое место", "Я сейчас растаю", "Ты самый лучший", "Мур-мур-мур", "Можно ещё немного?", "Так уютно!", "Вот за это я тебя и люблю"),
            "drag" to listOf("Ааа!", "Куда мы?", "Поставь меня!", "Осторожно, я лечу", "Держи меня покрепче", "Экскурсия без предупреждения?", "Вверх, вниз… вау", "Я не чемодан!", "О, новый вид сверху", "А лапки можно на землю?"),
            "throw" to listOf("Уиии!", "Я лечу!", "Ваааа!", "Полёт нормальный!", "Лапки вверх!", "Это был запуск?", "Становлюсь пиксельной кометой", "Ловиии меня!", "Вот это ускорение!", "Приземлюсь красиво!"),
            "dizzy" to listOf("Голова кружится…", "@_@", "Где я?", "Мир стал квадратным", "Мне нужны минутка и печенька", "Все пиксели поплыли", "Вижу сразу три экрана", "Крутится, вертится…", "Пожалуй, посижу", "Надо было пристегнуться"),
            "wake" to listOf("А? Что?", "Я не спал!", "*зевает*", "Доброе… какое сейчас время?", "Пять минут полежу", "Уже утро?", "Я просто моргал подольше", "Снились рыбки из пикселей", "О, ты вернулся!", "Сейчас проснусь окончательно"),
            "jump" to listOf("Хоп!", "Йе!", "Ура!", "Выше облаков!", "Лапки от земли!", "Пружинка включена", "Ещё один маленький прыжок", "Посмотри, как умею!", "Три, два, прыжок!", "Уиии!"),
            "mail" to listOf("Тебе пишут!", "Сообщение!", "Кто-то написал!", "Динь-динь!", "Пиликнуло!", "Кажется, пришла новость", "Проверь уведомления", "Там кто-то ждёт ответа", "У тебя новое сообщение", "Я услышал цифровой звонок"),
            "love" to listOf("Ааа, мило ♥", "♥♥♥", "Как трогательно!", "Любовь!", "Сердечко принято!", "Отправляю обнимашки", "У меня внутри стало тепло", "Вот это нежность", "Сохраняю в своё пиксельное сердце", "Я тоже тебя люблю ♥"),
            "laugh" to listOf("Ахаха!", "Хи-хи-хи", "Смешно!", "Ха-ха!", "Не могу остановиться!", "Ещё одну шутку!", "Пиксельный юмор — лучший", "Фыр-фыр-фыр", "У меня аж хвост подпрыгнул", "Запомню эту шутку"),
            "hi" to listOf("Привет-привет!", "И тебе привет!", "Здрасьте!", "*машет*", "Хай!", "Рад тебя видеть", "Лапкой машу!", "Салют!", "Привет, мой друг", "Увидел тебя и обрадовался"),
            "music" to listOf("О, музыка!", "Танцуем!", "Это мой трек!", "♪♪♪", "Бит пойман!", "Включаю лапки", "У этой песни отличный ритм", "Подпеваю пикселями", "Музыка — моя стихия", "Давай качать хвостом", "Топ-топ в такт", "О, знакомая мелодия?"),
            "music_end" to listOf("Эх, закончилось", "Ещё!", "Хороший трек", "Можно повтор?", "Тишина тоже неплоха", "Спасибо за концерт", "Я ещё немного потанцую", "Какая песня будет следующей?"),
            "shake" to listOf("Аааа!", "Землетрясение!", "Хватит трясти!", "Уоооу!", "Мир покачнулся", "Проверка на прочность пройдена", "Я почти взлетел", "Так, ставлю лапы шире", "Земля, не уходи!", "Пожалуйста, предупреди в следующий раз"),
            "slide" to listOf("Скользко!", "Уиии~", "Меня несёт!", "Ветер в усах!", "Лапки не тормозят", "Я всё контролирую… почти", "Съезжаю по экрану", "Держись, хвост!"),
            "hang" to listOf("Держусь!", "Помогите!", "Высоковато…", "Не отпущу!", "Лапки крепкие", "Я тут просто выглядываю", "С краешка всё видно", "Осторожно, хвост свисает"),
            "hang_fall" to listOf("Ой-ой-ой!", "Не удержался!", "Падаю!", "Незапланированное приземление", "Приземление мягкое?", "Фух, я в порядке", "Поймал равновесие!"),
            "peek" to listOf("Ку-ку!", "Я тебя вижу", "*подглядывает*", "Ты тут?", "Пик!", "А вот и я", "Выглянул на секунду", "Не искал меня?", "Секретный наблюдатель на связи", "Край экрана — хорошее укрытие"),
            "found" to listOf("Нашёл меня!", "Эх, раскусил", "Я просто смотрел!", "Ладно, сдаюсь", "Как ты догадался?", "Я почти не прятался", "Ты очень внимательный", "Пойман с поличным!"),
            "poop" to listOf("Упс…", "Это не я!", "Ой…", "*краснеет*", "Кажется, тут нужна уборка", "Я оставил маленький сюрприз", "Не наступи, пожалуйста", "Сделаю вид, что так и было", "Всё, теперь легче", "Прости за беспорядок!"),
            "clean" to listOf("Спасибо!", "Прости…", "Больше не буду!", "Ты лучший", "Чистота — красота", "Вот теперь снова уютно", "Уборка прошла успешно", "Спасибо за заботу ♥", "Лапки говорят спасибо", "Порядок восстановлен!"),
            "stretch" to listOf("Потягушки!", "Разминаю каждую лапку", "После прогулки самое то", "Спина говорит спасибо", "Потянулся — и день лучше", "Вот теперь я готов к делам"),
            "yawn" to listOf("Ох, сладко зевнул", "Кажется, пора отдохнуть", "Зевота заразительная", "Ещё не сплю, просто зеваю", "Ммм, как хорошо потянуться", "Сон пока подождёт"),
            "groom" to listOf("Навожу красоту", "Усы на месте, хвост на месте", "Пора умыть лапки", "Кто тут самый пушистый?", "Проверка шерсти завершена", "Красота требует внимания"),
            "spin" to listOf("Кручусь!", "Танцевальный поворот", "Вжух — и другой бок", "Пируэт почти идеальный", "Не кружится? Тогда ещё раз", "Тадам!"),
        )
    }
}
