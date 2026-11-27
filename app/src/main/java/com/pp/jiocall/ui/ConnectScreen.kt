package com.pp.jiocall.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pp.jiocall.data.Account
import com.pp.jiocall.data.JioMode
import com.pp.jiocall.jio.JioClient
import com.pp.jiocall.jio.JioRouter
import kotlinx.coroutines.launch

/** OTP attempts before starting over, as the router's own tool allows. */
private const val OTP_ATTEMPTS = 3

private sealed interface ConnectStep {
    data object Discovering : ConnectStep
    data object AskHost : ConnectStep
    data class Checking(val host: String) : ConnectStep
    data class Found(val host: String, val line: JioRouter.RouterAccount) : ConnectStep
    data class Requesting(val host: String, val line: JioRouter.RouterAccount, val mode: JioMode) : ConnectStep
    data class NotRegistered(val host: String, val line: JioRouter.RouterAccount) : ConnectStep
    data class Registering(val host: String, val line: JioRouter.RouterAccount) : ConnectStep
    data class Otp(
        val host: String,
        val line: JioRouter.RouterAccount,
        val session: JioClient.OtpSession,
        val attempts: Int,
    ) : ConnectStep
    data class Verifying(
        val host: String,
        val line: JioRouter.RouterAccount,
        val session: JioClient.OtpSession,
        val attempts: Int,
    ) : ConnectStep
}

/**
 * "Connect JioFiber/AirFiber": finds the router, shows its voice line, gets
 * SIP credentials for this device (temporary, or permanent after an OTP), and
 * opens the account editor with them filled in. Nothing is saved until the
 * user saves the account.
 */
@Composable
fun ConnectScreen(
    jio: JioClient,
    hostname: String,
    onDone: (Account) -> Unit,
    onBack: () -> Unit,
) {
    var step by remember { mutableStateOf<ConnectStep>(ConnectStep.Discovering) }
    var hostInput by rememberSaveable { mutableStateOf("") }
    var otpInput by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val found = jio.discover()
        step = if (found != null) ConnectStep.Found(found.first, found.second) else ConnectStep.AskHost
    }

    fun requestCredentials(host: String, line: JioRouter.RouterAccount, mode: JioMode) {
        scope.launch {
            error = null
            step = ConnectStep.Requesting(host, line, mode)
            runCatching { jio.requestCredentials(host, hostname, mode) }
                .onSuccess { answer ->
                    when (answer) {
                        is JioClient.CredentialsAnswer.Credentials ->
                            onDone(JioRouter.newAccount(host, mode, answer.credentials))

                        is JioClient.CredentialsAnswer.Refused ->
                            if (mode == JioMode.PERMANENT) {
                                step = ConnectStep.NotRegistered(host, line)
                            } else {
                                error = buildString {
                                    append("The router refused temporary credentials (${answer.status})")
                                    if (answer.detail.isNotBlank()) append(": ${answer.detail}")
                                }
                                step = ConnectStep.Found(host, line)
                            }
                    }
                }
                .onFailure {
                    error = it.message ?: "Could not get credentials"
                    step = ConnectStep.Found(host, line)
                }
        }
    }

    fun sendOtp(host: String, line: JioRouter.RouterAccount) {
        scope.launch {
            error = null
            step = ConnectStep.Registering(host, line)
            runCatching { jio.registerDevice(host, hostname) }
                .onSuccess {
                    otpInput = ""
                    step = ConnectStep.Otp(host, line, it, 0)
                }
                .onFailure {
                    error = it.message ?: "The router did not send an OTP"
                    step = ConnectStep.NotRegistered(host, line)
                }
        }
    }

    fun verify(host: String, line: JioRouter.RouterAccount, session: JioClient.OtpSession, attempts: Int) {
        scope.launch {
            error = null
            step = ConnectStep.Verifying(host, line, session, attempts)
            runCatching { jio.verifyOtp(host, session, otpInput) }
                .onSuccess { requestCredentials(host, line, JioMode.PERMANENT) }
                .onFailure { failure ->
                    if (attempts + 1 >= OTP_ATTEMPTS) {
                        error = "${failure.message ?: "Wrong OTP"}. Too many attempts; send a new OTP."
                        step = ConnectStep.NotRegistered(host, line)
                    } else {
                        error = failure.message ?: "Wrong OTP"
                        otpInput = ""
                        step = ConnectStep.Otp(host, line, session, attempts + 1)
                    }
                }
        }
    }

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
            ScreenHeader("Connect JioFiber/AirFiber", onBack = onBack)

        when (val current = step) {
            ConnectStep.Discovering -> Busy("Looking for your JioFiber/AirFiber router…")

            ConnectStep.AskHost -> Card {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "No JioFiber/AirFiber router answered on its own. Enter its address — the one your browser uses for the router.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Field(hostInput, { hostInput = it }, "Router address", "e.g. 192.168.29.1")
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val host = hostInput.trim()
                            scope.launch {
                                error = null
                                step = ConnectStep.Checking(host)
                                runCatching { jio.requestAccount(host) }
                                    .onSuccess { step = ConnectStep.Found(host, it) }
                                    .onFailure {
                                        error = it.message
                                        step = ConnectStep.AskHost
                                    }
                            }
                        },
                        enabled = hostInput.isNotBlank(),
                    ) { Text("Check") }
                }
            }

            is ConnectStep.Checking -> Busy("Checking ${current.host}…")

            is ConnectStep.Found -> {
                LineDetails(current.host, current.line)
                Spacer(Modifier.height(12.dp))
                SectionTitle("Register this device")
                Card {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "The router gives this device ($hostname) its own SIP credentials.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = { requestCredentials(current.host, current.line, JioMode.PERMANENT) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Register permanently (OTP)") }
                        Hint("An OTP is sent to the registered mobile number. These credentials last.")
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { requestCredentials(current.host, current.line, JioMode.TEMPORARY) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Register temporarily (no OTP)") }
                        Hint("No OTP; the credentials expire and can be renewed automatically.")
                    }
                }
            }

            is ConnectStep.Requesting -> Busy("Getting ${current.mode.label} SIP credentials…")

            is ConnectStep.NotRegistered -> {
                LineDetails(current.host, current.line)
                Spacer(Modifier.height(12.dp))
                Card {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "This device isn't registered for permanent credentials yet. The router will send an OTP to the line's registered mobile number.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = { sendOtp(current.host, current.line) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Send OTP") }
                        TextButton(onClick = { step = ConnectStep.Found(current.host, current.line) }) {
                            Text("Back")
                        }
                    }
                }
            }

            is ConnectStep.Registering -> Busy("Asking the router to send an OTP…")

            is ConnectStep.Otp -> Card {
                Column(Modifier.padding(14.dp)) {
                    val to = current.session.sentTo ?: "the registered mobile number"
                    Text(
                        "Enter the OTP sent to $to.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Field(otpInput, { otpInput = it.filter(Char::isDigit) }, "OTP", "Digits from the SMS")
                    if (current.attempts > 0) {
                        Hint("${OTP_ATTEMPTS - current.attempts} attempts left")
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { verify(current.host, current.line, current.session, current.attempts) },
                            enabled = otpInput.isNotBlank(),
                        ) { Text("Verify") }
                        OutlinedButton(onClick = { sendOtp(current.host, current.line) }) {
                            Text("New OTP")
                        }
                    }
                }
            }

            is ConnectStep.Verifying -> Busy("Checking the OTP…")
        }

        error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(24.dp))
    }
    }
}

@Composable
private fun Busy(text: String) {
    Card {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp))
            Spacer(Modifier.size(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun LineDetails(host: String, line: JioRouter.RouterAccount) {
    SectionTitle("Your JioFiber/AirFiber line")
    Card {
        Column(Modifier.padding(10.dp)) {
            InfoRow("Phone number", line.msisdn)
            InfoRow("Router", host)
            InfoRow("Router type", line.mode)
            InfoRow("Serial number", line.serialNumber)
            InfoRow("MAC address", line.macAddress)
            InfoRow("Services", line.services.joinToString(", "))
        }
    }
}
