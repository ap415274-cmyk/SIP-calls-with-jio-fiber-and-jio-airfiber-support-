package com.pp.jiocall.sip

/** Everything the SDP answer and the RTP stream have to agree on. */
internal data class MediaPlan(
    val codec: Codec,
    val payloadType: Int,
    val host: String,
    val port: Int,
    val octetAligned: Boolean,
    val dtmfPt: Int,
    val dtmfRate: Int,
    val remote: Sdp.Remote,
    val modeSet: Set<Int>? = null,
    val explicitOctetAligned: Boolean = false,
)

/**
 * The codec, framing and peer endpoint for `remote`, or null when the peer
 * offers nothing we can code (pjmedia answers 488 in that case).  The SDP
 * answer is built from this same plan, so what we advertise and what we code
 * can never disagree — that mismatch is silent in both directions.
 *
 * Deliberately free of Android types: the whole incoming-offer decision runs
 * in plain JVM unit tests, with the microphone, sockets and telephony stack
 * replaced by the caller.
 */
/**
 * The one codec to run for an offer, or null when nothing matches.
 *
 * pjmedia picks the caller's first offered codec: its ANSWER_MULTIPLE_CODECS
 * defaults to 0 (one format in the answer) and PREFER_REMOTE_CODEC_ORDER to 1
 * (the remote's order wins), so RusTone answers Jio's offer with AMR-WB on the
 * caller's pt 110.  Our former answer listed every matching format
 * (`110 96 107`), which let the gateway run its narrowband leg and send AMR-NB
 * on pt 96 while we transmitted AMR-WB on 110 — neither party heard the other.
 */
internal fun chooseCodec(offered: List<Codec>, remote: Sdp.Remote): Codec? =
    remote.codecs.values.firstOrNull { it in offered }

internal fun planMedia(
    remote: Sdp.Remote,
    offered: List<Codec>,
    amrOctetAligned: Boolean?,
    fallbackHost: String,
): MediaPlan? {
    val codec = chooseCodec(offered, remote) ?: return null
    val remotePt = remote.codecs.entries.firstOrNull { it.value == codec }?.key ?: codec.pt
    // A peer that names 0.0.0.0 (hold, or a re-offer from a proxy) has no
    // usable media address: the signalling host is the only way in.
    val remoteHost = if (remote.address.isBlank() || remote.address == "0.0.0.0") {
        fallbackHost
    } else {
        remote.address
    }
    // RFC 4867 §8.1: an offer without octet-align means bandwidth-efficient;
    // pjmedia's AMR factory defaults the same way.
    val octetAligned = if (codec.isAmr) (amrOctetAligned ?: (remote.octetAligned[remotePt] == true)) else false
    val explicitAlign = remote.octetAligned.containsKey(remotePt)
    return MediaPlan(
        codec = codec,
        payloadType = remotePt,
        host = remoteHost,
        port = remote.port,
        octetAligned = octetAligned,
        dtmfPt = remote.dtmfPt ?: if (codec == Codec.AMR_WB) 120 else Sdp.DTMF_PT,
        dtmfRate = remote.dtmfRate ?: codec.clockRate,
        remote = remote,
        modeSet = remote.modeSets[remotePt],
        explicitOctetAligned = amrOctetAligned != null || explicitAlign,
    )
}
