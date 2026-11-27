package com.pp.jiocall

import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pp.jiocall.data.Account
import com.pp.jiocall.data.AppSettings
import com.pp.jiocall.data.Transport
import com.pp.jiocall.sip.CallActionReceiver
import com.pp.jiocall.sip.CallService
import com.pp.jiocall.sip.CallSnapshot
import com.pp.jiocall.sip.CallState
import com.pp.jiocall.sip.SipAccount
import com.pp.jiocall.sip.SipCall
import com.pp.jiocall.sip.SipEngine
import com.pp.jiocall.ui.CallScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class IncomingDeclineInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun callScreenSlideControlsRequireSwipeGestureAndTriggerCleanly() {
        val primaryDeclines = AtomicInteger(0)
        val primaryAnswers = AtomicInteger(0)
        val waitingDeclines = AtomicInteger(0)

        val primary = CallSnapshot(
            callId = "incoming-primary-1",
            accountId = 1L,
            accountTitle = "JioFiber",
            outgoing = false,
            number = "+919876543210",
            name = "Test Caller",
            state = CallState.RINGING,
            muted = false,
            onHold = false,
            startedAt = 0L,
            error = null,
        )
        val waiting = CallSnapshot(
            callId = "incoming-waiting-2",
            accountId = 1L,
            accountTitle = "JioFiber",
            outgoing = false,
            number = "+919123456789",
            name = "Waiting Caller",
            state = CallState.RINGING,
            muted = false,
            onHold = false,
            startedAt = 0L,
            error = null,
        )

        var waitingState by mutableStateOf<CallSnapshot?>(null)

        composeRule.setContent {
            CallScreen(
                call = primary,
                speakerOn = false,
                onAnswer = { primaryAnswers.incrementAndGet() },
                onHangup = { primaryDeclines.incrementAndGet() },
                onMute = {},
                onHold = {},
                onSpeaker = {},
                onDtmf = {},
                secondaryCall = waitingState,
                onDeclineWaiting = { waitingDeclines.incrementAndGet() },
            )
        }

        // Save visual render of the incoming CallScreen UI for pixel inspection
        composeRule.waitForIdle()
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val dir = appContext.getExternalFilesDir(null) ?: appContext.filesDir
        val outFile = File(dir, "incoming_call_ui.png")
        val bmp = runCatching { composeRule.onRoot().captureToImage().asAndroidBitmap() }
            .getOrElse { InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() }
        FileOutputStream(outFile).use { out ->
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }
        assertTrue("Expected screenshot file ${outFile.absolutePath}", outFile.exists() && outFile.length() > 0L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val resolver = appContext.contentResolver
                resolver.delete(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                    arrayOf("jiocall_incoming_call_ui.png"),
                )
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "jiocall_incoming_call_ui.png")
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                    }
                    cv.clear()
                    cv.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, cv, null, null)
                }
            }
        }

        // Stationary pocket tap must NOT accidentally decline or answer, and should surface slide hint.
        composeRule.onNodeWithContentDescription("Decline").assertIsDisplayed().performTouchInput { click() }
        composeRule.onNodeWithText("Slide to decline").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Answer").assertIsDisplayed().performTouchInput { click() }
        composeRule.onNodeWithText("Slide to answer").assertIsDisplayed()
        composeRule.waitForIdle()
        assertEquals("Stationary tap must not trigger decline", 0, primaryDeclines.get())
        assertEquals("Stationary tap must not trigger answer", 0, primaryAnswers.get())

        // Sliding outward/up triggers Decline and Answer immediately on release.
        composeRule.onNodeWithContentDescription("Decline").performTouchInput {
            swipeUp(startY = centerY, endY = centerY - 240f, durationMillis = 120)
        }
        composeRule.onNodeWithContentDescription("Answer").performTouchInput {
            swipeUp(startY = centerY, endY = centerY - 240f, durationMillis = 120)
        }
        composeRule.waitForIdle()
        assertEquals("Slide should trigger primary decline once", 1, primaryDeclines.get())
        assertEquals("Slide should trigger primary answer once", 1, primaryAnswers.get())

        // Accessibility (TalkBack) click action on primary slide handle works too.
        composeRule.onNodeWithContentDescription("Decline")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        assertEquals("Expected primary accessibility click callback", 2, primaryDeclines.get())

        // SecondaryCallBanner waiting "Decline" button stays single-tap accessible.
        composeRule.runOnUiThread { waitingState = waiting }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Decline waiting call")
            .assertIsDisplayed()
            .performClick()
        composeRule.waitForIdle()
        assertEquals("Expected secondary waiting decline callback", 1, waitingDeclines.get())
    }

    @Test
    fun sipIncomingDeclineSendsClean486ClearsUiImmediatelyAndBlocksRetransmit() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context.applicationContext as App
        val engine = app.engine

        // Spin up a real local UDP SIP server to capture packets and feed an incoming INVITE.
        val server = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 4000
        val receivedPackets = mutableListOf<String>()
        val registerLatch = CountDownLatch(1)
        val declineLatch = CountDownLatch(1)
        val retransmitDeclineLatch = CountDownLatch(1)
        var clientAddr: InetAddress? = null
        var clientPort = 0

        val rxStopped = AtomicBoolean(false)
        val rxThread = Thread {
            val buf = ByteArray(8192)
            while (!rxStopped.get() && !server.isClosed) {
                val pkt = DatagramPacket(buf, buf.size)
                val ok = runCatching { server.receive(pkt) }.isSuccess
                if (!ok || rxStopped.get() || server.isClosed) break
                clientAddr = pkt.address
                clientPort = pkt.port
                val msg = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                synchronized(receivedPackets) { receivedPackets.add(msg) }
                if (msg.startsWith("REGISTER ")) {
                    val callId = Regex("(?im)^Call-ID:\\s*(.+)$").find(msg)?.groupValues?.get(1)?.trim().orEmpty()
                    val cseq = Regex("(?im)^CSeq:\\s*(.+)$").find(msg)?.groupValues?.get(1)?.trim().orEmpty()
                    val from = Regex("(?im)^From:\\s*(.+)$").find(msg)?.groupValues?.get(1)?.trim().orEmpty()
                    val to = Regex("(?im)^To:\\s*(.+)$").find(msg)?.groupValues?.get(1)?.trim().orEmpty()
                    val via = Regex("(?im)^Via:\\s*(.+)$").find(msg)?.groupValues?.get(1)?.trim().orEmpty()
                    val contact = Regex("(?im)^Contact:\\s*(.+)$").find(msg)?.groupValues?.get(1)?.trim().orEmpty()
                    val resp200 = buildString {
                        append("SIP/2.0 200 OK\r\n")
                        append("Via: $via\r\n")
                        append("From: $from\r\n")
                        append("To: $to;tag=srv1\r\n")
                        append("Call-ID: $callId\r\n")
                        append("CSeq: $cseq\r\n")
                        append("Contact: $contact;expires=300\r\n")
                        append("Content-Length: 0\r\n\r\n")
                    }.toByteArray(Charsets.UTF_8)
                    if (!rxStopped.get() && !server.isClosed) {
                        try {
                            server.send(DatagramPacket(resp200, resp200.size, pkt.address, pkt.port))
                        } catch (_: SocketException) {
                            break
                        }
                    }
                    registerLatch.countDown()
                } else if (msg.startsWith("SIP/2.0 486 ")) {
                    declineLatch.countDown()
                    if (synchronized(receivedPackets) { receivedPackets.count { it.startsWith("SIP/2.0 486 ") } } >= 2) {
                        retransmitDeclineLatch.countDown()
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        try {
            val testAcc = Account(
                id = 999L,
                label = "TestLoopback",
                server = "127.0.0.1:${server.localPort}",
                domain = "127.0.0.1",
                username = "+919999888877",
                transport = Transport.UDP,
                enabled = true,
            )
            engine.apply(listOf(testAcc), AppSettings(backgroundService = false, ringtone = false))
            assertTrue("Mock SIP REGISTER did not complete", registerLatch.await(3, TimeUnit.SECONDS))

            val incomingCallId = "test-decline-call-${System.currentTimeMillis()}@127.0.0.1"
            val rawInvite = """
                INVITE sip:+919999888877@127.0.0.1:5060 SIP/2.0
                Via: SIP/2.0/UDP 127.0.0.1:${server.localPort};branch=z9hG4bKbranch1
                Record-Route: <sip:127.0.0.1:${server.localPort};lr>
                From: "Decline Caller" <sip:+919876543210@127.0.0.1>;tag=caller1
                To: <sip:+919999888877@127.0.0.1>
                Call-ID: $incomingCallId
                CSeq: 1 INVITE
                Contact: <sip:+919876543210@127.0.0.1:${server.localPort}>
                Content-Length: 0

            """.trimIndent().replace("\n", "\r\n").toByteArray(Charsets.UTF_8)

            server.send(DatagramPacket(rawInvite, rawInvite.size, clientAddr!!, clientPort))

            // Wait for engine to enter RINGING state and CallService notification 43 to appear.
            val deadline = System.currentTimeMillis() + 3000L
            while (engine.call.value?.callId != incomingCallId && System.currentTimeMillis() < deadline) {
                Thread.sleep(30)
            }
            assertEquals(CallState.RINGING, engine.call.value?.state)
            Thread.sleep(250)

            // Simulate user tapping Notification Decline via BroadcastReceiver (CallActionReceiver).
            CallActionReceiver().onReceive(context, Intent(context, CallActionReceiver::class.java).apply {
                action = CallService.ACTION_DECLINE
            })

            // UI snapshot MUST clear IMMEDIATELY (zero 1.2s linger!).
            assertNull("CallSnapshot must clear immediately upon declining incoming call", engine.call.value)

            // Notification ID 43 MUST be cancelled immediately.
            val nm = context.getSystemService(NotificationManager::class.java)
            Thread.sleep(150)
            assertFalse(
                "Incoming notification (id=43) must be dismissed on Decline",
                nm.activeNotifications.any { it.id == 43 },
            )

            // SIP peer must receive RFC-clean 486 Busy Here (no Record-Route / Contact).
            assertTrue("Expected SIP/2.0 486 Busy Here over UDP", declineLatch.await(3, TimeUnit.SECONDS))
            val pkt486 = synchronized(receivedPackets) { receivedPackets.first { it.startsWith("SIP/2.0 486 ") } }
            assertFalse("486 must not contain Record-Route", pkt486.contains("Record-Route:", ignoreCase = true))
            assertFalse("486 must not contain Contact", pkt486.contains("Contact:", ignoreCase = true))

            // Retransmitted INVITE for the already-declined Call-ID must NOT re-ring the UI!
            server.send(DatagramPacket(rawInvite, rawInvite.size, clientAddr!!, clientPort))
            assertTrue("Retransmitted INVITE must be answered with cached 486", retransmitDeclineLatch.await(3, TimeUnit.SECONDS))
            Thread.sleep(150)
            assertNull("Retransmitted INVITE must never re-open incoming CallScreen", engine.call.value)
        } finally {
            rxStopped.set(true)
            runCatching { engine.stopAll() }
            runCatching { server.close() }
            rxThread.join(1000)
        }
    }
}
