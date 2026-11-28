package com.pp.jiocall

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pp.jiocall.data.Account
import com.pp.jiocall.ui.AccountEditorScreen
import com.pp.jiocall.ui.AccountsScreen
import com.pp.jiocall.ui.AppViewModel
import com.pp.jiocall.ui.CallScreen
import com.pp.jiocall.ui.ConnectScreen
import com.pp.jiocall.ui.ContactsScreen
import com.pp.jiocall.ui.DiagnosticsScreen
import com.pp.jiocall.ui.DialerScreen
import com.pp.jiocall.ui.RecentsScreen
import com.pp.jiocall.ui.SettingsScreen
import com.pp.jiocall.ui.components.OngoingCallBanner
import com.pp.jiocall.ui.theme.JioCallTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val intentNumber = MutableStateFlow<String?>(null)
    private val openCallRequest = MutableStateFlow(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intentNumber.value = extractNumberFromIntent(intent)
        if (intent?.getBooleanExtra(com.pp.jiocall.sip.CallService.EXTRA_OPEN_CALL, false) == true) {
            openCallRequest.value += 1
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            val viewModel: AppViewModel = viewModel()
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            JioCallTheme(darkTheme = settings.darkMode) {
                val context = LocalContext.current
                val permissions = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { }
                LaunchedEffect(Unit) {
                    val wanted = buildList {
                        add(Manifest.permission.RECORD_AUDIO)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            add(Manifest.permission.BLUETOOTH_CONNECT)
                        }
                    }.filter {
                        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                    }
                    if (wanted.isNotEmpty()) permissions.launch(wanted.toTypedArray())
                }
                val externalDialNumber by intentNumber.collectAsStateWithLifecycle()
                val openCallTrigger by openCallRequest.collectAsStateWithLifecycle()
                MainContent(
                    viewModel = viewModel,
                    settings = settings,
                    externalDialNumber = externalDialNumber,
                    openCallTrigger = openCallTrigger,
                    onExternalNumberConsumed = { intentNumber.value = null },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as? App)?.onAppForegrounded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intentNumber.value = extractNumberFromIntent(intent)
        if (intent.getBooleanExtra(com.pp.jiocall.sip.CallService.EXTRA_OPEN_CALL, false)) {
            openCallRequest.value += 1
        }
    }

    private fun extractNumberFromIntent(intent: Intent?): String? {
        if (intent == null) return null
        val action = intent.action
        if (action == Intent.ACTION_DIAL || action == Intent.ACTION_VIEW) {
            val uri = intent.data ?: return null
            val scheme = uri.scheme
            if (scheme == "tel" || scheme == "sip") {
                return uri.schemeSpecificPart?.replace(Regex("[^0-9+*#]"), "")
            }
        }
        return null
    }
}

private sealed interface Route {
    data object Connect : Route
    data object Accounts : Route
    data object Settings : Route
    data object Diagnostics : Route
    data class Editor(val account: Account, val isNew: Boolean) : Route
}

@Composable
private fun MainContent(
    viewModel: AppViewModel = viewModel(),
    settings: com.pp.jiocall.data.AppSettings = viewModel.settings.collectAsStateWithLifecycle().value,
    externalDialNumber: String? = null,
    openCallTrigger: Int = 0,
    onExternalNumberConsumed: () -> Unit = {},
) {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val calls by viewModel.calls.collectAsStateWithLifecycle()
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val contactLookup by viewModel.contactLookup.collectAsStateWithLifecycle()
    val availableRecordings by viewModel.availableRecordings.collectAsStateWithLifecycle()
    val recentsUi by viewModel.recentsUi.collectAsStateWithLifecycle()
    val contactRowsUi by viewModel.contactRowsUi.collectAsStateWithLifecycle()
    val statuses by viewModel.statuses.collectAsStateWithLifecycle()
    val call by viewModel.activeCall.collectAsStateWithLifecycle()
    val secondaryCall by viewModel.secondaryCall.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val speaker by viewModel.speaker.collectAsStateWithLifecycle()
    val routerNote by viewModel.routerNote.collectAsStateWithLifecycle()

    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 3 })
    val pagerScope = rememberCoroutineScope()
    var route by remember { mutableStateOf<Route?>(null) }
    var isCallMinimized by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val hasActiveCall = call != null
    val sharedPlayback = com.pp.jiocall.ui.components.rememberRecordingPlaybackController(
        availableRecordings = availableRecordings,
        hasActiveCall = hasActiveCall,
    )

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }
    LaunchedEffect(call) {
        if (call == null) {
            viewModel.refreshCalls()
            isCallMinimized = false
        }
    }
    LaunchedEffect(call?.finished) {
        if (call?.finished == true) {
            viewModel.refreshCalls()
        }
    }
    LaunchedEffect(call?.callId) {
        if (call != null) isCallMinimized = false
    }
    LaunchedEffect(call?.state) {
        // Bring call to full screen when an incoming call starts ringing or is answered
        val active = call ?: return@LaunchedEffect
        if (!active.outgoing && active.state == com.pp.jiocall.sip.CallState.RINGING) {
            isCallMinimized = false
        }
    }
    LaunchedEffect(externalDialNumber) {
        if (!externalDialNumber.isNullOrEmpty()) {
            pagerState.scrollToPage(0)
            route = null
        }
    }
    LaunchedEffect(openCallTrigger) {
        if (openCallTrigger > 0 && call != null) {
            isCallMinimized = false
            route = null
        }
    }

    val callContext = LocalContext.current
    val view = LocalView.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val fullScreenDarkCall = call != null && !isCallMinimized
    val showTopCallBar = call != null && isCallMinimized

    fun dismissKeyboardAndFocus() {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        val imm = callContext.getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
        view.clearFocus()
    }

    LaunchedEffect(fullScreenDarkCall, openCallTrigger, pagerState.currentPage, route) {
        dismissKeyboardAndFocus()
    }

    if (!view.isInEditMode) {
        LaunchedEffect(settings.darkMode, fullScreenDarkCall) {
            val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
            val darkIcons = !settings.darkMode && !fullScreenDarkCall
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = darkIcons
            controller.isAppearanceLightNavigationBars = darkIcons
        }
        val defaultSoftInputMode = remember {
            (view.context as? Activity)?.window?.attributes?.softInputMode
                ?: WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
        }
        DisposableEffect(hasActiveCall, fullScreenDarkCall) {
            val activity = view.context as? Activity
            if (activity != null) {
                activity.setShowWhenLocked(hasActiveCall)
                activity.setTurnScreenOn(hasActiveCall)
                activity.volumeControlStream = if (hasActiveCall) {
                    AudioManager.STREAM_VOICE_CALL
                } else {
                    AudioManager.USE_DEFAULT_STREAM_TYPE
                }
                activity.window?.setSoftInputMode(
                    if (fullScreenDarkCall) {
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                    } else {
                        defaultSoftInputMode
                    },
                )
            }
            if (fullScreenDarkCall) {
                dismissKeyboardAndFocus()
            }
            onDispose { }
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Skip rasterizing offscreen pager tabs while full-screen CallScreen covers them.
        Column(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = if (fullScreenDarkCall) 0f else 1f },
        ) {
            val currentCall = call

            // Persistent One-UI Top Ongoing Call Bar visible across tabs AND Settings/Accounts/Contacts
            AnimatedVisibility(
                visible = showTopCallBar,
                enter = expandVertically(
                    animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
                ) + fadeIn(animationSpec = tween(220)),
                exit = shrinkVertically(
                    animationSpec = tween(220, easing = FastOutSlowInEasing),
                ) + fadeOut(animationSpec = tween(160)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(com.pp.jiocall.data.GooglePhoneColors.background(settings.darkMode))
                        .statusBarsPadding(),
                ) {
                    if (currentCall != null) {
                        OngoingCallBanner(
                            call = currentCall,
                            speakerOn = speaker,
                            onClick = {
                                dismissKeyboardAndFocus()
                                isCallMinimized = false
                                route = null
                            },
                            onHangup = { viewModel.hangup() },
                            onToggleMute = { viewModel.toggleMute() },
                            onToggleSpeaker = { viewModel.toggleSpeaker() },
                            onAnswer = { viewModel.answer() },
                            qualityFlow = viewModel.callQuality,
                        )
                    }
                }
            }

            val currentRoute = route

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .focusProperties { canFocus = !fullScreenDarkCall },
            ) {
                Scaffold(
                    contentWindowInsets = if (showTopCallBar) {
                        ScaffoldDefaults.contentWindowInsets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
                    } else {
                        ScaffoldDefaults.contentWindowInsets
                    },
                    snackbarHost = { SnackbarHost(snackbar) },
                    bottomBar = {
                        if ((call == null || isCallMinimized) && route == null) {
                            val isDark = settings.darkMode
                            val currentPage = pagerState.currentPage
                            Column {
                                HorizontalDivider(
                                    color = com.pp.jiocall.data.GooglePhoneColors.cardBorder(isDark),
                                    thickness = 0.8.dp,
                                )
                                NavigationBar(
                                    containerColor = com.pp.jiocall.data.GooglePhoneColors.bottomNavBg(isDark),
                                ) {
                                    val navColors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = com.pp.jiocall.data.GooglePhoneColors.bottomNavSelectedIcon(isDark),
                                        selectedTextColor = com.pp.jiocall.data.GooglePhoneColors.bottomNavSelectedIcon(isDark),
                                        indicatorColor = com.pp.jiocall.data.GooglePhoneColors.bottomNavIndicator(isDark),
                                        unselectedIconColor = com.pp.jiocall.data.GooglePhoneColors.bottomNavUnselectedIcon(isDark),
                                        unselectedTextColor = com.pp.jiocall.data.GooglePhoneColors.bottomNavUnselectedIcon(isDark),
                                    )
                                    NavigationBarItem(
                                        selected = currentPage == 0,
                                        onClick = {
                                            dismissKeyboardAndFocus()
                                            pagerScope.launch { pagerState.animateScrollToPage(0, animationSpec = tween(180)) }
                                        },
                                        icon = {
                                            Icon(
                                                painter = androidx.compose.ui.res.painterResource(R.drawable.ic_dialpad),
                                                contentDescription = "Dial",
                                            )
                                        },
                                        label = { Text("Dial") },
                                        colors = navColors,
                                    )
                                    NavigationBarItem(
                                        selected = currentPage == 1,
                                        onClick = {
                                            dismissKeyboardAndFocus()
                                            pagerScope.launch { pagerState.animateScrollToPage(1, animationSpec = tween(180)) }
                                        },
                                        icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Recents") },
                                        label = { Text("Recents") },
                                        colors = navColors,
                                    )
                                    NavigationBarItem(
                                        selected = currentPage == 2,
                                        onClick = {
                                            dismissKeyboardAndFocus()
                                            pagerScope.launch { pagerState.animateScrollToPage(2, animationSpec = tween(180)) }
                                        },
                                        icon = { Icon(Icons.Default.Person, contentDescription = "Contacts") },
                                        label = { Text("Contacts") },
                                        colors = navColors,
                                    )
                                }
                            }
                        }
                    },
                ) { padding ->
                    val openAccounts = remember(viewModel) { { dismissKeyboardAndFocus(); route = Route.Accounts } }
                    val openSettings = remember(viewModel) { { dismissKeyboardAndFocus(); route = Route.Settings } }
                    val toggleDark = remember(viewModel) { { viewModel.toggleDarkMode() } }
                    val lastOutgoing = remember(calls) { calls.firstOrNull { it.outgoing }?.number.orEmpty() }
                    val dialFromDialer = remember(viewModel, onExternalNumberConsumed) {
                        { number: String, accountId: Long? ->
                            dismissKeyboardAndFocus()
                            val err = viewModel.dial(number, accountId)
                            onExternalNumberConsumed()
                            err
                        }
                    }
                    val dialVoicemail = remember(viewModel) {
                        { accountId: Long? ->
                            dismissKeyboardAndFocus()
                            viewModel.dialVoicemail(accountId)
                            Unit
                        }
                    }
                    val dialFromRecents = remember(viewModel) {
                        { entry: com.pp.jiocall.data.CallLogEntry ->
                            dismissKeyboardAndFocus()
                            viewModel.callBack(entry)
                            Unit
                        }
                    }
                    val dialFromContacts = remember(viewModel) {
                        { number: String ->
                            dismissKeyboardAndFocus()
                            viewModel.dial(number)
                            Unit
                        }
                    }
                    val resolveContactName = remember(viewModel) { { num: String -> viewModel.resolveName(num) } }
                    val resolveContactPhoto = remember(viewModel) { { num: String -> viewModel.resolvePhotoUri(num) } }
                    val openDialTab = remember(pagerScope, pagerState) {
                        {
                            dismissKeyboardAndFocus()
                            pagerScope.launch { pagerState.animateScrollToPage(0, animationSpec = tween(180)) }
                            Unit
                        }
                    }

                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .focusProperties { canFocus = !fullScreenDarkCall && currentRoute == null },
                    ) {
                        HorizontalPager(
                            state = pagerState,
                            beyondViewportPageCount = 1,
                            userScrollEnabled = false,
                            modifier = Modifier.fillMaxSize(),
                        ) { page ->
                            when (page) {
                                0 -> DialerScreen(
                                    accounts = accounts,
                                    statuses = statuses,
                                    contacts = contacts,
                                    initialNumber = externalDialNumber.orEmpty(),
                                    lastNumber = lastOutgoing,
                                    onDial = dialFromDialer,
                                    onDialVoicemail = dialVoicemail,
                                    onOpenAccounts = openAccounts,
                                    onOpenSettings = openSettings,
                                    isDark = settings.darkMode,
                                    onToggleDarkMode = toggleDark,
                                    contactLookup = contactLookup,
                                    onInitialNumberConsumed = onExternalNumberConsumed,
                                )

                                1 -> RecentsScreen(
                                    calls = calls,
                                    onDial = dialFromRecents,
                                    onClear = remember(viewModel) { { viewModel.clearCalls() } },
                                    resolveName = resolveContactName,
                                    onOpenDial = openDialTab,
                                    accounts = accounts,
                                    statuses = statuses,
                                    onOpenAccounts = openAccounts,
                                    onOpenSettings = openSettings,
                                    isDark = settings.darkMode,
                                    onToggleDarkMode = toggleDark,
                                    resolvePhotoUri = resolveContactPhoto,
                                    hasActiveCall = hasActiveCall,
                                    precomputedItems = recentsUi,
                                    sharedPlayback = sharedPlayback,
                                )

                                2 -> ContactsScreen(
                                    contacts = contacts,
                                    onImportContacts = remember(viewModel) { { viewModel.importContacts() } },
                                    onDial = dialFromContacts,
                                    onOpenDial = openDialTab,
                                    accounts = accounts,
                                    statuses = statuses,
                                    onOpenAccounts = openAccounts,
                                    onOpenSettings = openSettings,
                                    isDark = settings.darkMode,
                                    onToggleDarkMode = toggleDark,
                                    calls = calls,
                                    hasActiveCall = hasActiveCall,
                                    precomputedRows = contactRowsUi,
                                    sharedPlayback = sharedPlayback,
                                    onSyncContactsIfNeeded = remember(viewModel) { { viewModel.importContactsIfNeeded() } },
                                )
                            }
                        }
                    }
                }

                // 2. Route Overlays cleanly inside Box below persistent top call bar when present
                androidx.compose.animation.AnimatedVisibility(
                    visible = currentRoute != null,
                    enter = slideInVertically(
                        initialOffsetY = { it / 6 },
                        animationSpec = spring(dampingRatio = 0.88f, stiffness = Spring.StiffnessMediumLow),
                    ) + fadeIn(animationSpec = tween(220)),
                    exit = slideOutVertically(
                        targetOffsetY = { it / 6 },
                        animationSpec = tween(200, easing = FastOutSlowInEasing),
                    ) + fadeOut(animationSpec = tween(160)),
                ) {
                    val routeBackdropInteraction = remember { MutableInteractionSource() }
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(interactionSource = routeBackdropInteraction, indication = null) {
                                dismissKeyboardAndFocus()
                            },
                        color = com.pp.jiocall.data.GooglePhoneColors.background(settings.darkMode),
                    ) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .then(if (showTopCallBar) Modifier else Modifier.statusBarsPadding())
                                .navigationBarsPadding()
                                .imePadding(),
                        ) {
                            when (currentRoute) {
                                is Route.Connect -> ConnectScreen(
                                    jio = viewModel.jio,
                                    hostname = viewModel.deviceName,
                                    onDone = { route = Route.Editor(it, true) },
                                    onBack = { route = null },
                                )

                                is Route.Accounts -> AccountsScreen(
                                    accounts = accounts,
                                    statuses = statuses,
                                    onAdd = { route = Route.Editor(Account(), true) },
                                    onConnect = { route = Route.Connect },
                                    onEdit = { route = Route.Editor(it, false) },
                                    onToggle = { account, enabled -> viewModel.setEnabled(account, enabled) },
                                    onDelete = { viewModel.deleteAccount(it.id) },
                                    onRenew = { viewModel.renewCredentials(it) },
                                    onBack = { route = null },
                                )

                                is Route.Diagnostics -> DiagnosticsScreen(
                                    onBack = { route = null },
                                )

                                is Route.Settings -> SettingsScreen(
                                    settings = settings,
                                    deviceName = viewModel.deviceName,
                                    onSave = { viewModel.updateSettings(it) },
                                    onOpenDiagnostics = { route = Route.Diagnostics },
                                    onBack = { route = null },
                                )

                                is Route.Editor -> {
                                    val live = accounts.firstOrNull { it.id == currentRoute.account.id && it.id != 0L }
                                        ?: currentRoute.account
                                    AccountEditorScreen(
                                        original = live,
                                        isNew = currentRoute.isNew,
                                        status = statuses[live.id],
                                        routerNote = routerNote,
                                        onSave = {
                                            viewModel.saveAccount(it)
                                            viewModel.clearRouterNote()
                                            route = Route.Accounts
                                        },
                                        onDelete = {
                                            if (!currentRoute.isNew) viewModel.deleteAccount(currentRoute.account.id)
                                            viewModel.clearRouterNote()
                                            route = Route.Accounts
                                        },
                                        onRenew = { viewModel.renewCredentials(it) },
                                        onBack = {
                                            viewModel.clearRouterNote()
                                            route = Route.Accounts
                                        },
                                        onClearRouterNote = { viewModel.clearRouterNote() },
                                    )
                                }

                                null -> {}
                            }
                        }
                    }
                }
            }
        }

        val currentCall = call

        // 3. True Edge-to-Edge Full-Screen CallScreen with silky spring expand
        AnimatedVisibility(
            visible = currentCall != null && !isCallMinimized,
            enter = slideInVertically(
                initialOffsetY = { it / 2 },
                animationSpec = spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow),
            ) + scaleIn(initialScale = 0.96f, animationSpec = tween(260)) + fadeIn(animationSpec = tween(220)),
            exit = slideOutVertically(
                targetOffsetY = { it / 2 },
                animationSpec = tween(240, easing = FastOutSlowInEasing),
            ) + scaleOut(targetScale = 0.96f, animationSpec = tween(220)) + fadeOut(animationSpec = tween(180)),
        ) {
            if (currentCall != null) {
                val callPhotoUri = remember(currentCall.number, contacts) {
                    viewModel.resolvePhotoUri(currentCall.number)
                }
                CallScreen(
                    call = currentCall,
                    speakerOn = speaker,
                    onAnswer = { viewModel.answer() },
                    onHangup = { viewModel.hangup() },
                    onMute = { viewModel.toggleMute() },
                    onHold = { viewModel.toggleHold() },
                    onSpeaker = { viewModel.toggleSpeaker() },
                    onRecord = { viewModel.toggleRecord() },
                    onSilence = { viewModel.silenceRinger() },
                    onDtmf = { viewModel.sendDtmf(it) },
                    onMinimize = { isCallMinimized = true },
                    onOpenContacts = {
                        dismissKeyboardAndFocus()
                        isCallMinimized = true
                        route = null
                        pagerScope.launch { pagerState.scrollToPage(2) }
                    },
                    secondaryCall = secondaryCall,
                    onAnswerWaiting = { viewModel.answerWaiting() },
                    onDeclineWaiting = { viewModel.declineWaiting() },
                    onSwapCalls = { viewModel.swapCalls() },
                    photoUri = callPhotoUri,
                    qualityFlow = viewModel.callQuality,
                )
            }
        }
    }

    BackHandler(enabled = route != null) {
        if (route is Route.Editor) {
            viewModel.clearRouterNote()
            route = Route.Accounts
        } else {
            route = null
        }
    }

    BackHandler(enabled = call != null) {
        val current = call ?: return@BackHandler
        if (current.finished) {
            viewModel.dismissCall()
            isCallMinimized = false
        } else if (!current.outgoing && current.state == com.pp.jiocall.sip.CallState.RINGING) {
            // Do not hide an unanswered ringing call into the floating bar; silence & move task back instead.
            viewModel.silenceRinger()
            (callContext as? Activity)?.moveTaskToBack(true)
        } else if (!isCallMinimized) {
            isCallMinimized = true
        } else {
            (callContext as? Activity)?.moveTaskToBack(true)
        }
    }
}
