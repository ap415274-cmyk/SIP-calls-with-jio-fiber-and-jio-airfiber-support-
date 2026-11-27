package com.pp.jiocall.ui

import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pp.jiocall.data.AppSettings
import com.pp.jiocall.jio.JioRouter

@Composable
fun SettingsScreen(
    settings: AppSettings,
    deviceName: String,
    onSave: (AppSettings) -> Unit,
    onOpenDiagnostics: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var codecs by rememberSaveable { mutableStateOf(settings.audioCodecs) }
    var speakerphone by rememberSaveable { mutableStateOf(settings.speakerphone) }
    var echo by rememberSaveable { mutableStateOf(settings.echoCancellation) }
    var ringtone by rememberSaveable { mutableStateOf(settings.ringtone) }
    var autoRefresh by rememberSaveable { mutableStateOf(settings.autoRefreshRouter) }
    var background by rememberSaveable { mutableStateOf(settings.backgroundService) }
    var bootStart by rememberSaveable { mutableStateOf(settings.bootStart) }
    var autoRecord by rememberSaveable { mutableStateOf(settings.autoRecord) }
    var darkMode by rememberSaveable { mutableStateOf(settings.darkMode) }
    var dnd by rememberSaveable { mutableStateOf(settings.dnd) }
    var callWaiting by rememberSaveable { mutableStateOf(settings.callWaiting) }
    var forwarding by rememberSaveable { mutableStateOf(settings.forwarding) }
    var forwardingNumber by rememberSaveable { mutableStateOf(settings.forwardingNumber) }
    var dialPlan by rememberSaveable { mutableStateOf(settings.customDialPlan) }
    var validationError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(settings.darkMode) {
        darkMode = settings.darkMode
    }

    fun buildSettings(
        speaker: Boolean = speakerphone,
        echoCancel: Boolean = echo,
        playRingtone: Boolean = ringtone,
        refreshRouter: Boolean = autoRefresh,
        bgService: Boolean = background,
        startOnBoot: Boolean = bootStart,
        recordCalls: Boolean = autoRecord,
        isDarkMode: Boolean = darkMode,
        isDnd: Boolean = dnd,
        isCallWaiting: Boolean = callWaiting,
        isForwarding: Boolean = forwarding,
    ): AppSettings = AppSettings(
        audioCodecs = codecs,
        speakerphone = speaker,
        echoCancellation = echoCancel,
        ringtone = playRingtone,
        autoRefreshRouter = refreshRouter,
        backgroundService = bgService,
        bootStart = startOnBoot,
        autoRecord = recordCalls,
        darkMode = isDarkMode,
        dnd = isDnd,
        callWaiting = isCallWaiting,
        forwarding = isForwarding,
        forwardingNumber = forwardingNumber.trim(),
        customDialPlan = dialPlan,
    )

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        ) {
            ScreenHeader("Settings", onBack = onBack)

            SectionTitle("Audio")
            Column(Modifier.padding(horizontal = 4.dp)) {
                OutlinedTextField(
                    value = codecs,
                    onValueChange = { codecs = it },
                    label = { Text("Codecs, in preference order") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Hint("AMR-WB and AMR are what Jio's IMS expects; PCMA and PCMU work with most PBXs.")
                Spacer(Modifier.height(8.dp))
            }
            ToggleRow("Loudspeaker by default", "Route call audio to the speaker", speakerphone) {
                speakerphone = it
                onSave(buildSettings(speaker = it))
            }
            ToggleRow("Echo cancellation", "Cancel the echo of your own voice", echo) {
                echo = it
                onSave(buildSettings(echoCancel = it))
            }
            ToggleRow("Ringtone", "Play the ringtone for incoming calls", ringtone) {
                ringtone = it
                onSave(buildSettings(playRingtone = it))
            }

        SectionTitle("JioFiber / AirFiber")
        ToggleRow(
            "Refresh router credentials",
            "Ask the router for new temporary credentials when they expire or stop working",
            autoRefresh,
        ) {
            autoRefresh = it
            onSave(buildSettings(refreshRouter = it))
        }
        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("This device", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                InfoRow("Name", deviceName)
                InfoRow("Router identity", JioRouter.deviceMac(deviceName))
                Hint("The router knows this device by that name and address. Changing the phone's model name is not possible; unregistering from the router removes the entry.")
            }
        }

        SectionTitle("Call Recording")
        ToggleRow(
            "Auto-record calls",
            "Automatically record every call once answered. Silent: no beep or sound is sent to the other person",
            autoRecord,
        ) {
            autoRecord = it
            onSave(buildSettings(recordCalls = it))
        }
        Hint("Recordings are saved locally as standard 16-bit PCM WAV files. 100% private — zero audio or beeps sent over the wire.")

        SectionTitle("Call Features & Rules")
        ToggleRow("Do Not Disturb (DND)", "Silently reject incoming calls with SIP 486 Busy Here", dnd) {
            dnd = it
            onSave(buildSettings(isDnd = it))
        }
        ToggleRow("Call Waiting", "Alert with discrete beeps when a second call arrives", callWaiting) {
            callWaiting = it
            onSave(buildSettings(isCallWaiting = it))
        }
        ToggleRow("Call Forwarding", "Redirect incoming calls via SIP 302 Moved Temporarily", forwarding) {
            forwarding = it
            if (!it || forwardingNumber.isNotBlank()) {
                validationError = null
                onSave(buildSettings(isForwarding = it))
            } else {
                validationError = "Enter a forwarding number and tap Save settings."
            }
        }
        if (forwarding) {
            Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                OutlinedTextField(
                    value = forwardingNumber,
                    onValueChange = {
                        forwardingNumber = it
                        validationError = null
                    },
                    label = { Text("Forward to phone number") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Hint("Target number or extension to receive redirected calls.")
                Spacer(Modifier.height(4.dp))
            }
        }
        Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
            OutlinedTextField(
                value = dialPlan,
                onValueChange = { dialPlan = it },
                label = { Text("Custom Dial Plan (Linksys/Cisco format)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Hint("e.g. <0:91>x. (replace 0 with 91) or x. (match any length). Leave empty for default Indian mobile normalization.")
            Spacer(Modifier.height(4.dp))
        }

        SectionTitle("Display & Theme")
        ToggleRow(
            "Dark theme",
            "Google Phone style dark theme with deep dark surfaces and contrast keys",
            darkMode,
        ) {
            darkMode = it
            onSave(buildSettings(isDarkMode = it))
        }

        SectionTitle("Background & Startup")
        ToggleRow(
            "Auto-start on boot",
            "Automatically start JioCall and incoming call system after device reboot",
            bootStart,
        ) {
            bootStart = it
            onSave(buildSettings(startOnBoot = it))
        }
        ToggleRow(
            "Keep registered in the background",
            "A low-priority notification keeps the SIP connection alive for incoming calls",
            background,
        ) {
            background = it
            onSave(buildSettings(bgService = it))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = remember(context) { context.getSystemService(NotificationManager::class.java) }
            var canFullScreen by remember { mutableStateOf(nm?.canUseFullScreenIntent() != false) }
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner, nm) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        canFullScreen = nm?.canUseFullScreenIntent() != false
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                onClick = {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    runCatching { context.startActivity(intent) }.onFailure {
                        Toast.makeText(context, "Open App info > Notifications to enable full-screen alerts", Toast.LENGTH_SHORT).show()
                    }
                },
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("Lock-screen incoming call display", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = if (canFullScreen) {
                            "Allowed — full-screen incoming call slides can appear over lock screen"
                        } else {
                            "Permission required — tap to allow full-screen incoming calls over lock screen"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (canFullScreen) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        SectionTitle("Diagnostics")
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            onClick = { onOpenDiagnostics?.invoke() },
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("SIP Packet Trace & Diagnostics", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Inspect live SIP messages, headers, and SDP payloads sent and received by the app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        SectionTitle("About")
        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("JioCall for Android", style = MaterialTheme.typography.titleSmall)
                Hint("A port of RusTone (itsyourap/RusTone) with the JioFiber/AirFiber provisioning of JFC-RusTone. SIP signalling and RTP audio run inside the app.")
            }
        }

        validationError?.let { msg ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = msg,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                if (forwarding && forwardingNumber.isBlank()) {
                    validationError = "Enter a forwarding number or turn off Call Forwarding."
                } else {
                    validationError = null
                    onSave(buildSettings())
                    Toast.makeText(context, "Settings saved", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save settings") }
        Spacer(Modifier.height(24.dp))
    }
    }
}

@Composable
private fun ToggleRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
