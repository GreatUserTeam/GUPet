package com.frizovdanya.pixpet

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.provider.Settings.Secure
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GUPetTheme { GUPetApp() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GUPetApp() {
    var showAbout by remember { mutableStateOf(false) }
    BackHandler(enabled = showAbout) { showAbout = false }
    if (showAbout) GUPetAboutPage(onBack = { showAbout = false })
    else GUPetSettingsScreen(onAbout = { showAbout = true })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GUPetSettingsScreen(onAbout: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { PetPrefs.file(context) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var notificationAccess by remember { mutableStateOf(hasNotificationAccess(context)) }
    var enabled by remember { mutableStateOf(prefs.getBoolean(PetPrefs.SHOW, false)) }
    var poopEnabled by remember { mutableStateOf(prefs.getBoolean(PetPrefs.POOP, true)) }
    var species by remember { mutableStateOf(PetSpecies.fromKey(prefs.getString(PetPrefs.SPECIES, PetSpecies.GUPET.key))) }
    val initialSkinInfos = remember { GUPetSkinStore.list(context) }
    var customSkinInfos by remember { mutableStateOf(initialSkinInfos) }
    var customSkinId by remember {
        mutableStateOf(prefs.getString(PetPrefs.CUSTOM_SKIN_ID, null).takeIf { id -> initialSkinInfos.any { it.id == id } })
    }
    var selectedCustomSkin by remember { mutableStateOf(GUPetSkinStore.load(context, customSkinId)) }
    var skinToDelete by remember { mutableStateOf<GUPetSkinInfo?>(null) }
    var palette by remember { mutableIntStateOf(prefs.getInt(PetPrefs.PALETTE, 0).coerceIn(0, PetPalettes.names.lastIndex)) }
    var sizeDp by remember { mutableFloatStateOf(prefs.getFloat(PetPrefs.SIZE_DP, 4f).coerceIn(3f, 5f)) }
    var wander by remember { mutableStateOf(prefs.getBoolean(PetPrefs.WANDER, true)) }
    var phrases by remember { mutableStateOf(prefs.getBoolean(PetPrefs.PHRASES, true)) }
    var gravity by remember { mutableStateOf(prefs.getBoolean(PetPrefs.GRAVITY, false)) }
    var shake by remember { mutableStateOf(prefs.getBoolean(PetPrefs.SHAKE, true)) }
    var haptics by remember { mutableStateOf(prefs.getBoolean(PetPrefs.HAPTICS, true)) }
    var dance by remember { mutableStateOf(prefs.getBoolean(PetPrefs.DANCE, true)) }
    var reactNotifications by remember { mutableStateOf(prefs.getBoolean(PetPrefs.REACT_NOTIFICATIONS, false)) }
    var sleepMinutes by remember { mutableIntStateOf(prefs.getInt(PetPrefs.SLEEP_MINUTES, 3).coerceIn(0, 10)) }
    var petsCount by remember { mutableIntStateOf(prefs.getInt(PetPrefs.PETS_COUNT, 0)) }
    var poopsCleaned by remember { mutableIntStateOf(prefs.getInt(PetPrefs.POOPS_CLEANED, 0)) }
    var pendingAction by remember { mutableStateOf(GUPetContract.ACTION_START) }
    var pendingStart by remember { mutableStateOf(false) }
    var pendingNotificationEnable by remember { mutableStateOf(false) }
    var pendingDanceEnable by remember { mutableStateOf(false) }

    DisposableEffect(prefs, context) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                PetPrefs.PETS_COUNT -> petsCount = prefs.getInt(PetPrefs.PETS_COUNT, 0)
                PetPrefs.POOPS_CLEANED -> poopsCleaned = prefs.getInt(PetPrefs.POOPS_CLEANED, 0)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        dispatchPetAction(context, pendingAction, scope, snackbar)
    }

    val skinImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) { GUPetSkinStore.importSkin(context, uri) }
                }
                result.onSuccess { info ->
                    customSkinInfos = GUPetSkinStore.list(context)
                    customSkinId = info.id
                    selectedCustomSkin = withContext(Dispatchers.IO) { GUPetSkinStore.load(context, info.id) }
                    val revision = prefs.getLong(PetPrefs.CUSTOM_SKIN_REVISION, 0L) + 1L
                    prefs.edit()
                        .putString(PetPrefs.CUSTOM_SKIN_ID, info.id)
                        .putLong(PetPrefs.CUSTOM_SKIN_REVISION, revision)
                        .apply()
                    if (enabled) dispatchPetAction(context, GUPetContract.ACTION_SETTINGS_CHANGED, scope, snackbar)
                    snackbar.showSnackbar("Скин «${info.name}» импортирован и выбран")
                }.onFailure { error ->
                    snackbar.showSnackbar(error.message ?: "Не удалось импортировать скин")
                }
            }
        }
    }

    fun enableAndStart(action: String) {
        enabled = true
        prefs.edit().putBoolean(PetPrefs.SHOW, true).apply()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingAction = action
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            dispatchPetAction(context, action, scope, snackbar)
        }
    }

    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        overlayGranted = Settings.canDrawOverlays(context)
        if (overlayGranted && pendingStart) {
            pendingStart = false
            enableAndStart(pendingAction)
        } else if (!overlayGranted) {
            pendingStart = false
            scope.launch { snackbar.showSnackbar("Разрешение на отображение поверх приложений не выдано") }
        }
    }

    val notificationAccessLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        notificationAccess = hasNotificationAccess(context)
        if (pendingNotificationEnable && notificationAccess) {
            reactNotifications = true
            prefs.edit().putBoolean(PetPrefs.REACT_NOTIFICATIONS, true).apply()
        } else if (pendingNotificationEnable) {
            reactNotifications = false
            scope.launch { snackbar.showSnackbar("Доступ к уведомлениям не включён") }
        }
        pendingNotificationEnable = false
        if (pendingDanceEnable && notificationAccess) {
            dance = true
            prefs.edit().putBoolean(PetPrefs.DANCE, true).apply()
        } else if (pendingDanceEnable) {
            scope.launch { snackbar.showSnackbar("Без доступа к медиасессиям питомец не сможет определить музыку") }
        }
        pendingDanceEnable = false
        if (notificationAccess && enabled) {
            dispatchPetAction(context, GUPetContract.ACTION_SETTINGS_CHANGED, scope, snackbar)
        }
    }

    fun requestStart(action: String) {
        if (!Settings.canDrawOverlays(context)) {
            pendingAction = action
            pendingStart = true
            overlayPermissionLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
            )
            return
        }
        overlayGranted = true
        enableAndStart(action)
    }

    fun stopPet() {
        enabled = false
        prefs.edit().putBoolean(PetPrefs.SHOW, false).apply()
        context.stopService(Intent(context, OverlayPetService::class.java))
    }

    fun saveBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    fun selectCustomSkin(id: String?) {
        customSkinId = id
        selectedCustomSkin = GUPetSkinStore.load(context, id)
        val editor = prefs.edit()
        if (id == null) editor.remove(PetPrefs.CUSTOM_SKIN_ID)
        else editor.putString(PetPrefs.CUSTOM_SKIN_ID, id)
        editor.apply()
        if (enabled) dispatchPetAction(context, GUPetContract.ACTION_SETTINGS_CHANGED, scope, snackbar)
    }

    skinToDelete?.let { skin ->
        AlertDialog(
            onDismissRequest = { skinToDelete = null },
            title = { Text("Удалить скин?") },
            text = { Text("«${skin.name}» будет удалён с устройства.") },
            confirmButton = {
                TextButton(onClick = {
                    GUPetSkinStore.delete(context, skin.id)
                    customSkinInfos = GUPetSkinStore.list(context)
                    if (customSkinId == skin.id) selectCustomSkin(null)
                    skinToDelete = null
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { skinToDelete = null }) { Text("Отмена") } },
        )
    }

    val scheme = MaterialTheme.colorScheme
    Scaffold(
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("GUPet", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text(stringResource(R.string.app_subtitle), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = scheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = innerPadding.calculateTopPadding() + 8.dp, bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                HeroCard(
                    enabled = enabled,
                    overlayGranted = overlayGranted,
                    species = species,
                    palette = palette,
                    customSkin = selectedCustomSkin,
                    onToggle = { checked ->
                        if (checked) requestStart(GUPetContract.ACTION_START) else stopPet()
                    },
                )
            }

            item {
                AnimatedVisibility(visible = !overlayGranted) {
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = scheme.tertiaryContainer),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Smartphone, contentDescription = null, tint = scheme.onTertiaryContainer, modifier = Modifier.size(28.dp))
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Нужно разрешение", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = scheme.onTertiaryContainer)
                                Text("Android попросит разрешить GUPet показываться поверх других приложений.", style = MaterialTheme.typography.bodySmall, color = scheme.onTertiaryContainer)
                            }
                            Button(onClick = { requestStart(GUPetContract.ACTION_START) }) { Text("Разрешить") }
                        }
                    }
                }
            }

            item { SectionTitle("Выбери питомца", "Питомец GUPet, кошка или собака", Icons.Outlined.Pets) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    PetSpecies.entries.forEach { item ->
                        SpeciesCard(
                            species = item,
                            selected = species == item && customSkinId == null,
                            palette = palette,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                species = item
                                prefs.edit().putString(PetPrefs.SPECIES, item.key).apply()
                                if (customSkinId != null) selectCustomSkin(null)
                            },
                        )
                    }
                }
            }

            item {
                Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Palette, contentDescription = null, tint = scheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Пользовательские скины", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text("Скин заменяет вид и окрас; анимации питомца сохраняются.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.Start) {
                            FilterChip(
                                selected = customSkinId == null,
                                onClick = { selectCustomSkin(null) },
                                label = { Text("Встроенный вид") },
                            )
                            OutlinedButton(onClick = { skinImportLauncher.launch("*/*") }) {
                                Icon(Icons.Outlined.FileOpen, contentDescription = null)
                                Spacer(Modifier.width(7.dp))
                                Text("Импорт .gupet")
                            }
                        }
                        Text("PNG-кадры 18 × 13 px · архивы размером до 2 МБ", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                        if (customSkinInfos.isEmpty()) {
                            Text("Пока нет добавленных скинов. Формат и пример архива — в документации.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        } else {
                            customSkinInfos.forEach { skin ->
                                Card(
                                    onClick = { selectCustomSkin(skin.id) },
                                    shape = RoundedCornerShape(18.dp),
                                    colors = CardDefaults.cardColors(containerColor = if (customSkinId == skin.id) scheme.secondaryContainer else scheme.surface),
                                    border = if (customSkinId == skin.id) androidx.compose.foundation.BorderStroke(1.5.dp, scheme.primary) else null,
                                ) {
                                    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(skin.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                            Text("Автор: ${skin.author}", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                                            if (customSkinId == skin.id) Text("Выбрано", style = MaterialTheme.typography.labelSmall, color = scheme.primary)
                                        }
                                        IconButton(onClick = { skinToDelete = skin }) {
                                            Icon(Icons.Outlined.DeleteOutline, contentDescription = "Удалить скин ${skin.name}", tint = scheme.error)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Palette, contentDescription = null, tint = scheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("Окрас", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (customSkinId == null) PetPalettes.names[palette] else "Сохранённый окрас встроенного вида: ${PetPalettes.names[palette]}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant,
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            PALETTE_COLORS.forEachIndexed { index, color ->
                                PaletteSwatch(
                                    color = color,
                                    selected = palette == index,
                                    label = PetPalettes.names[index],
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        palette = index
                                        prefs.edit().putInt(PetPrefs.PALETTE, index).apply()
                                    },
                                )
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Размер", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text("${sizeDp.toInt()} dp", style = MaterialTheme.typography.labelLarge, color = scheme.primary)
                        }
                        Slider(
                            value = sizeDp,
                            onValueChange = { sizeDp = it },
                            onValueChangeFinished = { prefs.edit().putFloat(PetPrefs.SIZE_DP, sizeDp).apply() },
                            valueRange = 3f..5f,
                            steps = 1,
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Маленький", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                            Text("Большой", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                        }
                    }
                }
            }

            item { SectionTitle("Кучки", "Можно вызвать в любой момент и убрать одним касанием", Icons.Outlined.CleaningServices) }
            item {
                Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = scheme.secondaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("💩", fontSize = 28.sp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Самостоятельные кучки", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = scheme.onSecondaryContainer)
                                Text("Можно отключить случайные события. Ручной вызов доступен в любой момент.", style = MaterialTheme.typography.bodySmall, color = scheme.onSecondaryContainer)
                            }
                            Switch(checked = poopEnabled, onCheckedChange = {
                                poopEnabled = it
                                saveBoolean(PetPrefs.POOP, it)
                            })
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            FilledTonalButton(
                                onClick = { requestStart(GUPetContract.ACTION_POOP_NOW) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                            ) { Text("Покакать сейчас", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            OutlinedButton(
                                onClick = {
                                    if (enabled) dispatchPetAction(context, GUPetContract.ACTION_CLEAN_POOPS, scope, snackbar)
                                    else scope.launch { snackbar.showSnackbar("Сначала включи питомца") }
                                },
                                enabled = enabled,
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                            ) { Text("Убрать кучки", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
            }

            item { SectionTitle("Поведение", "Питомец живёт своей маленькой жизнью", Icons.Outlined.AutoAwesome) }
            item {
                Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow)) {
                    Column {
                        SettingSwitchRow(Icons.Outlined.AutoAwesome, "Гуляет сам", "Ходит, прыгает, машет и иногда выглядывает из-за края", wander) {
                            wander = it; saveBoolean(PetPrefs.WANDER, it)
                        }
                        SettingSwitchRow(Icons.Outlined.ChatBubbleOutline, "Реплики", "Короткие облачка во время игры", phrases) {
                            phrases = it; saveBoolean(PetPrefs.PHRASES, it)
                        }
                        SettingSwitchRow(Icons.Outlined.Sensors, "Гравитация и наклон", "Падает вниз и скользит, если наклонить телефон", gravity) {
                            gravity = it; saveBoolean(PetPrefs.GRAVITY, it)
                        }
                        SettingSwitchRow(Icons.Outlined.Smartphone, "Реагирует на встряхивание", "Потряси телефон — питомец кувыркнётся", shake) {
                            shake = it; saveBoolean(PetPrefs.SHAKE, it)
                        }
                        SettingSwitchRow(Icons.Outlined.Vibration, "Тактильный отклик", "Лёгкая вибрация при касаниях", haptics) {
                            haptics = it; saveBoolean(PetPrefs.HAPTICS, it)
                        }
                        SettingSwitchRow(
                            Icons.Outlined.MusicNote,
                            "Танцует под музыку",
                            if (notificationAccess) "Реагирует на активные медиаплееры и меняет танцевальные движения" else "Разреши доступ к медиасессиям в блоке ниже — звук не записывается",
                            dance,
                        ) { checked ->
                            if (checked && !notificationAccess) {
                                pendingDanceEnable = true
                                notificationAccessLauncher.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            } else {
                                dance = checked
                                saveBoolean(PetPrefs.DANCE, checked)
                            }
                        }
                        SettingSwitchRow(Icons.Outlined.NotificationsActive, "Реакции на Telegram", "Распознаёт приветствия, смех и сердечки в уведомлениях", reactNotifications) {
                            if (it && !notificationAccess) {
                                pendingNotificationEnable = true
                                notificationAccessLauncher.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            } else {
                                reactNotifications = it
                                saveBoolean(PetPrefs.REACT_NOTIFICATIONS, it)
                            }
                        }
                    }
                }
            }

            item {
                AnimatedVisibility(visible = !notificationAccess) {
                    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow)) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.NotificationsActive, contentDescription = null, tint = scheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Доступ для музыки и уведомлений", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text("Нужен для определения воспроизведения музыки и реакций на Telegram-уведомления. Текст проверяется локально и не сохраняется.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                            }
                            OutlinedButton(onClick = { notificationAccessLauncher.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("Настроить") }
                        }
                    }
                }
            }

            item {
                Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Bedtime, contentDescription = null, tint = scheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("Когда засыпать", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(if (sleepMinutes == 0) "Не засыпает сам" else "После $sleepMinutes мин. бездействия", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            listOf(1, 3, 10, 0).forEach { minutes ->
                                FilterChip(
                                    selected = sleepMinutes == minutes,
                                    onClick = {
                                        sleepMinutes = minutes
                                        prefs.edit().putInt(PetPrefs.SLEEP_MINUTES, minutes).apply()
                                    },
                                    label = { Text(if (minutes == 0) "Никогда" else "$minutes мин") },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }

            item { SectionTitle("Ещё", "Быстрые действия и статистика", Icons.Outlined.CenterFocusStrong) }
            item {
                Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { if (enabled) dispatchPetAction(context, GUPetContract.ACTION_RECENTER, scope, snackbar) },
                                enabled = enabled,
                                modifier = Modifier.weight(1f),
                            ) { Icon(Icons.Outlined.CenterFocusStrong, contentDescription = null); Spacer(Modifier.width(7.dp)); Text("В центр") }
                            FilledTonalButton(
                                onClick = { if (enabled) stopPet() else requestStart(GUPetContract.ACTION_START) },
                                modifier = Modifier.weight(1f),
                            ) { Icon(Icons.Outlined.Pets, contentDescription = null); Spacer(Modifier.width(7.dp)); Text(if (enabled) "Спрятать" else "Позвать") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            StatTile("Погладили", petsCount.toString(), "💗", Modifier.weight(1f))
                            StatTile("Убрано кучек", poopsCleaned.toString(), "🧹", Modifier.weight(1f))
                        }
                    }
                }
            }

            item {
                Card(
                    onClick = onAbout,
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = scheme.primaryContainer),
                ) {
                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Info, contentDescription = null, tint = scheme.onPrimaryContainer)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("О команде и GUPet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = scheme.onPrimaryContainer)
                            Text("Версия ${BuildConfig.VERSION_NAME} · данные хранятся на устройстве", style = MaterialTheme.typography.bodySmall, color = scheme.onPrimaryContainer)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GUPetAboutPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    Scaffold(
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("О GUPet", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = scheme.background),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = innerPadding.calculateTopPadding() + 12.dp, bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = scheme.primaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("GUPet", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = scheme.onPrimaryContainer)
                        Text("Небольшой пиксельный питомец, который живёт поверх приложений, гуляет, отдыхает и реагирует на происходящее.", style = MaterialTheme.typography.bodyLarge, color = scheme.onPrimaryContainer)
                        Text("Версия ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelLarge, color = scheme.onPrimaryContainer)
                    }
                }
            }
            item {
                Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Команда", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("GUPet развивается командой guteam.net. За разработку отвечает coder @frizovdanya.", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    }
                }
            }
            item {
                Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Документация", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Инструкция по настройке питомца и созданию пользовательских скинов.", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                        OutlinedButton(onClick = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://doc.guteam.net/pet"))) }
                        }) {
                            Icon(Icons.Outlined.FileOpen, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Открыть doc.guteam.net/pet")
                        }
                    }
                }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("by guteam.net", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Text("coder @frizovdanya", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun HeroCard(
    enabled: Boolean,
    overlayGranted: Boolean,
    species: PetSpecies,
    palette: Int,
    customSkin: GUPetSkin?,
    onToggle: (Boolean) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.primaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().background(
                Brush.linearGradient(listOf(scheme.primaryContainer, scheme.tertiaryContainer)),
                RoundedCornerShape(30.dp),
            ).padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Твой маленький\nпиксельный друг", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = scheme.onPrimaryContainer)
                    Text("Живёт поверх приложений — гуляет, прыгает и реагирует на тебя.", style = MaterialTheme.typography.bodyMedium, color = scheme.onPrimaryContainer)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(if (enabled) Color(0xFF258A58) else scheme.outline))
                        Spacer(Modifier.width(7.dp))
                        Text(
                            when {
                                enabled && overlayGranted -> "Питомец на экране"
                                !overlayGranted -> "Нужно разрешение Android"
                                else -> "Питомец пока отдыхает"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onPrimaryContainer,
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    FilledTonalButton(onClick = { onToggle(!enabled) }) {
                        Icon(Icons.Outlined.Pets, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (enabled) "Спрятать питомца" else "Позвать питомца")
                    }
                }
                Spacer(Modifier.width(6.dp))
                PixelPetPreview(species, palette, Modifier.size(width = 112.dp, height = 110.dp), customSkin)
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(scheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(11.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SpeciesCard(
    species: PetSpecies,
    selected: Boolean,
    palette: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) scheme.secondaryContainer else scheme.surfaceContainerLow),
        border = if (selected) androidx.compose.foundation.BorderStroke(1.5.dp, scheme.primary) else null,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            PixelPetPreview(species, palette, Modifier.size(58.dp))
            Text(species.title, style = MaterialTheme.typography.labelLarge, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
            if (selected) Text("Выбрано", style = MaterialTheme.typography.labelSmall, color = scheme.primary)
            else Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable
private fun PaletteSwatch(color: Color, selected: Boolean, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(color)
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Text("✓", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
    }
}

@Composable
private fun SettingSwitchRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (checked) scheme.primary else scheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun StatTile(title: String, value: String, emoji: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier.clip(RoundedCornerShape(17.dp)).background(scheme.surface).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(emoji, fontSize = 19.sp)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(title, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PixelPetPreview(
    species: PetSpecies,
    palette: Int,
    modifier: Modifier = Modifier,
    customSkin: GUPetSkin? = null,
) {
    val pixelPaint = remember {
        android.graphics.Paint().apply {
            style = android.graphics.Paint.Style.FILL
            isAntiAlias = false
            isFilterBitmap = false
        }
    }
    val previewFrame = remember(species, palette) {
        PetFrame(expression = PetExpression.HAPPY, blush = true, phase = 0.4f)
    }
    Canvas(modifier) {
        drawIntoCanvas { canvas ->
            PetRenderer.draw(
                canvas = canvas.nativeCanvas,
                paint = pixelPaint,
                width = size.width,
                height = size.height,
                species = species,
                palette = palette,
                frame = previewFrame,
                nowMs = 0L,
                particles = emptyList(),
                customSkin = customSkin,
            )
        }
    }
}

private fun hasNotificationAccess(context: Context): Boolean {
    val enabled = Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
    return enabled.split(':').any { it.contains(context.packageName) }
}

private fun dispatchPetAction(context: Context, action: String, scope: CoroutineScope, snackbar: SnackbarHostState) {
    try {
        val intent = Intent(context, OverlayPetService::class.java).setAction(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ContextCompat.startForegroundService(context, intent)
        else context.startService(intent)
    } catch (_: Exception) {
        scope.launch { snackbar.showSnackbar("Не удалось запустить питомца. Проверь разрешения Android.") }
    }
}

private val PALETTE_COLORS = listOf(
    Color(0xFFD97757), Color(0xFF4FBF98), Color(0xFF9B8AEE), Color(0xFF4FA8E8), Color(0xFFEE85AE), Color(0xFF5E6670),
)
