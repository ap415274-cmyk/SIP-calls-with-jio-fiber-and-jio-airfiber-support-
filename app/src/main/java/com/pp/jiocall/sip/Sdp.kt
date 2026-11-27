package com.pp.jiocall.sip

/** Codecs this engine can actually send and receive. */
enum class Codec(val mime: String, val pt: Int, val clockRate: Int) {
    AMR_WB("AMR-WB", 96, 16000),
    AMR_NB("AMR", 97, 8000),
    PCMA("PCMA", 8, 8000),
    PCMU("PCMU", 0, 8000);

    /** 20 ms of audio. */
    val samplesPerFrame: Int get() = clockRate / 50

    val isAmr: Boolean get() = this == AMR_WB || this == AMR_NB

    val androidMime: String
        get() = when (this) {
            AMR_WB -> "audio/amr-wb"
            AMR_NB -> "audio/3gpp"
            else -> ""
        }

    companion object {
        fun fromMime(mime: String, clockRate: Int): Codec? = entries.firstOrNull {
            it.mime.equals(mime.trim(), ignoreCase = true) && it.clockRate == clockRate
        }

        /** A settings entry like `AMR-WB/16000/1`. */
        fun fromSetting(value: String): Codec? {
            val name = value.substringBefore('/').trim()
            val clock = value.split('/').getOrNull(1)?.toIntOrNull()
            return entries.firstOrNull {
                it.mime.equals(name, ignoreCase = true) && (clock == null || it.clockRate == clock)
            }
        }
    }
}

/** SDP with the payload types this engine understands. */
object Sdp {
    const val DTMF_PT = 101

    data class Remote(
        val address: String,
        val port: Int,
        val payloads: List<Int>,
        val codecs: Map<Int, Codec>,
        val octetAligned: Map<Int, Boolean> = emptyMap(),
        val modeSets: Map<Int, Set<Int>> = emptyMap(),
        val dtmfPt: Int? = null,
        val dtmfRate: Int? = null,
        val sendOnly: Boolean,
        val recvOnly: Boolean,
        val inactive: Boolean,
    )

    /**
     * The SDP address type for [address].  Writing `IN IP4` in front of an
     * IPv6 literal is invalid SDP: the peer then has no usable media address
     * and the call is silent in both directions.  Jio's media is IPv6 on
     * parts of its network, so the family has to follow the address we
     * actually advertise.
     */
    private fun family(address: String): String = if (address.contains(':')) "IP6" else "IP4"

    fun offer(address: String, port: Int, codecs: List<Codec>, sessionId: Long): String {
        val types = codecs.joinToString(" ") { it.pt.toString() } + " 120 121"
        val family = family(address)
        val builder = StringBuilder()
        builder.append("v=0\r\n")
        builder.append("o=- $sessionId $sessionId IN $family $address\r\n")
        builder.append("s=pjmedia\r\n")
        builder.append("c=IN $family $address\r\n")
        builder.append("b=AS:41\r\n")
        builder.append("t=0 0\r\n")
        builder.append("a=X-nat:0\r\n")
        builder.append("m=audio $port RTP/AVP $types\r\n")
        builder.append("c=IN $family $address\r\n")
        builder.append("b=TIAS:23850\r\n")
        builder.append("a=rtcp:${port + 1} IN $family $address\r\n")
        builder.append("a=sendrecv\r\n")
        for (codec in codecs) {
            builder.append("a=rtpmap:${codec.pt} ${codec.mime}/${codec.clockRate}\r\n")
            if (codec.isAmr) {
                builder.append("a=fmtp:${codec.pt} octet-align=1;mode-change-capability=2;max-red=0\r\n")
            }
        }
        builder.append("a=rtpmap:120 telephone-event/16000\r\n")
        builder.append("a=fmtp:120 0-16\r\n")
        builder.append("a=rtpmap:121 telephone-event/8000\r\n")
        builder.append("a=fmtp:121 0-16\r\n")
        return builder.toString()
    }

    /**
     * A compliant RFC 3264 SDP answer for [plan], or null when the offer cannot
     * be read (the caller then sends 488, as pjmedia does, instead of putting a
     * fresh offer in a 200 OK).
     *
     * The answer names exactly the one codec the plan runs.  RFC 3264 permits
     * several formats, and listing every offer that matched is what broke
     * incoming calls on Jio: its gateway has a single codec per leg, took the
     * AMR-NB payload type out of our multi-format answer, and dropped every
     * RTP packet we sent on the AMR-WB payload type — the called party heard
     * nothing while the line looked connected.  pjmedia answers with the one
     * negotiated format for the same reason.
     */
    internal fun createAnswer(
        remoteOffer: String,
        address: String,
        port: Int,
        plan: MediaPlan,
        sessionId: Long,
    ): String? {
        val parsed = parse(remoteOffer) ?: return null
        val dtmfPt = parsed.dtmfPt
        // An answer may only list a format the offer listed.
        val offeredPts = parsed.payloads.toSet()
        if (plan.payloadType !in offeredPts) return null

        val ptList = mutableListOf(plan.payloadType)
        if (dtmfPt != null && dtmfPt != plan.payloadType) ptList.add(dtmfPt)

        // RFC 3264 §6.1: the answer's direction mirrors the offer's.
        val direction = when {
            parsed.inactive -> "inactive"
            parsed.sendOnly -> "recvonly"
            parsed.recvOnly -> "sendonly"
            else -> "sendrecv"
        }

        val family = family(address)
        val types = ptList.joinToString(" ")
        val builder = StringBuilder()
        builder.append("v=0\r\n")
        builder.append("o=- $sessionId $sessionId IN $family $address\r\n")
        builder.append("s=pjmedia\r\n")
        builder.append("c=IN $family $address\r\n")
        builder.append("b=AS:41\r\n")
        builder.append("t=0 0\r\n")
        builder.append("a=X-nat:0\r\n")
        builder.append("m=audio $port RTP/AVP $types\r\n")
        builder.append("c=IN $family $address\r\n")
        builder.append("b=TIAS:23850\r\n")
        builder.append("a=rtcp:${port + 1} IN $family $address\r\n")
        builder.append("a=$direction\r\n")
        builder.append("a=rtpmap:${plan.payloadType} ${plan.codec.mime}/${plan.codec.clockRate}\r\n")
        if (plan.codec.isAmr) {
            // RFC 4867 §8.1: only octet-aligned has to be announced; an
            // absent parameter means bandwidth-efficient.  Preserve any
            // peer mode-set restriction (e.g. Jio's mode-set=0,1,2,3).
            val modePart = plan.modeSet?.takeIf { it.isNotEmpty() }
                ?.sorted()
                ?.joinToString(",")
                ?.let { "mode-set=$it;" }
                .orEmpty()
            if (plan.octetAligned) {
                builder.append("a=fmtp:${plan.payloadType} octet-align=1;${modePart}mode-change-capability=2;max-red=0\r\n")
            } else if (modePart.isNotEmpty()) {
                builder.append("a=fmtp:${plan.payloadType} ${modePart}mode-change-capability=2;max-red=0\r\n")
            }
        }
        if (dtmfPt != null) {
            val rate = parsed.dtmfRate ?: plan.codec.clockRate
            builder.append("a=rtpmap:$dtmfPt telephone-event/$rate\r\n")
            builder.append("a=fmtp:$dtmfPt 0-15\r\n")
        }
        return builder.toString()
    }

    /**
     * The AMR framing (RFC 4867 §8.1) that a call must both advertise in its
     * answer and code with: the offer's explicit octet-align for the codec we
     * are going to use, otherwise bandwidth-efficient — "If 1, octet-aligned
     * operation SHALL be used.  If 0 or if not present, bandwidth-efficient
     * operation is employed" (RFC 4867 §8.1 media type registration), which
     * is also what pjmedia's AMR codec defaults to.
     */
    fun negotiateAmrAlignment(remoteSdp: String, preferredCodecs: List<Codec>): Boolean {
        if (remoteSdp.isBlank()) return false
        val parsed = parse(remoteSdp) ?: return false
        val codec = preferredCodecs.firstOrNull { candidate ->
            candidate.isAmr && parsed.codecs.values.any { it == candidate }
        } ?: return false
        val pt = parsed.codecs.entries.firstOrNull { it.value == codec }?.key ?: return false
        return parsed.octetAligned[pt] == true
    }

    fun parse(body: String): Remote? {
        var address = ""
        var connectionAddress = address
        var mediaPort = -1
        val payloads = mutableListOf<Int>()
        val codecs = mutableMapOf<Int, Codec>()
        val octetAligned = mutableMapOf<Int, Boolean>()
        val modeSets = mutableMapOf<Int, Set<Int>>()
        var dtmfPt: Int? = null
        var dtmfRate: Int? = null
        var sendOnly = false
        var recvOnly = false
        var inactive = false
        var inAudio = false

        for (raw in body.split("\r\n", "\n")) {
            val line = raw.trim()
            when {
                line.startsWith("c=") -> {
                    val parts = line.removePrefix("c=").split(' ')
                    if (parts.size >= 3) {
                        connectionAddress = parts[2].substringBefore('/')
                        if (inAudio || address.isEmpty()) address = connectionAddress
                    }
                }

                line.startsWith("m=") -> {
                    val parts = line.removePrefix("m=").split(' ')
                    if (parts.firstOrNull() == "audio") {
                        inAudio = true
                        mediaPort = parts.getOrNull(1)?.toIntOrNull() ?: -1
                        payloads += parts.drop(3).mapNotNull { it.toIntOrNull() }
                    } else {
                        inAudio = false
                    }
                }

                line.startsWith("a=rtpmap:") -> {
                    if (!inAudio) continue
                    val rest = line.removePrefix("a=rtpmap:")
                    val pt = rest.substringBefore(' ').toIntOrNull() ?: continue
                    val name = rest.substringAfter(' ', "").substringBefore('/')
                    val clock = rest.substringAfter(' ', "").split('/').getOrNull(1)?.toIntOrNull()
                    if (name.isNotEmpty() && clock != null) {
                        if (name.equals("telephone-event", ignoreCase = true)) {
                            dtmfPt = pt
                            dtmfRate = clock
                        } else {
                            Codec.fromMime(name, clock)?.let { codecs[pt] = it }
                        }
                    }
                }

                line.startsWith("a=fmtp:") -> {
                    if (!inAudio) continue
                    val rest = line.removePrefix("a=fmtp:")
                    val pt = rest.substringBefore(' ').toIntOrNull() ?: continue
                    val params = rest.substringAfter(' ', "")
                    if (params.contains("octet-align=1", ignoreCase = true)) {
                        octetAligned[pt] = true
                    } else if (params.contains("octet-align=0", ignoreCase = true)) {
                        octetAligned[pt] = false
                    }
                    val modeMatch = MODE_SET_REGEX.find(params)
                    if (modeMatch != null) {
                        val parsedModes = modeMatch.groupValues[1]
                            .split(',')
                            .mapNotNull { it.trim().toIntOrNull() }
                            .toSet()
                        if (parsedModes.isNotEmpty()) modeSets[pt] = parsedModes
                    }
                }

                line == "a=sendonly" -> if (inAudio) sendOnly = true
                line == "a=recvonly" -> if (inAudio) recvOnly = true
                line == "a=inactive" -> if (inAudio) inactive = true
                line == "a=sendrecv" -> {
                    sendOnly = false
                    recvOnly = false
                    inactive = false
                }
            }
        }
        if (mediaPort <= 0) return null
        if (address.isEmpty()) address = connectionAddress
        if (address.isEmpty()) return null
        // Static payload types need no rtpmap.
        for (pt in payloads) {
            if (codecs[pt] != null) continue
            when (pt) {
                0 -> codecs[pt] = Codec.PCMU
                8 -> codecs[pt] = Codec.PCMA
            }
        }
        return Remote(address, mediaPort, payloads, codecs, octetAligned, modeSets, dtmfPt, dtmfRate, sendOnly, recvOnly, inactive)
    }

    private val MODE_SET_REGEX = Regex("""mode-set\s*=\s*([0-9,\s]+)""", RegexOption.IGNORE_CASE)
}
