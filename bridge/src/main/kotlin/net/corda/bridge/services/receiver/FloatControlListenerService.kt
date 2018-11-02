package net.corda.bridge.services.receiver

import net.corda.bridge.services.api.*
import net.corda.bridge.services.receiver.FloatControlTopics.FLOAT_DATA_TOPIC
import net.corda.bridge.services.util.ServiceStateCombiner
import net.corda.bridge.services.util.ServiceStateHelper
import net.corda.core.identity.CordaX500Name
import net.corda.core.serialization.SerializationDefaults
import net.corda.core.serialization.deserialize
import net.corda.core.serialization.serialize
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.core.utilities.contextLogger
import net.corda.nodeapi.internal.ArtemisMessagingComponent.Companion.P2P_PREFIX
import net.corda.nodeapi.internal.config.MutualSslConfiguration
import net.corda.nodeapi.internal.protonwrapper.messages.MessageStatus
import net.corda.nodeapi.internal.protonwrapper.messages.ReceivedMessage
import net.corda.nodeapi.internal.protonwrapper.netty.AMQPConfiguration
import net.corda.nodeapi.internal.protonwrapper.netty.AMQPServer
import net.corda.nodeapi.internal.protonwrapper.netty.ConnectionChange
import rx.Subscription
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock


class FloatControlListenerService(val conf: FirewallConfiguration,
                                  val maximumMessageSize: Int,
                                  val auditService: FirewallAuditService,
                                  private val amqpListener: BridgeAMQPListenerService,
                                  private val stateHelper: ServiceStateHelper = ServiceStateHelper(log)) : FloatControlService, ServiceStateSupport by stateHelper {
    companion object {
        private val log = contextLogger()
    }

    private val lock = ReentrantLock()
    private val statusFollower = ServiceStateCombiner(listOf(auditService, amqpListener))
    private var statusSubscriber: Subscription? = null
    private var incomingMessageSubscriber: Subscription? = null
    private var connectSubscriber: Subscription? = null
    private var receiveSubscriber: Subscription? = null
    private var amqpControlServer: AMQPServer? = null
    private val sslConfiguration: MutualSslConfiguration = conf.floatOuterConfig?.customSSLConfiguration
            ?: conf.p2pSslOptions
    private val floatControlAddress = conf.floatOuterConfig!!.floatAddress
    private val floatClientName = conf.floatOuterConfig!!.expectedCertificateSubject
    private var activeConnectionInfo: ConnectionChange? = null
    private var forwardAddress: NetworkHostAndPort? = null
    private var forwardLegalName: String? = null

    override fun start() {
        statusSubscriber = statusFollower.activeChange.subscribe({
            if (it) {
                startControlListener()
            } else {
                stopControlListener()
            }
            stateHelper.active = it
        }, { log.error("Error in state change", it) })
        incomingMessageSubscriber = amqpListener.onReceive.subscribe({
            forwardReceivedMessage(it)
        }, { log.error("Error in state change", it) })
    }

    private fun startControlListener() {
        lock.withLock {
            val keyStore = sslConfiguration.keyStore.get()
            val trustStore = sslConfiguration.trustStore.get()
            val amqpConfig = object : AMQPConfiguration {
                override val userName: String? = null
                override val password: String? = null
                override val keyStore = keyStore
                override val trustStore = trustStore
                override val crlCheckSoftFail: Boolean = conf.crlCheckSoftFail
                override val maxMessageSize: Int = maximumMessageSize
                override val trace: Boolean = conf.enableAMQPPacketTrace
                override val healthCheckPhrase = conf.healthCheckPhrase
            }
            val controlServer = AMQPServer(floatControlAddress.host,
                    floatControlAddress.port,
                    amqpConfig)
            connectSubscriber = controlServer.onConnection.subscribe({ onConnectToControl(it) }, { log.error("Connection event error", it) })
            receiveSubscriber = controlServer.onReceive.subscribe({ onControlMessage(it) }, { log.error("Receive event error", it) })
            amqpControlServer = controlServer
            controlServer.start()
        }
    }

    override fun stop() {
        lock.withLock {
            stateHelper.active = false
            stopControlListener()
            statusSubscriber?.unsubscribe()
            statusSubscriber = null
        }
    }

    private fun stopControlListener() {
        lock.withLock {
            if (amqpListener.running) {
                amqpListener.wipeKeysAndDeactivate()
            }
            connectSubscriber?.unsubscribe()
            connectSubscriber = null
            amqpControlServer?.stop()
            receiveSubscriber?.unsubscribe()
            receiveSubscriber = null
            amqpControlServer = null
            activeConnectionInfo = null
            forwardAddress = null
            forwardLegalName = null
            incomingMessageSubscriber?.unsubscribe()
            incomingMessageSubscriber = null
        }
    }

    private fun onConnectToControl(connectionChange: ConnectionChange) {
        auditService.statusChangeEvent("Connection change on float control port $connectionChange")
        lock.withLock {
            val currentConnection = activeConnectionInfo
            if (currentConnection != null) {
                // If there is a new valid TLS connection kill old connection.
                // Else if this event signals loss of current connection wipe the keys
                if (connectionChange.connected || (currentConnection.remoteAddress == connectionChange.remoteAddress)) {
                    if (amqpListener.running) {
                        amqpListener.wipeKeysAndDeactivate()
                    }
                    amqpControlServer?.dropConnection(currentConnection.remoteAddress)
                    activeConnectionInfo = null
                    forwardAddress = null
                    forwardLegalName = null
                }
            }
            if (connectionChange.connected) {
                if (connectionChange.remoteCert != null) {
                    val certificateSubject = CordaX500Name.parse(connectionChange.remoteCert!!.subjectDN.toString())
                    if (certificateSubject == floatClientName) {
                        activeConnectionInfo = connectionChange
                    } else {
                        amqpControlServer?.dropConnection(connectionChange.remoteAddress)
                    }
                } else {
                    amqpControlServer?.dropConnection(connectionChange.remoteAddress)
                }
            }
        }
    }

    private fun onControlMessage(receivedMessage: ReceivedMessage) {
        if (!receivedMessage.checkTunnelControlTopic()) {
            auditService.packetDropEvent(receivedMessage, "Invalid control topic packet received on topic ${receivedMessage.topic}!!", RoutingDirection.INBOUND)
            receivedMessage.complete(true)
            return
        }
        val controlMessage = try {
            if (CordaX500Name.parse(receivedMessage.sourceLegalName) != floatClientName) {
                auditService.packetDropEvent(receivedMessage, "Invalid control source legal name!!", RoutingDirection.INBOUND)
                receivedMessage.complete(true)
                return
            }
            receivedMessage.payload.deserialize<TunnelControlMessage>()
        } catch (ex: Exception) {
            receivedMessage.complete(true)
            return
        }
        lock.withLock {
            when (controlMessage) {
                is ActivateFloat -> {
                    log.info("Received Tunnel Activate message")
                    amqpListener.provisionKeysAndActivate(controlMessage.keyStoreBytes,
                            controlMessage.keyStorePassword,
                            controlMessage.keyStorePrivateKeyPassword,
                            controlMessage.trustStoreBytes,
                            controlMessage.trustStorePassword)
                    forwardAddress = receivedMessage.sourceLink
                    forwardLegalName = receivedMessage.sourceLegalName
                }
                is DeactivateFloat -> {
                    log.info("Received Tunnel Deactivate message")
                    if (amqpListener.running) {
                        amqpListener.wipeKeysAndDeactivate()
                    }
                    forwardAddress = null
                    forwardLegalName = null

                }
            }
        }
        receivedMessage.complete(true)
    }

    private fun forwardReceivedMessage(message: ReceivedMessage) {
        val amqpControl = lock.withLock {
            if (amqpControlServer == null ||
                    activeConnectionInfo == null ||
                    forwardLegalName == null ||
                    forwardAddress == null ||
                    !stateHelper.active) {
                null
            } else {
                amqpControlServer
            }
        }
        if (amqpControl == null) {
            message.complete(true) // consume message so it isn't resent forever
            return
        }
        if (!message.topic.startsWith(P2P_PREFIX)) {
            auditService.packetDropEvent(message, "Message topic is not a valid peer namespace ${message.topic}", RoutingDirection.INBOUND)
            message.complete(true) // consume message so it isn't resent forever
            return
        }
        val appProperties = message.applicationProperties.map { Pair(it.key, it.value) }.toList()
        try {
            val wrappedMessage = FloatDataPacket(message.topic,
                    appProperties,
                    message.payload,
                    CordaX500Name.parse(message.sourceLegalName),
                    message.sourceLink,
                    CordaX500Name.parse(message.destinationLegalName),
                    message.destinationLink)
            val amqpForwardMessage = amqpControl.createMessage(wrappedMessage.serialize(context = SerializationDefaults.P2P_CONTEXT).bytes,
                    FLOAT_DATA_TOPIC,
                    forwardLegalName!!,
                    forwardAddress!!,
                    emptyMap())
            amqpForwardMessage.onComplete.then { message.complete(it.get() == MessageStatus.Acknowledged) }
            amqpControl.write(amqpForwardMessage)
            auditService.packetAcceptedEvent(message, RoutingDirection.INBOUND)
        } catch (ex: Exception) {
            log.error("Failed to forward message", ex)
            message.complete(false)
        }
    }

}