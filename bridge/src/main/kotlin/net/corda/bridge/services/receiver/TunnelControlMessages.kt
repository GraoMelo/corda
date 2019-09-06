package net.corda.bridge.services.receiver

import net.corda.core.identity.CordaX500Name
import net.corda.core.serialization.CordaSerializable
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.core.utilities.minutes
import java.security.cert.X509CRL
import java.security.cert.X509Certificate
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

@CordaSerializable
sealed class TunnelControlMessage

object FloatControlTopics {
    const val FLOAT_CONTROL_TOPIC = "float.control"
    const val FLOAT_SIGNING_TOPIC = "float.signing"
    const val FLOAT_DATA_TOPIC = "float.forward"
    const val FLOAT_CRL_TOPIC = "float.crl"
}

internal class ActivateFloat(val certificates: Map<String, List<X509Certificate>>, val trustStoreBytes: ByteArray, val trustStorePassword: CharArray, val maxMessageSize: Int, val bridgeCommTimeout: Duration = 1.minutes) : TunnelControlMessage()

@CordaSerializable
internal interface RequestIdContainer {
    val requestId: Long
    val digest: String
}

private val requestCounter = AtomicLong(System.currentTimeMillis()) // Initialise once with current ms value to avoid clash from previous start-ups

internal class SigningRequest(override val requestId: Long = requestCounter.incrementAndGet(), val alias: String, val sigAlgo: String, val data: ByteArray) : TunnelControlMessage(), RequestIdContainer {
    override val digest: String
        get() = "Alias: $alias, sigAlgo: $sigAlgo"
}
internal class SigningResponse(override val requestId: Long, val signature: ByteArray?) : TunnelControlMessage(), RequestIdContainer {
    override val digest: String
        get() = "Signed bytes length: ${signature?.size}"
}

internal class CrlRequest(override val requestId: Long = requestCounter.incrementAndGet(), val certificate: X509Certificate) : TunnelControlMessage(), RequestIdContainer {
    override val digest: String
        get() = "Certificate: $certificate"
}
internal class CrlResponse(override val requestId: Long, val crls: Set<X509CRL>) : TunnelControlMessage(), RequestIdContainer {
    override val digest: String
        get() = "CRL size: ${crls.size}, Sample: ${crls.take(10)}"
}
object DeactivateFloat : TunnelControlMessage()

// Placeholder for messages to facilitate float health check
internal class HealthCheckFloat(override val requestId: Long, val command: String) : TunnelControlMessage(), RequestIdContainer {
    override val digest: String
        get() = "Command: $command"
}
internal class FloatHealthyAck(override val requestId: Long, val healthy: Boolean, val narrative: String) : TunnelControlMessage(), RequestIdContainer {
    override val digest: String
        get() = "Healthy: $healthy, Narrative: $narrative"
}

@CordaSerializable
internal class FloatDataPacket(val topic: String,
                               val originalHeaders: List<Pair<String, Any?>>,
                               val originalPayload: ByteArray,
                               val sourceLegalName: CordaX500Name,
                               val sourceLink: NetworkHostAndPort,
                               val destinationLegalName: CordaX500Name,
                               val destinationLink: NetworkHostAndPort)