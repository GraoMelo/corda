package net.corda.testing.node

import com.google.common.collect.MutableClassToInstanceMap
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions
import net.corda.core.cordapp.CordappProvider
import net.corda.core.crypto.*
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.StateMachineRunId
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.PartyAndCertificate
import net.corda.core.messaging.DataFeed
import net.corda.core.messaging.FlowHandle
import net.corda.core.messaging.FlowProgressHandle
import net.corda.core.node.*
import net.corda.core.node.services.*
import net.corda.core.serialization.SerializeAsToken
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.core.transactions.SignedTransaction
import net.corda.node.VersionInfo
import net.corda.node.internal.StateLoaderImpl
import net.corda.node.internal.cordapp.CordappLoader
import net.corda.node.services.api.StateMachineRecordedTransactionMappingStorage
import net.corda.node.services.api.VaultServiceInternal
import net.corda.node.services.api.WritableTransactionStorage
import net.corda.node.services.config.configOf
import net.corda.node.services.identity.InMemoryIdentityService
import net.corda.node.services.keys.freshCertificate
import net.corda.node.services.keys.getSigner
import net.corda.node.services.persistence.HibernateConfiguration
import net.corda.node.services.persistence.InMemoryStateMachineRecordedTransactionMappingStorage
import net.corda.node.services.schema.HibernateObserver
import net.corda.node.services.schema.NodeSchemaService
import net.corda.node.services.transactions.InMemoryTransactionVerifierService
import net.corda.node.services.vault.NodeVaultService
import net.corda.node.utilities.CordaPersistence
import net.corda.node.utilities.configureDatabase
import net.corda.testing.*
import org.bouncycastle.operator.ContentSigner
import rx.Observable
import rx.subjects.PublishSubject
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import java.sql.Connection
import java.time.Clock
import java.util.*

/**
 * A singleton utility that only provides a mock identity, key and storage service. However, this is sufficient for
 * building chains of transactions and verifying them. It isn't sufficient for testing flows however.
 */
open class MockServices(
        cordappLoader: CordappLoader,
        override val validatedTransactions: WritableTransactionStorage,
        protected val stateLoader: StateLoaderImpl = StateLoaderImpl(validatedTransactions),
        private val initialIdentityName: CordaX500Name = MEGA_CORP.name,
        vararg val keys: KeyPair
) : ServiceHub, StateLoader by stateLoader {
    companion object {
        private val MOCK_IDENTITIES = listOf(MEGA_CORP_IDENTITY, MINI_CORP_IDENTITY, DUMMY_CASH_ISSUER_IDENTITY, DUMMY_NOTARY_IDENTITY)

        @JvmStatic
        val MOCK_VERSION_INFO = VersionInfo(1, "Mock release", "Mock revision", "Mock Vendor")

        private fun readDatabaseConfig(nodeName: String? = null, postifx: String? = null): Config {
            val standarizedNodeName = if (nodeName!= null) nodeName.replace(" ", "").replace("-", "_") else null
            //in case of H2, the same db instance runs for all integration tests, so adding additional variable postfix to use unique db user/schema each time
            val h2InstanceName =  if (postifx != null) standarizedNodeName + "_" + postifx  else standarizedNodeName
            val parseOptions = ConfigParseOptions.defaults()
            val databaseConfig = ConfigFactory.parseResources(System.getProperty("databaseProvider") + ".conf", parseOptions.setAllowMissing(true))
            val fixedOverride = ConfigFactory.parseString("baseDirectory = \"\"")
            val nodeOrganizationNameConfig = if (standarizedNodeName != null) configOf("nodeOrganizationName" to standarizedNodeName) else ConfigFactory.empty()
            val defaultProps = Properties()
            defaultProps.setProperty("dataSourceProperties.dataSourceClassName", "org.h2.jdbcx.JdbcDataSource")
            defaultProps.setProperty("dataSourceProperties.dataSource.url", "jdbc:h2:mem:${h2InstanceName}_persistence;LOCK_TIMEOUT=10000;DB_CLOSE_ON_EXIT=FALSE")
            defaultProps.setProperty("dataSourceProperties.dataSource.user", "sa")
            defaultProps.setProperty("dataSourceProperties.dataSource.password", "")
            val defaultConfig = ConfigFactory.parseProperties(defaultProps, parseOptions)

            return databaseConfig.withFallback(fixedOverride)
                    .withFallback(nodeOrganizationNameConfig)
                    .withFallback(defaultConfig)
                    .resolve()
        }
        /**
         * Make properties appropriate for creating a DataSource for unit tests.
         *
         * @param nodeName Reflects the "instance" of the in-memory database or database username/schema.  Defaults to a random string.
         * @param nodeNameExtension Provides additional name extension for the "instance" of in-memory database to provide uniqnes when running unit tests against H2 db.
         */
        // TODO: Can we use an X509 principal generator here?
        @JvmStatic
        fun makeTestDataSourceProperties(nodeName: String = SecureHash.randomSHA256().toString(), nodeNameExtension: String? = null): Properties {
            val config = readDatabaseConfig(nodeName, nodeNameExtension)
            val props = Properties()
            props.setProperty("dataSourceClassName", config.getString("dataSourceProperties.dataSourceClassName"))
            props.setProperty("dataSource.url", config.getString("dataSourceProperties.dataSource.url"))
            props.setProperty("dataSource.user", config.getString("dataSourceProperties.dataSource.user"))
            props.setProperty("dataSource.password", config.getString("dataSourceProperties.dataSource.password"))
            return props
        }

        /**
         * Make properties appropriate for creating a Database for unit tests.
         *
         * @param nodeName Reflects the "instance" of the in-memory database or database username/schema.
         */
        @JvmStatic
        fun makeTestDatabaseProperties(nodeName: String? = null): Properties {
            val props = Properties()
            props.setProperty("transactionIsolationLevel", "repeatableRead") //for other possible values see net.corda.node.utilities.CordaPeristence.parserTransactionIsolationLevel(String)
            if (nodeName != null) {
                props.setProperty("nodeOrganizationName", nodeName)
            }
            return props
        }

        /**
         * Creates an instance of [InMemoryIdentityService] with [MOCK_IDENTITIES].
         */
        @JvmStatic
        fun makeTestIdentityService() = InMemoryIdentityService(MOCK_IDENTITIES, trustRoot = DEV_TRUST_ROOT)

        /**
         * Makes database and mock services appropriate for unit tests.
         * @param keys a list of [KeyPair] instances to be used by [MockServices]. Defaults to [MEGA_CORP_KEY]
         * @param createIdentityService a lambda function returning an instance of [IdentityService]. Defaults to [InMemoryIdentityService].
         *
         * @return a pair where the first element is the instance of [CordaPersistence] and the second is [MockServices].
         */
        @JvmStatic
        fun makeTestDatabaseAndMockServices(keys: List<KeyPair> = listOf(MEGA_CORP_KEY),
                                            createIdentityService: () -> IdentityService = { makeTestIdentityService() },
                                            cordappPackages: List<String> = emptyList()): Pair<CordaPersistence, MockServices>
            = makeTestDatabaseAndMockServices(keys, createIdentityService, cordappPackages, MEGA_CORP.name)

        /**
         * Makes database and mock services appropriate for unit tests.
         * @param keys a list of [KeyPair] instances to be used by [MockServices]. Defaults to [MEGA_CORP_KEY]
         * @param createIdentityService a lambda function returning an instance of [IdentityService]. Defauts to [InMemoryIdentityService].
         * @param initialIdentityName the name of the first (typically sole) identity the services will represent.
         * @return a pair where the first element is the instance of [CordaPersistence] and the second is [MockServices].
         */
        @JvmStatic
        fun makeTestDatabaseAndMockServices(keys: List<KeyPair> = listOf(MEGA_CORP_KEY),
                                            createIdentityService: () -> IdentityService = { makeTestIdentityService() },
                                            cordappPackages: List<String> = emptyList(),
                                            initialIdentityName: CordaX500Name): Pair<CordaPersistence, MockServices> {
            val cordappLoader = CordappLoader.createWithTestPackages(cordappPackages)
            val dataSourceProps = makeTestDataSourceProperties()
            val databaseProperties = makeTestDatabaseProperties()
            val identityServiceRef: IdentityService by lazy { createIdentityService() }
            val database = configureDatabase(dataSourceProps, databaseProperties, { identityServiceRef }, NodeSchemaService(cordappLoader))
            val mockService = database.transaction {
                object : MockServices(cordappLoader, initialIdentityName = initialIdentityName, keys = *(keys.toTypedArray())) {
                    override val identityService: IdentityService = database.transaction { identityServiceRef }
                    override val vaultService: VaultServiceInternal = makeVaultService(database.hibernateConfig)

                    override fun recordTransactions(statesToRecord: StatesToRecord, txs: Iterable<SignedTransaction>) {
                        for (stx in txs) {
                            validatedTransactions.addTransaction(stx)
                        }
                        // Refactored to use notifyAll() as we have no other unit test for that method with multiple transactions.
                        vaultService.notifyAll(statesToRecord, txs.map { it.tx })
                    }

                    override fun jdbcSession(): Connection = database.createSession()
                }
            }
            return Pair(database, mockService)
        }
    }

    constructor(cordappLoader: CordappLoader, initialIdentityName: CordaX500Name = MEGA_CORP.name, vararg keys: KeyPair) : this(cordappLoader, MockTransactionStorage(), initialIdentityName = initialIdentityName, keys = *keys)
    constructor(cordappPackages: List<String>, initialIdentityName: CordaX500Name = MEGA_CORP.name, vararg keys: KeyPair) : this(CordappLoader.createWithTestPackages(cordappPackages), initialIdentityName = initialIdentityName, keys = *keys)
    constructor(vararg keys: KeyPair) : this(emptyList(), MEGA_CORP.name, *keys)
    constructor() : this(generateKeyPair())

    val key: KeyPair get() = keys.first()

    override fun recordTransactions(statesToRecord: StatesToRecord, txs: Iterable<SignedTransaction>) {
        txs.forEach {
            stateMachineRecordedTransactionMapping.addMapping(StateMachineRunId.createRandom(), it.id)
        }
        for (stx in txs) {
            validatedTransactions.addTransaction(stx)
        }
    }

    final override val attachments = MockAttachmentStorage()
    val stateMachineRecordedTransactionMapping: StateMachineRecordedTransactionMappingStorage = MockStateMachineRecordedTransactionMappingStorage()
    override val identityService: IdentityService = InMemoryIdentityService(MOCK_IDENTITIES, trustRoot = DEV_TRUST_ROOT)
    override val keyManagementService: KeyManagementService by lazy { MockKeyManagementService(identityService, *keys) }

    override val vaultService: VaultService get() = throw UnsupportedOperationException()
    override val contractUpgradeService: ContractUpgradeService get() = throw UnsupportedOperationException()
    override val networkMapCache: NetworkMapCache get() = throw UnsupportedOperationException()
    override val clock: Clock get() = Clock.systemUTC()
    override val myInfo: NodeInfo
        get() {
            val identity = getTestPartyAndCertificate(initialIdentityName, key.public)
            return NodeInfo(emptyList(), listOf(identity), 1, serial = 1L)
        }
    override val transactionVerifierService: TransactionVerifierService get() = InMemoryTransactionVerifierService(2)
    val mockCordappProvider = MockCordappProvider(cordappLoader, attachments)
    override val cordappProvider: CordappProvider get() = mockCordappProvider
    lateinit var hibernatePersister: HibernateObserver

    fun makeVaultService(hibernateConfig: HibernateConfiguration): VaultServiceInternal {
        val vaultService = NodeVaultService(Clock.systemUTC(), keyManagementService, stateLoader, hibernateConfig)
        hibernatePersister = HibernateObserver.install(vaultService.rawUpdates, hibernateConfig)
        return vaultService
    }

    val cordappServices = MutableClassToInstanceMap.create<SerializeAsToken>()
    override fun <T : SerializeAsToken> cordaService(type: Class<T>): T {
        require(type.isAnnotationPresent(CordaService::class.java)) { "${type.name} is not a Corda service" }
        return cordappServices.getInstance(type) ?: throw IllegalArgumentException("Corda service ${type.name} does not exist")
    }

    override fun jdbcSession(): Connection = throw UnsupportedOperationException()
}

class MockKeyManagementService(val identityService: IdentityService,
                               vararg initialKeys: KeyPair) : SingletonSerializeAsToken(), KeyManagementService {
    private val keyStore: MutableMap<PublicKey, PrivateKey> = initialKeys.associateByTo(HashMap(), { it.public }, { it.private })

    override val keys: Set<PublicKey> get() = keyStore.keys

    val nextKeys = LinkedList<KeyPair>()

    override fun freshKey(): PublicKey {
        val k = nextKeys.poll() ?: generateKeyPair()
        keyStore[k.public] = k.private
        return k.public
    }

    override fun filterMyKeys(candidateKeys: Iterable<PublicKey>): Iterable<PublicKey> = candidateKeys.filter { it in this.keys }

    override fun freshKeyAndCert(identity: PartyAndCertificate, revocationEnabled: Boolean): PartyAndCertificate {
        return freshCertificate(identityService, freshKey(), identity, getSigner(identity.owningKey), revocationEnabled)
    }

    private fun getSigner(publicKey: PublicKey): ContentSigner = getSigner(getSigningKeyPair(publicKey))

    private fun getSigningKeyPair(publicKey: PublicKey): KeyPair {
        val pk = publicKey.keys.first { keyStore.containsKey(it) }
        return KeyPair(pk, keyStore[pk]!!)
    }

    override fun sign(bytes: ByteArray, publicKey: PublicKey): DigitalSignature.WithKey {
        val keyPair = getSigningKeyPair(publicKey)
        return keyPair.sign(bytes)
    }

    override fun sign(signableData: SignableData, publicKey: PublicKey): TransactionSignature {
        val keyPair = getSigningKeyPair(publicKey)
        return keyPair.sign(signableData)
    }
}

class MockStateMachineRecordedTransactionMappingStorage(
        val storage: StateMachineRecordedTransactionMappingStorage = InMemoryStateMachineRecordedTransactionMappingStorage()
) : StateMachineRecordedTransactionMappingStorage by storage

open class MockTransactionStorage : WritableTransactionStorage, SingletonSerializeAsToken() {
    override fun track(): DataFeed<List<SignedTransaction>, SignedTransaction> {
        return DataFeed(txns.values.toList(), _updatesPublisher)
    }

    private val txns = HashMap<SecureHash, SignedTransaction>()

    private val _updatesPublisher = PublishSubject.create<SignedTransaction>()

    override val updates: Observable<SignedTransaction>
        get() = _updatesPublisher

    private fun notify(transaction: SignedTransaction) = _updatesPublisher.onNext(transaction)

    override fun addTransaction(transaction: SignedTransaction): Boolean {
        val recorded = txns.putIfAbsent(transaction.id, transaction) == null
        if (recorded) {
            notify(transaction)
        }
        return recorded
    }

    override fun getTransaction(id: SecureHash): SignedTransaction? = txns[id]
}

fun <T : SerializeAsToken> createMockCordaService(serviceHub: MockServices, serviceConstructor: (AppServiceHub) -> T): T {
    class MockAppServiceHubImpl<T : SerializeAsToken>(val serviceHub: MockServices, serviceConstructor: (AppServiceHub) -> T) : AppServiceHub, ServiceHub by serviceHub {
        val serviceInstance: T

        init {
            serviceInstance = serviceConstructor(this)
            serviceHub.cordappServices.putInstance(serviceInstance.javaClass, serviceInstance)
        }

        override fun <T> startFlow(flow: FlowLogic<T>): FlowHandle<T> {
            throw UnsupportedOperationException()
        }

        override fun <T> startTrackedFlow(flow: FlowLogic<T>): FlowProgressHandle<T> {
            throw UnsupportedOperationException()
        }
    }
    return MockAppServiceHubImpl(serviceHub, serviceConstructor).serviceInstance
}