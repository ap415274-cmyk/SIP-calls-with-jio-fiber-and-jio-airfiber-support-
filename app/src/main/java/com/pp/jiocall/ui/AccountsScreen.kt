package com.pp.jiocall.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pp.jiocall.data.Account
import com.pp.jiocall.data.Transport
import com.pp.jiocall.sip.AccountStatus
import com.pp.jiocall.sip.RegState

@Composable
fun AccountsScreen(
    accounts: List<Account>,
    statuses: Map<Long, AccountStatus>,
    onAdd: () -> Unit,
    onConnect: () -> Unit,
    onEdit: (Account) -> Unit,
    onToggle: (Account, Boolean) -> Unit,
    onDelete: (Account) -> Unit,
    onRenew: ((Account) -> Unit)? = null,
    onBack: (() -> Unit)? = null,
) {
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
            ScreenHeader("Accounts", onBack = onBack) {
            IconButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = "Add account")
            }
        }

        if (accounts.isEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("No accounts yet", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Connect a JioFiber or AirFiber router to get this device's SIP credentials, or add any SIP account by hand.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        accounts.forEach { account ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { onEdit(account) },
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            account.title(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            listOf(account.number(), account.host())
                                .filter { it.isNotBlank() }
                                .joinToString("  ·  "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        StatusLine(statuses[account.id])
                        if (account.provisioning != null) {
                            Text(
                                "JioFiber/AirFiber · ${account.provisioning.mode.label}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            val status = statuses[account.id]
                            if (status?.state == RegState.FAILED && onRenew != null) {
                                Spacer(Modifier.height(4.dp))
                                OutlinedButton(onClick = { onRenew(account) }) {
                                    Text("Refresh credentials", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                    Switch(
                        checked = account.enabled,
                        onCheckedChange = { onToggle(account, it) },
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
            Text("Connect JioFiber / AirFiber")
        }
        Spacer(Modifier.height(6.dp))
        OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
            Text("Add SIP account by hand")
        }
        Hint("Router accounts get their server, user and password from the router; the fields stay locked in the editor.")
        Spacer(Modifier.height(24.dp))
    }
    }
}

@Composable
fun AccountEditorScreen(
    original: Account,
    isNew: Boolean,
    status: AccountStatus?,
    routerNote: String?,
    onSave: (Account) -> Unit,
    onDelete: (Account) -> Unit,
    onRenew: (Account) -> Unit,
    onBack: () -> Unit,
    onClearRouterNote: () -> Unit,
) {
    val locked = original.provisioning != null
    var label by rememberSaveable(original.id) { mutableStateOf(original.label) }
    var displayName by rememberSaveable(original.id) { mutableStateOf(original.displayName) }
    var username by rememberSaveable(original.id) { mutableStateOf(original.username) }
    var authId by rememberSaveable(original.id) { mutableStateOf(original.authId) }
    var password by rememberSaveable(original.id) { mutableStateOf(original.password) }
    var server by rememberSaveable(original.id) { mutableStateOf(original.server) }
    var proxy by rememberSaveable(original.id) { mutableStateOf(original.proxy) }
    var domain by rememberSaveable(original.id) { mutableStateOf(original.domain) }
    var transportName by rememberSaveable(original.id) { mutableStateOf(original.transport.name) }
    val transport = runCatching { Transport.valueOf(transportName) }.getOrDefault(original.transport)
    var dialingPrefix by rememberSaveable(original.id) { mutableStateOf(original.dialingPrefix) }
    var dialPlan by rememberSaveable(original.id) { mutableStateOf(original.dialPlan) }
    var voicemail by rememberSaveable(original.id) { mutableStateOf(original.voicemailNumber) }
    var publicAddr by rememberSaveable(original.id) { mutableStateOf(original.publicAddr) }
    var hideCid by rememberSaveable(original.id) { mutableStateOf(original.hideCid) }
    var registerRefresh by rememberSaveable(original.id) { mutableStateOf(original.registerRefresh.toString()) }
    var keepAlive by rememberSaveable(original.id) { mutableStateOf(original.keepAlive.toString()) }
    var enabled by rememberSaveable(original.id) { mutableStateOf(original.enabled) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(
        locked,
        original.username,
        original.authId,
        original.password,
        original.server,
        original.proxy,
        original.domain,
        original.transport,
    ) {
        if (locked) {
            username = original.username
            authId = original.authId
            password = original.password
            server = original.server
            proxy = original.proxy
            domain = original.domain
            transportName = original.transport.name
        }
    }

    fun collect(): Account = original.copy(
        label = label.trim(),
        displayName = displayName,
        username = if (locked) original.username else username.trim(),
        authId = if (locked) original.authId else authId.trim(),
        password = if (locked) original.password else password,
        server = if (locked) original.server else server.trim(),
        proxy = if (locked) original.proxy else proxy.trim(),
        domain = if (locked) original.domain else domain.trim(),
        transport = if (locked) original.transport else transport,
        dialingPrefix = dialingPrefix,
        dialPlan = dialPlan,
        voicemailNumber = voicemail.trim(),
        publicAddr = publicAddr.trim(),
        hideCid = hideCid,
        registerRefresh = registerRefresh.toIntOrNull()?.coerceIn(60, 3_600) ?: 300,
        keepAlive = keepAlive.toIntOrNull()?.coerceIn(0, 3_600) ?: 15,
        enabled = enabled,
    )

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete account?") },
            text = { Text("Remove \"${original.title()}\" from this device? This stops incoming calls for this account.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete(original)
                    },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            },
        )
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
            ScreenHeader(if (isNew) "New account" else "Edit account", onBack = onBack) {
            if (!isNew) {
                IconButton(onClick = { showDeleteDialog = true }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete account")
                }
            }
        }

        StatusLine(status)

        if (locked) {
            Card(Modifier.padding(top = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "Set up from ${original.provisioning?.router}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Hint(
                        "The router decides the server, user, password and transport. " +
                            (if (original.provisioning?.mode == com.pp.jiocall.data.JioMode.TEMPORARY) {
                                "Temporary credentials expire; they can be renewed here."
                            } else {
                                "Permanent credentials stay until the device is removed from the router."
                            }),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { onRenew(original) }) { Text("Get new credentials") }
                    routerNote?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (routerNote != null) {
                TextButton(onClick = onClearRouterNote) { Text("Dismiss") }
            }
        }

        SectionTitle("Name")
        FieldGroup {
            Field(label, { label = it }, "Label", "e.g. Home")
            Field(displayName, { displayName = it }, "Display name", "Shown to people you call")
        }

        SectionTitle("Sign-in")
        FieldGroup {
            Field(username, { username = it; errorMessage = null }, "Username", "+91… or 1001", enabled = !locked)
            Field(authId, { authId = it }, "Authentication ID", "If different from the username", enabled = !locked)
            Field(password, { password = it }, "Password", password = true, enabled = !locked)
            Field(server, { server = it; errorMessage = null }, "Registrar", "host or host:port", enabled = !locked)
            Field(proxy, { proxy = it }, "Outbound proxy", "host:port, optional", enabled = !locked)
            Field(domain, { domain = it; errorMessage = null }, "Domain", "For numbers without a host", enabled = !locked)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Transport.entries.forEach { option ->
                    if (option == transport) {
                        Button(
                            onClick = { transportName = option.name },
                            enabled = !locked,
                            modifier = Modifier.weight(1f),
                        ) { Text(option.label, maxLines = 1) }
                    } else {
                        OutlinedButton(
                            onClick = { transportName = option.name },
                            enabled = !locked,
                            modifier = Modifier.weight(1f),
                        ) { Text(option.label, maxLines = 1) }
                    }
                }
            }
            if (locked) Hint("Locked: the router set these.")
        }

        SectionTitle("Registration")
        FieldGroup {
            Field(registerRefresh, { registerRefresh = it.filter(Char::isDigit) }, "Refresh (seconds)", keyboardType = KeyboardType.Number)
            Field(keepAlive, { keepAlive = it.filter(Char::isDigit) }, "Keep-alive (seconds, 0 off)", keyboardType = KeyboardType.Number)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Enabled", style = MaterialTheme.typography.bodyLarge)
                    Hint("Register and receive calls")
                }
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }
        }

        SectionTitle("Calling")
        FieldGroup {
            Field(dialingPrefix, { dialingPrefix = it }, "Dialling prefix", "Added to every number")
            Field(dialPlan, { dialPlan = it }, "Dial plan", "pattern=>replacement, | separated")
            Field(voicemail, { voicemail = it }, "Voicemail number", "Optional")
            Field(publicAddr, { publicAddr = it }, "Public address", "Address to advertise in SDP")
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Hide caller ID", style = MaterialTheme.typography.bodyLarge)
                    Hint("Sends an anonymous From and Privacy: id")
                }
                Switch(checked = hideCid, onCheckedChange = { hideCid = it })
            }
        }

        errorMessage?.let { msg ->
            Spacer(Modifier.height(10.dp))
            Text(
                text = msg,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                val candidate = collect()
                if (!locked && (candidate.username.isBlank() || (candidate.server.isBlank() && candidate.domain.isBlank()))) {
                    errorMessage = "Username and Registrar (or Domain) are required."
                } else {
                    errorMessage = null
                    onSave(candidate)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (isNew) "Save account" else "Save changes") }
        Spacer(Modifier.height(6.dp))
        if (!isNew) {
            TextButton(onClick = { showDeleteDialog = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Delete this account", color = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    }
}

