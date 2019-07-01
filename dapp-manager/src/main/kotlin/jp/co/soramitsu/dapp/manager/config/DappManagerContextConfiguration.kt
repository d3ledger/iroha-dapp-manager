/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package jp.co.soramitsu.dapp.manager.config

import com.d3.commons.config.RMQConfig
import com.d3.commons.config.loadRawLocalConfigs
import com.d3.commons.healthcheck.HealthCheckEndpoint
import com.d3.commons.model.IrohaCredential
import com.d3.commons.sidechain.iroha.ReliableIrohaChainListener
import com.d3.commons.sidechain.iroha.consumer.IrohaConsumerImpl
import com.d3.commons.util.createPrettySingleThreadPool
import com.google.gson.JsonParser
import jp.co.soramitsu.dapp.block.BlockProcessor
import jp.co.soramitsu.dapp.service.CommandObservableSource
import jp.co.soramitsu.iroha.java.IrohaAPI
import jp.co.soramitsu.iroha.java.QueryAPI
import jp.co.soramitsu.iroha.java.Utils
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.URI

@Configuration
class DappManagerContextConfiguration {

    private val dappManagerConfig =
        loadRawLocalConfigs(
            DAPP_MANAGER_NAME,
            DappManagerConfig::class.java,
            "dapp_manager.properties"
        )

    private val rmqConfig =
        loadRawLocalConfigs(
            DAPP_MANAGER_NAME,
            RMQConfig::class.java,
            "rmq.properties"
        )

    @Bean
    fun dappKeyPair() = Utils.parseHexKeypair(
        dappManagerConfig.pubKey,
        dappManagerConfig.privKey
    )

    @Bean
    fun irohaApi() = IrohaAPI(URI(dappManagerConfig.irohaUrl))

    @Bean
    fun dAppManagerAccountId() = dappManagerConfig.accountId

    @Bean
    fun queryApi() = QueryAPI(irohaApi(), dAppManagerAccountId(), dappKeyPair())

    @Bean
    fun chainListener() = ReliableIrohaChainListener(
        rmqConfig,
        dappManagerConfig.queue,
        createPrettySingleThreadPool(DAPP_MANAGER_NAME, "chain-listener")
    )

    @Bean
    fun repositoryAccountId() = dappManagerConfig.repository

    @Bean
    fun repositorySetterAccountId() = dAppManagerAccountId()

    @Bean
    fun journalAccountId() = dappManagerConfig.journal

    @Bean
    fun journalSetterAccountId() = dAppManagerAccountId()

    @Bean
    fun dappAccountsStorage() = dappManagerConfig.dappAccountsStorage

    @Bean
    fun brvsAccountId() = dappManagerConfig.brvsAccountId

    @Bean
    fun commandObservableSource() = CommandObservableSource(BlockProcessor(chainListener()))

    @Bean
    fun domain() = DAPP_DOMAIN

    @Bean
    fun registrationPort() = dappManagerConfig.registrationPort

    @Bean
    fun uploaderPort() = dappManagerConfig.uploaderPort

    @Bean
    fun irohaConsumer() = IrohaConsumerImpl(
        IrohaCredential(
            dAppManagerAccountId(),
            dappKeyPair()
        ),
        irohaApi()
    )

    @Bean
    fun jsonParser() = JsonParser()

    @Bean
    fun healthCheckEndpoint() = HealthCheckEndpoint(dappManagerConfig.healthCheckPort)
}
