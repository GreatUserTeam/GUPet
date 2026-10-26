package com.frizovdanya.pixpet

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
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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
    private data class PoopWindow(val view: PoopArtView, val params: WindowManager.LayoutParams, val bornAt: Long)

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var windowManager: WindowManager
    private var settings = PetSettings(
        show = false,
        species = PetSpecies.GUPET,
        palette = 0,
        sizeDp = 4f,
        wander = true,
        phrases = true,
        gravity = false,
        shake = true,
        haptics = true,
        poop = true,
        sleepMinutes = 3,
        reactNotifications = false,
        dance = true,
        customSkinId = null,
        customSkinRevision = 0L,
    )

    private var petView: PetArtView? = null
    private var petParams: WindowManager.LayoutParams? = null
    private var bubbleView: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private val poops = mutableListOf<PoopWindow>()
    private val particles = mutableListOf<PetParticle>()
    private val tick = object : Runnable {
        override fun run() {
            if (!running || petView == null) return
            step()
            if (running && petView != null) handler.postDelayed(this, nextFrameDelay())
        }
    }

    private var receiverRegistered = false
    private val privateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                GUPetContract.ACTION_REACTION -> reactToNotification(intent.getStringExtra(GUPetContract.EXTRA_MOOD) ?: "mail")
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
    private var posX = 0f
    private var posY = 0f
    private var lastLayoutX = Int.MIN_VALUE
    private var lastLayoutY = Int.MIN_VALUE
    private var positionReady = false

    private var state = State.IDLE
    private var touch: TouchTrack? = null
    private var lastTickAt = 0L
    private var lastInteractionAt = 0L
    private var nextActionAt = 0L
    private var nextBlinkAt = 0L
    private var blinkUntil = 0L
    private var nextHeartAt = 0L
    private var nextZAt = 0L
    private var nextNoteAt = 0L
    private var nextPoopAt = 0L
    private var nextPeekAt = 0L
    private var peekEndAt = 0L
    private var peekPhaseUntil = 0L
    private var peekSide = 0
    private var peekOut = false
    private var peekMoving = false
    private var peekPending = 0
    private var waveUntil = 0L
    private var expression = PetExpression.OPEN
    private var expressionUntil = 0L
    private var squashUntil = 0L
    private var mouthOpenUntil = 0L
    private var hopActive = false
    private var hopOffset = 0f
    private var hopVelocity = 0f
    private var velocityX = 0f
    private var velocityY = 0f
    private var slideVelocity = 0f
    private var walkTarget = 0f
    private var look = 0
    private var hangStartedAt = 0L
    private var hangUntil = 0L
    private var shakeDizzy = false
    private var lastTapAt = 0L
    private val recentTaps = mutableListOf<Long>()
    private var nextExternalReactionAt = 0L
    private var lastPhrase: String? = null
    private var idlePose = PetPose.IDLE
    private var idlePoseStartedAt = 0L
    private var idlePoseUntil = 0L
    private var bubbleUntil = 0L
    private var bubbleVisible = false
    private var bubbleFadeIn = false

    override fun onCreate() {
        super.onCreate()
        prefs = PetPrefs.file(this)
        settings = PetPrefs.load(prefs)
        prefs.registerOnSharedPreferenceChangeListener(this)
        configRegistered = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        registerPrivateReceiver()
        resetRuntime()
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
        running = true
        if (petView == null) attachPet()
        if (petView == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (action) {
            GUPetContract.ACTION_POOP_NOW -> poopNow(manual = true)
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
        cleanAllPoops(animated = false, count = false)
        particles.clear()
        hideBubble(animated = false)
        removeOverlay(petView)
        petView = null
        petParams = null
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
        if (!fresh.show) {
            stopSelf()
            return
        }
        val view = petView
        if (view != null) {
            view.species = fresh.species
            view.paletteIndex = fresh.palette
            if (old.customSkinId != fresh.customSkinId || old.customSkinRevision != fresh.customSkinRevision) {
                view.customSkin = GUPetSkinStore.load(this, fresh.customSkinId)
            }
            if (old.sizeDp != fresh.sizeDp) resizePetWindow()
        }
        if (old.poop && !fresh.poop) cleanAllPoops(animated = true, count = false)
        if (fresh.poop && !old.poop) nextPoopAt = SystemClock.elapsedRealtime() + randomBetween(90_000L, 210_000L)
        if (fresh.gravity && !old.gravity && (state == State.IDLE || state == State.WALK || state == State.SLEEP) && posY < maxY - dp(2f)) {
            velocityX = 0f
            velocityY = 0f
            state = State.FLY
        }
        if (!fresh.phrases) hideBubble()
        if (state == State.SLEEP && (fresh.sleepMinutes == 0 || SystemClock.elapsedRealtime() - lastInteractionAt < fresh.sleepMinutes * 60_000L)) {
            state = State.IDLE
            setExpression(PetExpression.SURPRISED, 750L)
        }
        if (!fresh.gravity) slideVelocity = 0f
        registerSensorIfNeeded()
        updateMediaMonitor()
        petView?.invalidate()
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

    private fun attachPet() {
        if (petView != null) return
        unitPx = dpFloat(settings.sizeDp)
        windowWidth = (unitPx * 18f).toInt().coerceAtLeast(dp(60f))
        windowHeight = (unitPx * 17f).toInt().coerceAtLeast(dp(52f))
        refreshBounds(force = true)
        val view = PetArtView(this).apply {
            species = settings.species
            paletteIndex = settings.palette
            customSkin = GUPetSkinStore.load(this@OverlayPetService, settings.customSkinId)
            contentDescription = "Питомец GUPet"
            setOnClickListener { onTap(SystemClock.elapsedRealtime()) }
            setOnTouchListener { touchedView, event ->
                val isShortTap = event.actionMasked == MotionEvent.ACTION_UP && state == State.PRESS &&
                    SystemClock.elapsedRealtime() - (touch?.downAt ?: SystemClock.elapsedRealtime()) < LONG_PRESS_MS
                val handled = onPetTouch(event)
                if (isShortTap) touchedView.performClick()
                handled
            }
        }
        val params = overlayParams(windowWidth, windowHeight, touchable = true)
        try {
            windowManager.addView(view, params)
        } catch (_: Exception) {
            prefs.edit().putBoolean(PetPrefs.SHOW, false).apply()
            stopSelf()
            return
        }
        petView = view
        petParams = params
        if (!positionReady) restorePosition()
        updatePetLayout(force = true)
        running = true
        val now = SystemClock.elapsedRealtime()
        if (settings.phrases) showBubble("hello")
        lastInteractionAt = now
        nextActionAt = now + 3_000L
        nextPoopAt = now + randomBetween(150_000L, 330_000L)
        registerSensorIfNeeded()
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
        if (changed && petView != null) {
            if (!positionReady) restorePosition()
            else if (state != State.PEEK) {
                posX = posX.coerceIn(minX, maxX)
                posY = posY.coerceIn(minY, maxY)
            }
            updatePetLayout(force = true)
        }
    }

    private fun systemDimension(name: String): Int {
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id != 0) try { resources.getDimensionPixelSize(id) } catch (_: Exception) { 0 } else 0
    }

    private fun restorePosition() {
        val fx = (prefs.getInt(PetPrefs.POS_X, 780) / 1_000f).coerceIn(0f, 1f)
        val fy = (prefs.getInt(PetPrefs.POS_Y, 720) / 1_000f).coerceIn(0f, 1f)
        posX = minX + (maxX - minX) * fx
        posY = minY + (maxY - minY) * fy
        positionReady = true
    }

    private fun savePosition() {
        val fx = if (maxX > minX) ((posX - minX) / (maxX - minX)).coerceIn(0f, 1f) else 0.5f
        val fy = if (maxY > minY) ((posY - minY) / (maxY - minY)).coerceIn(0f, 1f) else 0.6f
        prefs.edit()
            .putInt(PetPrefs.POS_X, (fx * 1_000).toInt())
            .putInt(PetPrefs.POS_Y, (fy * 1_000).toInt())
            .apply()
    }

    private fun resizePetWindow() {
        val view = petView ?: return
        val params = petParams ?: return
        val oldWidth = windowWidth
        val center = posX + oldWidth / 2f
        unitPx = dpFloat(settings.sizeDp)
        windowWidth = (unitPx * 18f).toInt().coerceAtLeast(dp(60f))
        windowHeight = (unitPx * 17f).toInt().coerceAtLeast(dp(52f))
        params.width = windowWidth
        params.height = windowHeight
        view.layoutParams = params
        refreshBounds(force = true)
        posX = (center - windowWidth / 2f).coerceIn(minX, maxX)
        posY = posY.coerceIn(minY, maxY)
        updatePetLayout(force = true)
    }

    private fun updatePetLayout(force: Boolean = false) {
        val view = petView ?: return
        val params = petParams ?: return
        val nextX = posX.toInt()
        val nextY = posY.toInt()
        if (!force && nextX == lastLayoutX && nextY == lastLayoutY) return
        params.x = nextX
        params.y = nextY
        try {
            windowManager.updateViewLayout(view, params)
            lastLayoutX = nextX
            lastLayoutY = nextY
        } catch (_: Exception) { }
    }

    private fun removeOverlay(view: View?) {
        if (view == null) return
        try { view.animate().cancel() } catch (_: Exception) { }
        try { windowManager.removeViewImmediate(view) } catch (_: Exception) { }
    }

    private fun setPosition(x: Float, y: Float, allowOffscreenX: Boolean = false) {
        posX = if (allowOffscreenX) x else x.coerceIn(minX, maxX)
        posY = y.coerceIn(minY - dp(3f), maxY)
        updatePetLayout()
    }

    private fun scheduleTick(delayMs: Long) {
        if (!running || petView == null) return
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, delayMs.coerceAtLeast(0L))
    }

    private fun nextFrameDelay(): Long {
        val now = SystemClock.elapsedRealtime()
        val moving = state == State.PRESS || state == State.DRAG || state == State.PET || state == State.FLY ||
            state == State.WALK || state == State.HANG || state == State.PEEK || hopActive || mediaPlaying ||
            shakePending > 0f || particles.isNotEmpty() || now < squashUntil
        return when {
            moving -> 33L
            state == State.SLEEP -> 420L
            else -> 120L
        }
    }

    private fun resetRuntime() {
        val now = SystemClock.elapsedRealtime()
        state = State.IDLE
        touch = null
        lastTickAt = now
        lastInteractionAt = now
        nextActionAt = now + 3_500L
        idlePose = PetPose.IDLE
        idlePoseStartedAt = 0L
        idlePoseUntil = 0L
        nextBlinkAt = now + 2_000L
        blinkUntil = 0L
        nextHeartAt = now
        nextZAt = now + 700L
        nextNoteAt = now + 400L
        nextPoopAt = now + randomBetween(150_000L, 330_000L)
        nextPeekAt = now + 45_000L
        waveUntil = 0L
        expression = PetExpression.OPEN
        expressionUntil = 0L
        squashUntil = 0L
        mouthOpenUntil = 0L
        hopActive = false
        hopOffset = 0f
        hopVelocity = 0f
        velocityX = 0f
        velocityY = 0f
        slideVelocity = 0f
        look = 0
        peekSide = 0
        peekPending = 0
        peekOut = false
        peekMoving = false
        shakeDizzy = false
        lastTapAt = 0L
        recentTaps.clear()
        particles.clear()
    }

    private fun step() {
        val now = SystemClock.elapsedRealtime()
        refreshBounds()
        val dt = ((now - lastTickAt).coerceAtLeast(0L) / 1_000f).coerceIn(0f, 0.05f)
        lastTickAt = now
        val track = touch
        if (state == State.PRESS && track != null && now - track.downAt >= LONG_PRESS_MS) beginPet(now)
        if (shakePending > 0f && state != State.PRESS && state != State.DRAG && state != State.PET) applyShake(now)

        when (state) {
            State.FLY -> stepFlight(dt, now)
            State.HANG -> stepHang(now)
            State.PEEK -> stepPeek(dt, now)
            State.PET -> {
                if (now >= nextHeartAt) {
                    nextHeartAt = now + 450L
                    spawnParticle(ParticleKind.HEART)
                    haptic(18L)
                }
            }
            State.IDLE, State.WALK, State.SLEEP -> stepGround(dt, now)
            State.PRESS, State.DRAG -> Unit
        }

        if (hopActive) {
            hopOffset += hopVelocity * dt
            hopVelocity += dpFloat(2_500f) * dt
            if (hopOffset >= 0f) {
                hopOffset = 0f
                hopVelocity = 0f
                hopActive = false
                squashUntil = now + 110L
            }
        }
        updateParticles(now)
        render(now)
        updateBubble(now)
    }

    private fun stepGround(dt: Float, now: Long) {
        if (settings.gravity && state != State.SLEEP && posY < maxY - dp(1f) && !hopActive) {
            state = State.FLY
            velocityX = 0f
            velocityY = 0f
            return
        }
        if (!settings.gravity) {
            posY = posY.coerceIn(minY, maxY)
            slideVelocity = 0f
        } else if (posY < maxY - dp(1f) && !hopActive) {
            state = State.FLY
            velocityY = 0f
            return
        } else {
            posY = min(posY, maxY)
            slideVelocity += tilt * dpFloat(1_000f) * dt
            slideVelocity *= kotlin.math.exp(-2.6f * dt)
            if (abs(slideVelocity) < dpFloat(7f)) slideVelocity = 0f
            if (slideVelocity != 0f) {
                posX += slideVelocity * dt
                if (posX < minX || posX > maxX) {
                    posX = posX.coerceIn(minX, maxX)
                    slideVelocity = 0f
                } else if (abs(slideVelocity) > dpFloat(55f) && state == State.SLEEP) {
                    state = State.IDLE
                    setExpression(PetExpression.SURPRISED, 800L)
                }
                if (abs(slideVelocity) > dpFloat(60f)) {
                    state = State.IDLE
                    look = if (slideVelocity > 0) 1 else -1
                }
            }
        }

        if (state == State.WALK) {
            val direction = if (walkTarget >= posX) 1 else -1
            look = direction
            posX += direction * dpFloat(42f) * dt
            val reached = if (direction > 0) posX >= walkTarget else posX <= walkTarget
            val edge = (direction < 0 && posX <= minX) || (direction > 0 && posX >= maxX)
            if (reached || edge) {
                posX = posX.coerceIn(minX, maxX)
                state = State.IDLE
                nextActionAt = now + randomBetween(2_500L, 6_000L)
                val side = peekPending
                peekPending = 0
                if (side != 0 && ((side < 0 && posX <= minX + dp(4f)) || (side > 0 && posX >= maxX - dp(4f)))) {
                    startPeek(now, side)
                } else savePosition()
            }
            return
        }

        if (state == State.SLEEP) {
            if (now >= nextZAt) {
                nextZAt = now + 1_650L
                spawnParticle(ParticleKind.ZZZ)
            }
            return
        }

        if (now >= nextBlinkAt) {
            blinkUntil = now + 130L
            nextBlinkAt = now + if (Random.nextFloat() < 0.15f) 450L else randomBetween(2_000L, 5_500L)
        }

        if (settings.sleepMinutes > 0 && now - lastInteractionAt > settings.sleepMinutes * 60_000L &&
            !hopActive && !mediaPlaying && state == State.IDLE) {
            state = State.SLEEP
            look = 0
            nextZAt = now + 500L
            hideBubble()
            return
        }

        if (settings.poop && now >= nextPoopAt && state == State.IDLE && !hopActive) {
            poopNow(manual = false)
            return
        }

        if (mediaPlaying && settings.dance) {
            if (now >= nextNoteAt) {
                nextNoteAt = now + 1_050L
                spawnParticle(ParticleKind.NOTE)
            }
            return
        }

        if (now < nextActionAt || hopActive) return
        nextActionAt = now + randomBetween(3_500L, 9_000L)
        if (!settings.wander) {
            when (Random.nextInt(100)) {
                in 0..18 -> look = Random.nextInt(-1, 2)
                in 19..40 -> say("idle")
                in 41..56 -> hop(dpFloat(280f))
                in 57..66 -> waveUntil = now + 1_600L
                in 67..75 -> playIdlePose(PetPose.STRETCH, 1_400L, "stretch", now)
                in 76..84 -> playIdlePose(PetPose.YAWN, 1_200L, "yawn", now)
                in 85..93 -> playIdlePose(PetPose.GROOM, 1_800L, "groom", now)
                else -> playIdlePose(PetPose.SPIN, 900L, "spin", now)
            }
            return
        }

        val idleLongEnough = now - lastInteractionAt > PEEK_AFTER_MS
        if (idleLongEnough && now >= nextPeekAt && Random.nextFloat() < 0.22f) {
            val side = if (posX - minX < maxX - posX) -1 else 1
            peekPending = side
            walkTarget = if (side < 0) minX else maxX
            state = State.WALK
            return
        }

        when (Random.nextInt(100)) {
            in 0..28 -> {
                val distance = dpFloat(Random.nextInt(45, 155).toFloat()) * if (Random.nextBoolean()) 1f else -1f
                walkTarget = (posX + distance).coerceIn(minX, maxX)
                if (abs(walkTarget - posX) > dpFloat(12f)) state = State.WALK
            }
            in 29..39 -> look = Random.nextInt(-1, 2)
            in 40..53 -> say("idle")
            in 54..67 -> hop(dpFloat(330f))
            in 68..75 -> waveUntil = now + 1_600L
            in 76..81 -> playIdlePose(PetPose.STRETCH, 1_400L, "stretch", now)
            in 82..87 -> playIdlePose(PetPose.YAWN, 1_200L, "yawn", now)
            in 88..93 -> playIdlePose(PetPose.GROOM, 1_800L, "groom", now)
            else -> playIdlePose(PetPose.SPIN, 900L, "spin", now)
        }
    }

    private fun playIdlePose(pose: PetPose, durationMs: Long, phrase: String, now: Long) {
        idlePose = pose
        idlePoseStartedAt = now
        idlePoseUntil = now + durationMs
        nextActionAt = max(nextActionAt, now + durationMs + randomBetween(1_500L, 3_000L))
        say(phrase)
    }

    private fun stepFlight(dt: Float, now: Long) {
        posX += velocityX * dt
        posY += velocityY * dt
        if (settings.gravity) {
            velocityY += dpFloat(2_450f) * dt
            velocityX *= kotlin.math.exp(-0.32f * dt)
            velocityX += tilt * dpFloat(900f) * dt
        } else {
            val friction = kotlin.math.exp(-1.9f * dt)
            velocityX *= friction
            velocityY *= friction
        }
        if (posX < minX) {
            posX = minX
            velocityX = abs(velocityX) * 0.55f
        } else if (posX > maxX) {
            posX = maxX
            velocityX = -abs(velocityX) * 0.55f
        }

        if (posY < minY) {
            if (velocityY < -dpFloat(260f) && !shakeDizzy) {
                startHang(now)
                return
            }
            posY = minY
            velocityY = abs(velocityY) * 0.55f
        } else if (posY > maxY) {
            posY = maxY
            if (settings.gravity && velocityY < dpFloat(390f)) {
                land(now)
            } else {
                velocityY = -abs(velocityY) * if (settings.gravity) 0.42f else 0.55f
                velocityX *= 0.78f
                squashUntil = now + 120L
            }
        }

        if (state == State.FLY && !settings.gravity && hypot(velocityX, velocityY) < dpFloat(42f)) land(now)
        if (shakeDizzy && now - lastInteractionAt > 1_200L && state == State.FLY && hypot(velocityX, velocityY) < dpFloat(160f)) {
            shakeDizzy = false
            setExpression(PetExpression.DIZZY, 2_000L)
            say("dizzy")
        }
    }

    private fun land(now: Long) {
        state = State.IDLE
        velocityX = 0f
        velocityY = 0f
        squashUntil = now + 140L
        nextActionAt = now + randomBetween(2_000L, 5_000L)
        if (shakeDizzy) {
            shakeDizzy = false
            setExpression(PetExpression.DIZZY, 2_500L)
            say("dizzy")
        }
        savePosition()
    }

    private fun startHang(now: Long) {
        state = State.HANG
        velocityX = 0f
        velocityY = 0f
        posY = minY - unitPx * 1.5f
        hangStartedAt = now
        hangUntil = now + randomBetween(2_000L, 3_500L)
        haptic(22L)
        if (Random.nextFloat() < 0.7f) say("hang")
    }

    private fun stepHang(now: Long) {
        posX = posX.coerceIn(minX, maxX)
        posY = minY - unitPx * 1.5f
        if (now < hangUntil) return
        state = State.FLY
        velocityX = 0f
        velocityY = dpFloat(if (settings.gravity) 160f else 650f)
        setExpression(PetExpression.SURPRISED, 700L)
        haptic(16L)
        if (Random.nextFloat() < 0.6f) say("hang_fall")
    }

    private fun stepPeek(dt: Float, now: Long) {
        val hiddenUnits = 3.2f
        val visibleUnits = 7f
        val targetX = if (peekSide > 0) {
            screenWidth - unitPx * if (peekOut) visibleUnits else hiddenUnits
        } else {
            -windowWidth + unitPx * if (peekOut) visibleUnits else hiddenUnits
        }
        val delta = targetX - posX
        val step = dpFloat(52f) * dt
        peekMoving = abs(delta) > 1f
        if (peekMoving) setPosition(posX + delta.coerceIn(-step, step), posY, allowOffscreenX = true)
        look = if (!peekOut && peekMoving) peekSide else -peekSide
        if (now < peekPhaseUntil || peekMoving) return
        if (now >= peekEndAt) {
            endPeek(now)
            return
        }
        if (!peekOut) {
            peekOut = true
            peekPhaseUntil = now + randomBetween(1_400L, 2_800L)
            if (Random.nextFloat() < 0.55f) say("peek")
        } else {
            peekOut = false
            peekPhaseUntil = now + randomBetween(3_000L, 7_000L)
        }
    }

    private fun startPeek(now: Long, side: Int) {
        state = State.PEEK
        peekSide = side
        peekOut = false
        peekMoving = true
        peekPhaseUntil = now + randomBetween(2_500L, 5_000L)
        peekEndAt = now + randomBetween(25_000L, 45_000L)
        look = side
    }

    private fun endPeek(now: Long) {
        val side = peekSide
        peekSide = 0
        peekMoving = false
        nextPeekAt = now + randomBetween(60_000L, 120_000L)
        state = State.WALK
        walkTarget = if (side < 0) minX + dpFloat(30f) else maxX - dpFloat(30f)
        nextActionAt = now + randomBetween(3_000L, 6_000L)
    }

    private fun onPetTouch(event: MotionEvent): Boolean {
        val now = SystemClock.elapsedRealtime()
        val rawX = event.rawX
        val rawY = event.rawY
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val wasSleeping = state == State.SLEEP
                val wasPeeking = state == State.PEEK
                lastInteractionAt = now
                val track = TouchTrack(
                    downX = rawX,
                    downY = rawY,
                    downAt = now,
                    offsetX = rawX - posX,
                    offsetY = rawY - posY,
                    anchorX = rawX,
                )
                track.samples.add(TouchPoint(now, rawX, rawY))
                touch = track
                hopActive = false
                hopOffset = 0f
                hopVelocity = 0f
                velocityX = 0f
                velocityY = 0f
                slideVelocity = 0f
                peekSide = 0
                peekPending = 0
                state = State.PRESS
                if (wasSleeping) {
                    setExpression(PetExpression.SURPRISED, 800L)
                    say("wake")
                } else if (wasPeeking) {
                    nextPeekAt = now + randomBetween(60_000L, 120_000L)
                    setExpression(PetExpression.SURPRISED, 650L)
                    say("found")
                }
                scheduleTick(0L)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val track = touch ?: return false
                addTouchSample(track, now, rawX, rawY)
                if (state == State.PRESS && hypot(rawX - track.downX, rawY - track.downY) > dpFloat(8f)) {
                    state = State.DRAG
                    peekPending = 0
                    haptic(12L)
                    if (Random.nextFloat() < 0.25f) say("drag")
                }
                if (state == State.DRAG) {
                    setPosition(rawX - track.offsetX, rawY - track.offsetY)
                    val first = track.samples.firstOrNull()
                    if (first != null && now - first.timeMs > 5L) track.velocityX = (rawX - first.x) / ((now - first.timeMs) / 1_000f)
                } else if (state == State.PET) {
                    val dx = rawX - track.anchorX
                    if (abs(dx) > dpFloat(10f)) {
                        val direction = if (dx > 0f) 1 else -1
                        if (track.direction != 0 && direction != track.direction) spawnParticle(ParticleKind.HEART)
                        track.direction = direction
                        track.anchorX = rawX
                    }
                }
                scheduleTick(0L)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val track = touch ?: return false
                touch = null
                lastInteractionAt = now
                val cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL
                when (state) {
                    State.DRAG -> releaseDrag(now, track, cancelled)
                    State.PET -> endPet(now)
                    State.PRESS -> {
                        if (!cancelled && now - track.downAt >= LONG_PRESS_MS) {
                            beginPet(now)
                            endPet(now)
                        } else {
                            state = State.IDLE
                        }
                    }
                    else -> state = State.IDLE
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

    private fun releaseDrag(now: Long, track: TouchTrack, cancelled: Boolean) {
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
        if (!cancelled && abs(vy) > dpFloat(700f) && posY <= minY + unitPx * 2f) {
            startHang(now)
        } else if (!cancelled && (hypot(vx, vy) > dpFloat(700f) || settings.gravity)) {
            state = State.FLY
            velocityX = vx
            velocityY = vy
            if (hypot(vx, vy) > dpFloat(2_500f)) say("throw")
        } else {
            state = State.IDLE
            squashUntil = now + 130L
            nextActionAt = now + randomBetween(2_000L, 5_000L)
            savePosition()
        }
    }

    private fun onTap(now: Long) {
        if (now - lastTapAt < DOUBLE_TAP_MS) {
            lastTapAt = 0L
            hop(dpFloat(720f))
            setExpression(PetExpression.HAPPY, 1_300L)
            repeat(3) { spawnParticle(ParticleKind.SPARK) }
            haptic(14L)
            if (Random.nextFloat() < 0.4f) say("jump")
            return
        }
        lastTapAt = now
        while (recentTaps.isNotEmpty() && now - recentTaps.first() > 2_500L) recentTaps.removeAt(0)
        recentTaps.add(now)
        if (recentTaps.size >= 6) {
            recentTaps.clear()
            setExpression(PetExpression.ANGRY, 2_000L)
            say("annoyed", force = true)
            haptic(18L)
            return
        }
        squashUntil = now + 130L
        setExpression(PetExpression.SURPRISED, 450L)
        hop(dpFloat(320f))
        haptic(12L)
        if (Random.nextFloat() < 0.35f) say("tap")
    }

    private fun beginPet(now: Long) {
        if (state != State.PRESS && state != State.PET) return
        state = State.PET
        nextHeartAt = now + 100L
        haptic(12L)
    }

    private fun endPet(now: Long) {
        state = State.IDLE
        setExpression(PetExpression.HAPPY, 2_200L)
        nextActionAt = now + randomBetween(3_000L, 6_000L)
        val count = prefs.getInt(PetPrefs.PETS_COUNT, 0) + 1
        prefs.edit().putInt(PetPrefs.PETS_COUNT, count).apply()
        if (Random.nextFloat() < 0.65f) say("pet")
    }

    private fun hop(strengthPx: Float) {
        if (state != State.IDLE && state != State.WALK) return
        hopActive = true
        hopVelocity = -strengthPx
        if (hopOffset > 0f) hopOffset = 0f
    }

    private fun setExpression(value: PetExpression, durationMs: Long) {
        expression = value
        expressionUntil = SystemClock.elapsedRealtime() + durationMs
    }

    private fun recenter() {
        if (petView == null) return
        refreshBounds(force = true)
        posX = (minX + maxX) / 2f
        posY = (minY + maxY) / 2f
        state = State.IDLE
        velocityX = 0f
        velocityY = 0f
        slideVelocity = 0f
        peekSide = 0
        peekPending = 0
        lastInteractionAt = SystemClock.elapsedRealtime()
        updatePetLayout(force = true)
        hop(dpFloat(500f))
        repeat(3) { spawnParticle(ParticleKind.SPARK) }
        say("hello")
        savePosition()
        scheduleTick(0L)
    }

    private fun spawnParticle(kind: ParticleKind, originX: Float? = null, originY: Float? = null) {
        if (petView == null) return
        while (particles.size >= MAX_PARTICLES) particles.removeAt(0)
        val now = SystemClock.elapsedRealtime()
        val width = windowWidth.toFloat()
        val cell = unitPx * 0.72f
        val x = originX ?: (width / 2f - cell * 3f + Random.nextFloat() * cell * 5f)
        val y = originY ?: when (kind) {
            ParticleKind.ZZZ -> unitPx * 4.2f
            else -> unitPx * 3.1f
        }
        particles.add(
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

    private fun updateParticles(now: Long) {
        val iterator = particles.iterator()
        while (iterator.hasNext()) {
            val particle = iterator.next()
            if (now - particle.bornAtMs >= particle.durationMs) iterator.remove()
        }
    }

    private fun poopNow(manual: Boolean) {
        if ((!manual && !settings.poop) || petView == null) return
        val now = SystemClock.elapsedRealtime()
        nextPoopAt = now + randomBetween(150_000L, 360_000L)
        if (poops.size >= MAX_POOPS) {
            if (!manual) return
            removePoop(poops.first(), animated = false, count = false)
        }
        val poopUnit = max(dp(3f).toFloat(), unitPx * 0.9f)
        val poopWidth = (poopUnit * 9f).toInt()
        val poopHeight = (poopUnit * 7f).toInt()
        val side = if (look == 0) if (Random.nextBoolean()) -1 else 1 else -look
        val centerX = posX + windowWidth / 2f + side * windowWidth * 0.42f
        val px = (centerX - poopWidth / 2f).coerceIn(0f, max(0, screenWidth - poopWidth).toFloat())
        val py = (posY + windowHeight - poopHeight).coerceIn(minY, max(minY, (screenHeight - safeBottom - poopHeight).toFloat()))
        lateinit var view: PoopArtView
        view = PoopArtView(this) { removePoopByView(view) }
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
        repeat(2) { spawnParticle(ParticleKind.SPARK) }
        squashUntil = now + 180L
        setExpression(PetExpression.HAPPY, 1_700L)
        haptic(18L)
        say("poop")
        look = -side
        if (settings.wander && state == State.IDLE) {
            val target = (posX - side * dpFloat(Random.nextInt(40, 80).toFloat())).coerceIn(minX, maxX)
            if (abs(target - posX) > dpFloat(12f)) {
                walkTarget = target
                state = State.WALK
            }
        }
        nextActionAt = now + randomBetween(3_000L, 6_000L)
        scheduleTick(0L)
    }

    private fun removePoopByView(view: View) {
        val entry = poops.firstOrNull { it.view === view } ?: return
        removePoop(entry, animated = true, count = true)
    }

    private fun removePoop(entry: PoopWindow, animated: Boolean, count: Boolean) {
        if (!poops.remove(entry)) return
        entry.view.setOnClickListener(null)
        if (count) {
            val total = prefs.getInt(PetPrefs.POOPS_CLEANED, 0) + 1
            prefs.edit().putInt(PetPrefs.POOPS_CLEANED, total).apply()
            repeat(3) { spawnParticle(ParticleKind.SPARK) }
            haptic(16L)
            if (settings.phrases && Random.nextFloat() < 0.7f) say("clean")
        }
        if (animated) {
            entry.view.animate()
                .scaleX(0.05f).scaleY(0.05f).alpha(0f).rotation(90f)
                .setDuration(250L)
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

    private fun showBubble(category: String, force: Boolean = false) {
        if (!force && !settings.phrases) return
        val options = PHRASES[category] ?: listOf(category)
        val filtered = if (options.size > 1) options.filter { it != lastPhrase } else options
        val phrase = (filtered.ifEmpty { options }).random()
        lastPhrase = phrase
        showBubbleText(phrase)
    }

    private fun say(category: String, force: Boolean = false) = showBubble(category, force)

    private fun showBubbleText(text: String) {
        val now = SystemClock.elapsedRealtime()
        val view = bubbleView ?: createBubbleView().also { bubbleView = it }
        view.animate().cancel()
        view.text = text
        view.alpha = 0f
        view.visibility = View.VISIBLE
        bubbleVisible = true
        bubbleFadeIn = true
        bubbleUntil = now + 2_000L + text.length * 50L
        measureAndAddBubble(view)
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

    private fun measureAndAddBubble(view: TextView) {
        val maxWidthPx = dp(220f)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(maxWidthPx, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val width = view.measuredWidth.coerceAtLeast(dp(42f))
        val height = view.measuredHeight.coerceAtLeast(dp(30f))
        val params = bubbleParams ?: overlayParams(width, height, touchable = false).apply {
            alpha = 0.78f
            title = "GUPet — реплика"
        }.also { bubbleParams = it }
        params.width = width
        params.height = height
        if (view.parent == null) {
            try {
                windowManager.addView(view, params)
            } catch (_: Exception) {
                bubbleView = null
                bubbleParams = null
                return
            }
        } else {
            try { windowManager.updateViewLayout(view, params) } catch (_: Exception) { }
        }
        positionBubble()
    }

    private fun positionBubble() {
        val view = bubbleView ?: return
        val params = bubbleParams ?: return
        if (view.width <= 0 && view.measuredWidth <= 0) return
        val width = if (view.width > 0) view.width else view.measuredWidth
        val height = if (view.height > 0) view.height else view.measuredHeight
        val x = (posX + windowWidth / 2f - width / 2f).coerceIn(0f, max(0, screenWidth - width).toFloat())
        var y = posY + unitPx * 1.5f - height - dp(4f)
        if (y < safeTop) y = posY + windowHeight + dp(4f)
        y = y.coerceIn(0f, max(0, screenHeight - height).toFloat())
        val ix = x.toInt()
        val iy = y.toInt()
        if (params.x == ix && params.y == iy) return
        params.x = ix
        params.y = iy
        try { windowManager.updateViewLayout(view, params) } catch (_: Exception) { }
    }

    private fun updateBubble(now: Long) {
        if (!bubbleVisible) return
        if (now >= bubbleUntil) {
            hideBubble()
            return
        }
        positionBubble()
        if (bubbleFadeIn) {
            bubbleFadeIn = false
            bubbleView?.animate()?.alpha(1f)?.setDuration(150L)?.start()
        }
    }

    private fun hideBubble(animated: Boolean = true) {
        val view = bubbleView ?: return
        bubbleVisible = false
        bubbleFadeIn = false
        bubbleView = null
        bubbleParams = null
        if (!animated) {
            removeOverlay(view)
            return
        }
        view.animate().alpha(0f).setDuration(180L).withEndAction { removeOverlay(view) }.start()
    }

    private fun render(now: Long) {
        val view = petView ?: return
        val pose = when (state) {
            State.WALK -> PetPose.WALK
            State.PRESS -> PetPose.PRESS
            State.DRAG -> PetPose.DRAG
            State.PET -> PetPose.PET
            State.FLY -> PetPose.FLY
            State.SLEEP -> PetPose.SLEEP
            State.HANG -> PetPose.HANG
            State.PEEK -> PetPose.PEEK
            State.IDLE -> when {
                mediaPlaying && settings.dance -> musicPose(now)
                now < idlePoseUntil -> idlePose
                else -> PetPose.IDLE
            }
        }
        var face = when {
            state == State.SLEEP -> PetExpression.SLEEP
            now < expressionUntil -> expression
            now < blinkUntil && (state == State.IDLE || state == State.WALK || state == State.PEEK || state == State.HANG) -> PetExpression.BLINK
            else -> PetExpression.OPEN
        }
        var waveSide = 0
        if (pose == PetPose.PEEK && peekOut && !peekMoving && (now / 220L) % 2L == 0L) waveSide = if (peekSide < 0) -1 else 1
        if (now < waveUntil && (now / 240L) % 2L == 0L) waveSide = if ((now / 240L) % 4L == 0L) -1 else 1
        if (mediaPlaying && settings.dance && state == State.IDLE) {
            face = PetExpression.HAPPY
            waveSide = if ((now / 240L) % 2L == 0L) -1 else 1
        }
        var rotation = 0f
        when {
            state == State.FLY -> rotation = (velocityX / dpFloat(70f)).coerceIn(-25f, 25f)
            state == State.HANG -> rotation = (sin((now - hangStartedAt) / 1_000f * 3f) * 6f)
            pose == PetPose.SPIN && mediaPlaying && state == State.IDLE -> rotation = ((now % 2_400L) / 2_400f) * 360f
            pose == PetPose.SPIN && now < idlePoseUntil -> rotation = (((now - idlePoseStartedAt).coerceAtLeast(0L) % 900L) / 900f) * 360f
            slideVelocity != 0f -> rotation = (-slideVelocity / dpFloat(30f)).coerceIn(-12f, 12f)
            expression == PetExpression.DIZZY && now < expressionUntil -> rotation = sin(now / 100f) * 7f
            mediaPlaying && state == State.IDLE -> rotation = sin(now / 250f) * 5f
            state == State.DRAG && touch != null -> rotation = (touch?.velocityX ?: 0f).div(dpFloat(90f)).coerceIn(-18f, 18f)
        }
        val squash = when {
            hopActive && hopVelocity < 0f -> -1f
            now < squashUntil -> 1f
            else -> 0f
        }
        view.frame = PetFrame(
            pose = pose,
            expression = face,
            look = look,
            waveSide = waveSide,
            blush = state == State.PET || face == PetExpression.HAPPY || pose == PetPose.DANCE,
            mouthOpen = now < mouthOpenUntil || (state == State.PET && (now / 180L) % 2L == 0L),
            squash = squash,
            rotation = rotation,
            hopOffset = hopOffset,
            phase = now / 1_000f,
        )
        view.frameTimeMs = now
        view.particles = particles
        updatePetLayout()
    }

    private fun reactToNotification(mood: String) {
        if (!settings.reactNotifications || !running || petView == null) return
        val now = SystemClock.elapsedRealtime()
        if (now < nextExternalReactionAt || (state != State.IDLE && state != State.WALK && state != State.SLEEP && state != State.PEEK)) return
        nextExternalReactionAt = now + if (mood == "mail") 5_000L else 3_000L
        lastInteractionAt = now
        if (state == State.SLEEP) state = State.IDLE
        if (state == State.PEEK) endPeek(now)
        when (mood) {
            "love" -> {
                setExpression(PetExpression.HAPPY, 2_000L)
                repeat(3) { spawnParticle(ParticleKind.HEART) }
                say("love")
            }
            "laugh" -> {
                setExpression(PetExpression.HAPPY, 1_500L)
                hop(dpFloat(400f))
                spawnParticle(ParticleKind.SPARK)
                say("laugh")
            }
            "hi" -> {
                waveUntil = now + 1_800L
                say("hi")
            }
            else -> {
                look = if (Random.nextBoolean()) 1 else -1
                setExpression(PetExpression.SURPRISED, 700L)
                hop(dpFloat(290f))
                say("mail")
            }
        }
        nextActionAt = max(nextActionAt, now + 2_500L)
        scheduleTick(0L)
    }

    private val mediaRetry = object : Runnable {
        override fun run() {
            if (running && settings.dance) updateMediaMonitor()
        }
    }

    private fun hasMediaSessionAccess(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        val expected = ComponentName(this, PetNotificationListenerService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
    }

    private fun updateMediaMonitor() {
        if (!running || !settings.dance || !hasMediaSessionAccess()) {
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
        if (!running || !settings.dance || !hasMediaSessionAccess() || mediaRetryCount >= 8) return
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
        if (!running || mediaPlaying == playing || (playing && !settings.dance)) return
        mediaPlaying = playing
        val now = SystemClock.elapsedRealtime()
        if (playing) {
            lastInteractionAt = now
            nextNoteAt = now + 250L
            idlePoseUntil = 0L
            idlePose = PetPose.IDLE
            if (state == State.SLEEP) state = State.IDLE
            if (state == State.PEEK) endPeek(now)
            say("music")
        } else {
            idlePoseUntil = 0L
            idlePose = PetPose.IDLE
            nextActionAt = now + randomBetween(2_000L, 4_000L)
            if (Random.nextFloat() < 0.7f) say("music_end")
        }
        scheduleTick(0L)
    }

    private fun registerSensorIfNeeded() {
        val shouldListen = running && (settings.gravity || settings.shake)
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
        if (!running || petView == null) return
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
        if (settings.shake && hadPrevious && state != State.PRESS && state != State.DRAG && state != State.PET) {
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

    private fun applyShake(now: Long) {
        val power = shakePending.coerceAtMost(40f)
        shakePending = 0f
        val wasSleeping = state == State.SLEEP
        state = State.FLY
        hopActive = false
        hopOffset = 0f
        hopVelocity = 0f
        slideVelocity = 0f
        val speed = dpFloat(900f + power * 48f)
        val angle = Random.nextFloat() * (Math.PI.toFloat() * 2f)
        velocityX = cos(angle) * speed
        velocityY = sin(angle) * speed - if (settings.gravity) dpFloat(650f) else 0f
        shakeDizzy = true
        lastInteractionAt = now
        setExpression(PetExpression.SURPRISED, 900L)
        haptic(26L)
        if (wasSleeping) say("wake") else if (Random.nextFloat() < 0.6f) say("shake")
    }

    private fun haptic(durationMs: Long) {
        if (!settings.haptics) return
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
            petView?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
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
        private const val MAX_POOPS = 3
        private const val LONG_PRESS_MS = 420L
        private const val DOUBLE_TAP_MS = 320L
        private const val PEEK_AFTER_MS = 45_000L
        private const val SHAKE_JERK = 14f
        private const val SHAKE_HITS = 4
        private const val SHAKE_WINDOW_MS = 900L
        private const val EARTH_G = 9.81f

        private val PHRASES = mapOf(
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
