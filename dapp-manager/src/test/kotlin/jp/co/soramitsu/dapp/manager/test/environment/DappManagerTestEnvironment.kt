/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package jp.co.soramitsu.dapp.manager.test.environment

import com.d3.commons.config.RMQConfig
import com.d3.commons.model.IrohaCredential
import com.d3.commons.sidechain.iroha.ReliableIrohaChainListener
import com.d3.commons.sidechain.iroha.consumer.IrohaConsumerImpl
import com.d3.commons.util.createPrettySingleThreadPool
import com.google.common.io.Files
import com.google.gson.JsonParser
import iroha.protocol.BlockOuterClass
import iroha.protocol.Endpoint
import iroha.protocol.Primitive
import jp.co.soramitsu.crypto.ed25519.Ed25519Sha3
import jp.co.soramitsu.dapp.block.BlockProcessor
import jp.co.soramitsu.dapp.manager.config.DAPP_DOMAIN
import jp.co.soramitsu.dapp.manager.config.DAPP_MANAGER_NAME
import jp.co.soramitsu.dapp.manager.controller.RegistrationInitialization
import jp.co.soramitsu.dapp.manager.registration.DappRegistrationStrategy
import jp.co.soramitsu.dapp.manager.service.ContractJournalProcessor
import jp.co.soramitsu.dapp.manager.service.DappInstancesStatusMonitor
import jp.co.soramitsu.dapp.manager.util.DappManagerUtils
import jp.co.soramitsu.dapp.service.CommandObservableSource
import jp.co.soramitsu.iroha.java.IrohaAPI
import jp.co.soramitsu.iroha.java.QueryAPI
import jp.co.soramitsu.iroha.java.Transaction
import jp.co.soramitsu.iroha.java.Utils
import jp.co.soramitsu.iroha.java.detail.Const
import jp.co.soramitsu.iroha.testcontainers.IrohaContainer
import jp.co.soramitsu.iroha.testcontainers.PeerConfig
import jp.co.soramitsu.iroha.testcontainers.detail.GenesisBlockBuilder
import khttp.responses.Response
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.FixedHostPortGenericContainer
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.InternetProtocol
import java.io.Closeable
import java.io.File
import java.net.URI
import java.nio.charset.Charset
import java.util.*


class KGenericFixedContainer(imageName: String) :
    FixedHostPortGenericContainer<KGenericFixedContainer>(imageName)

private const val DEFAULT_RMQ_PORT = 5672
private const val DEFAULT_IROHA_PORT = 50051
const val dappManagerAccountId = "dapp_mngr" + Const.accountIdDelimiter + DAPP_DOMAIN
const val dappJournalAccountId = "dappjournal" + Const.accountIdDelimiter + DAPP_DOMAIN
const val dappStorageAccountId = "dapp_accs" + Const.accountIdDelimiter + DAPP_DOMAIN
const val dappContractAccountId = "other" + Const.accountIdDelimiter + DAPP_DOMAIN
const val brvsAccountId = "brvs" + Const.accountIdDelimiter + DAPP_DOMAIN
val irohaKeyPair = Ed25519Sha3().generateKeypair()!!
val contractIrohaKeyPair = Ed25519Sha3().generateKeypair()!!
const val rmqName = "rmq"
const val irohaName = "iroha"
val rmq = KGenericFixedContainer("rabbitmq:3-management").withExposedPorts(DEFAULT_RMQ_PORT)
    .withFixedExposedPort(DEFAULT_RMQ_PORT, DEFAULT_RMQ_PORT)
    .withCreateContainerCmdModifier { it.withName(rmqName) }!!
val iroha = IrohaContainer().withPeerConfig(peerConfig)!!
var postgresDockerContainer: GenericContainer<*> = GenericContainer<Nothing>()
var irohaContainer: GenericContainer<*> = GenericContainer<Nothing>()
val chainAdapter = KGenericFixedContainer("nexus.iroha.tech:19002/d3-deploy/chain-adapter:1.0.0")
val brvs = KGenericFixedContainer("nexus.iroha.tech:19002/brvs-deploy/brvs:1.0.0")
val mongodb =
    KGenericFixedContainer("mongo:4.0.6").withCreateContainerCmdModifier { it.withName("brvs-mongodb") }!!
const val rmqExchange = irohaName
const val resourcesLocation = "src/test/resources"
const val registrationPort = 8090

val genesisBlock: BlockOuterClass.Block
    get() =
        GenesisBlockBuilder()
            .addDefaultTransaction()
            .addTransaction(
                Transaction.builder(dappManagerAccountId)
                    .createDomain(
                        DAPP_DOMAIN,
                        GenesisBlockBuilder.defaultRoleName
                    )
                    .createAccount(
                        dappManagerAccountId,
                        irohaKeyPair.public
                    )
                    .createAccount(
                        dappJournalAccountId,
                        irohaKeyPair.public
                    )
                    .createAccount(
                        dappStorageAccountId,
                        irohaKeyPair.public
                    )
                    .createAccount(
                        dappContractAccountId,
                        contractIrohaKeyPair.public
                    )
                    .createAccount(
                        brvsAccountId,
                        irohaKeyPair.public
                    )
                    .setAccountDetail(
                        dappJournalAccountId,
                        "testcontract",
                        dappContractAccountId
                    )
                    .build()
                    .build()
            )
            .build()


val peerConfig: PeerConfig
    get() = PeerConfig.builder()
        .genesisBlock(genesisBlock)
        .build()

class DappManagerTestEnvironment : Closeable {

    lateinit var service: DappInstancesStatusMonitor
    lateinit var irohaAPI: IrohaAPI
    lateinit var queryAPI: QueryAPI
    private lateinit var rmqHost: String
    private var rmqPort: Int = 0

    private val pubKeyFile = File("$resourcesLocation/pub.key")
    private val privKeyFile = File("$resourcesLocation/priv.key")

    private val serviceKeyPair = irohaKeyPair
    private val instanceKeyPair = Ed25519Sha3().generateKeypair()!!
    val instanceHexPubKey = Utils.toHex(instanceKeyPair.public.encoded).toLowerCase()
    val contractHexPubKey = Utils.toHex(contractIrohaKeyPair.public.encoded).toLowerCase()

    fun requestExecutionOfTestContract(accountId: String) {
        val response = irohaAPI.transaction(
            Transaction.builder(accountId)
                .setAccountDetail(
                    DappManagerUtils.getRequestAccountId(accountId),
                    "testcontract",
                    "true"
                )
                .setQuorum(2)
                .sign(instanceKeyPair)
                .build()
        ).blockingLast()
        if (response.txStatus != Endpoint.TxStatus.COMMITTED) {
            throw RuntimeException("Requesting execution from $accountId dapp instance account failed: $response")
        }
    }

    fun denyExecutionOfTestContract(accountId: String) {
        val response = irohaAPI.transaction(
            Transaction.builder(accountId)
                .setAccountDetail(
                    DappManagerUtils.getRequestAccountId(accountId),
                    "testcontract",
                    "false"
                )
                .setQuorum(2)
                .sign(instanceKeyPair)
                .build()
        ).blockingLast()
        if (response.txStatus != Endpoint.TxStatus.COMMITTED) {
            throw RuntimeException("Requesting denying from $accountId dapp instance account failed: $response")
        }
    }

    fun init() {
        iroha.withLogger(null)
        iroha.configure()
        postgresDockerContainer = iroha.postgresDockerContainer
        postgresDockerContainer.start()
        irohaContainer = iroha.irohaDockerContainer.withCreateContainerCmdModifier { it.withName(irohaName) }
            .withExposedPorts(DEFAULT_IROHA_PORT)
        irohaContainer.getPortBindings().add(
            String.format(
                "%d:%d/%s",
                DEFAULT_IROHA_PORT,
                DEFAULT_IROHA_PORT,
                InternetProtocol.TCP.toDockerNotation()
            )
        )
        irohaContainer.start()

        val host = irohaContainer.getContainerIpAddress()
        val port = irohaContainer.getMappedPort(DEFAULT_IROHA_PORT)!!

        irohaAPI = IrohaAPI(URI("grpc", null, host, port, null, null, null))

        rmq.withNetwork(iroha.network).start()
        rmqHost = rmq.containerIpAddress
        rmqPort = rmq.getMappedPort(DEFAULT_RMQ_PORT)

        val servicePubKeyHex = Utils.toHex(serviceKeyPair.public.encoded).toLowerCase()
        val servicePrivKeyHex = Utils.toHex(serviceKeyPair.private.encoded).toLowerCase()

        chainAdapter
            .withEnv(
                "CHAIN-ADAPTER_RMQHOST",
                rmqName
            )
            .withEnv(
                "CHAIN-ADAPTER_IROHA_HOSTNAME",
                irohaName
            )
            .withEnv(
                "CHAIN-ADAPTER_IROHA_PORT",
                iroha.toriiAddress.port.toString()
            )
            .withEnv(
                "CHAIN-ADAPTER_IROHACREDENTIAL_ACCOUNTID",
                dappManagerAccountId
            )
            .withEnv(
                "CHAIN-ADAPTER_IROHACREDENTIAL_PUBKEY",
                servicePubKeyHex
            )
            .withEnv(
                "CHAIN-ADAPTER_IROHACREDENTIAL_PRIVKEY",
                servicePrivKeyHex
            )
            .withEnv(
                "CHAIN-ADAPTER_DROPLASTREADBLOCK",
                "true"
            )
            .withEnv(
                "WAIT_HOSTS",
                "rmq:$rmqPort, iroha:${iroha.toriiAddress.port}"
            )
            .withNetwork(iroha.network)
            .start()

        mongodb.withNetwork(iroha.network).start()

        Files.write(
            servicePubKeyHex,
            pubKeyFile,
            Charset.defaultCharset()
        )
        Files.write(
            servicePrivKeyHex,
            privKeyFile,
            Charset.defaultCharset()
        )

        brvs
            .addFileSystemBind(
                resourcesLocation,
                "/opt/brvs/config/keys",
                BindMode.READ_ONLY
            )

        brvs
            .withEnv(
                "credential.accountId",
                brvsAccountId
            )
            .withEnv(
                "brvs.userDomains",
                DAPP_DOMAIN
            )
            .withEnv(
                "accounts.holder",
                dappStorageAccountId
            )
            .withEnv(
                "credential.pubkeyFilePath",
                "config/keys/pub.key"
            )
            .withEnv(
                "credential.privkeyFilePath",
                "config/keys/priv.key"
            )
            .withEnv(
                "iroha.host",
                irohaName
            )
            .withEnv(
                "iroha.port",
                iroha.toriiAddress.port.toString()
            )
            .withEnv(
                "mongo.host",
                "brvs-mongodb"
            )
            .withEnv(
                "mongo.port",
                "27017"
            )
            .withEnv(
                "rmq.host",
                rmqName
            )
            .withEnv(
                "rmq.port",
                rmqPort.toString()
            )
            .withEnv(
                "WAIT_HOSTS",
                "brvs-mongodb:27017, $rmqName:$rmqPort, $irohaName:${iroha.toriiAddress.port}"
            )
            .withNetwork(iroha.network)
            .start()

        Thread.sleep(3000)

        queryAPI = QueryAPI(irohaAPI, dappManagerAccountId, irohaKeyPair)

        val chainListener = ReliableIrohaChainListener(
            object : RMQConfig {
                override val host = rmqHost
                override val irohaExchange = rmqExchange
                override val port = rmqPort
            },
            Random().nextLong().toString(),
            createPrettySingleThreadPool(DAPP_MANAGER_NAME, "chain-listener")
        )

        val jsonParser = JsonParser()

        service = DappInstancesStatusMonitor(
            ContractJournalProcessor(
                dappJournalAccountId,
                dappManagerAccountId,
                irohaKeyPair,
                queryAPI,
                jsonParser
            ),
            CommandObservableSource(
                BlockProcessor(chainListener)
            ),
            queryAPI,
            jsonParser,
            dappStorageAccountId
        )

        // Contract account can be modified
        irohaAPI.transaction(
            Transaction.builder(dappContractAccountId)
                .grantPermission(dappManagerAccountId, Primitive.GrantablePermission.can_add_my_signatory)
                .grantPermission(dappManagerAccountId, Primitive.GrantablePermission.can_remove_my_signatory)
                .grantPermission(dappManagerAccountId, Primitive.GrantablePermission.can_set_my_quorum)
                .sign(contractIrohaKeyPair)
                .build()
        ).blockingLast()

        RegistrationInitialization(
            registrationPort,
            DappRegistrationStrategy(
                IrohaConsumerImpl(
                    IrohaCredential(
                        dappStorageAccountId,
                        serviceKeyPair
                    ),
                    irohaAPI
                ),
                serviceKeyPair,
                dappStorageAccountId,
                brvsAccountId,
                DAPP_DOMAIN
            )
        )

        // To be sure the service is initialized
        Thread.sleep(1000)
    }

    override fun close() {
        irohaAPI.close()
        chainAdapter.stop()
        irohaContainer.stop()
        postgresDockerContainer.stop()
        iroha.conf.deleteTempDir()
        rmq.stop()
        pubKeyFile.delete()
        privKeyFile.delete()
    }

    fun register(
        name: String
    ): Response {
        return khttp.post(
            "http://127.0.0.1:$registrationPort/users",
            data = mapOf("name" to name, "pubkey" to instanceHexPubKey, "domain" to DAPP_DOMAIN)
        )
    }
}
