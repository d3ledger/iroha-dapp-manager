/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package jp.co.soramitsu.dapp.manager.service

import com.d3.commons.util.createPrettyFixThreadPool
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import io.reactivex.schedulers.Schedulers
import iroha.protocol.Commands
import iroha.protocol.Endpoint
import jp.co.soramitsu.dapp.manager.config.DAPP_DOMAIN
import jp.co.soramitsu.dapp.manager.config.DAPP_MANAGER_NAME
import jp.co.soramitsu.dapp.manager.util.DappManagerUtils
import jp.co.soramitsu.dapp.manager.util.requestAccountPostfix
import jp.co.soramitsu.dapp.service.CommandObservableSource
import jp.co.soramitsu.iroha.java.QueryAPI
import jp.co.soramitsu.iroha.java.Transaction
import jp.co.soramitsu.iroha.java.Utils.parseHexPublicKey
import jp.co.soramitsu.iroha.java.detail.Const.accountIdDelimiter
import mu.KLogging
import org.springframework.stereotype.Component
import java.security.PublicKey

@Component
final class DappInstancesStatusMonitor(
    private val contractJournalRetriever: JournalRetriever,
    commandObservableSource: CommandObservableSource,
    private val queryAPI: QueryAPI,
    private val jsonParser: JsonParser,
    dappAccountsStorage: String
) {
    // dApp instance -> [enabled contracts]
    private val enabledContracts: MutableMap<String, MutableSet<String>>
    private val requestsAccounts: MutableSet<String>
    private val scheduler = Schedulers.from(
        createPrettyFixThreadPool(
            DAPP_MANAGER_NAME,
            "monitor"
        )
    )
    private val irohaAPI = queryAPI.api
    private val dappManagerAccountId = queryAPI.accountId
    private val dappManagerKeyPair = queryAPI.keyPair

    init {
        val dappAccounts = getDetailsValuesFrom(
            dappAccountsStorage,
            this::accountIdExctractor
        )
        requestsAccounts = dappAccounts.map(DappManagerUtils::getRequestAccountId).toMutableSet()
        enabledContracts = constructEnabledContractsMapFor(dappAccounts)
        commandObservableSource
            .getObservable(Commands.Command.CommandCase.SET_ACCOUNT_DETAIL)
            .observeOn(scheduler)
            .subscribe { (command, time) ->
                monitorRequests(command, time)
            }
        logger.info("Subscribed to new dapp requests")
        commandObservableSource
            .getObservable(Commands.Command.CommandCase.CREATE_ACCOUNT)
            .observeOn(scheduler)
            .subscribe { (command, _) ->
                monitorNewInstances(command)
            }
        logger.info("Subscribed to new dapp accounts creation events")
    }

    private fun <T : Any> getDetailsValuesFrom(
        accountId: String,
        transformer: (Map.Entry<String, JsonElement>) -> T?
    ) =
        jsonParser
            .parse(
                queryAPI.getAccount(accountId)
                    .account
                    .jsonData
            )
            .asJsonObject.entrySet().map { accountSetter ->
            accountSetter
                .value
                .asJsonObject
                .entrySet()
                .mapNotNull(transformer)
        }.flatten().toMutableSet()

    private fun accountIdExctractor(entry: Map.Entry<String, JsonElement>): String? {
        val accountIdWithoutDelimiter = entry.key
        val domain = entry.value
        return if (!accountIdWithoutDelimiter.endsWith(domain.asString)) {
            null
        } else accountIdWithoutDelimiter.substring(
            0,
            accountIdWithoutDelimiter.lastIndexOf(domain.asString)
        ) + accountIdDelimiter + domain.asString
    }

    private fun booleanExctractor(entry: Map.Entry<String, JsonElement>) =
        if (entry.value.asBoolean) entry.key
        else null

    private fun constructEnabledContractsMapFor(accounts: Iterable<String>) =
        accounts.associate { accountId ->
            accountId to getDetailsValuesFrom(accountId, this::booleanExctractor)
        }.toMutableMap()

    private fun monitorRequests(command: Commands.Command, commandTime: Long) {
        val setAccountDetail = command.setAccountDetail
        val requestsAccountId = setAccountDetail.accountId
        if (requestsAccounts.contains(requestsAccountId)) {
            val contractName = setAccountDetail.key
            val isEnabled = setAccountDetail.value!!.toBoolean()
            val originalAccountId = DappManagerUtils.getOriginalAccountId(requestsAccountId)
            val accountContracts = enabledContracts[originalAccountId]
            // enable
            if (!accountContracts!!.contains(contractName) && isEnabled) {
                enableContractFor(contractName, originalAccountId, commandTime)
                return
            }
            // disable
            if (accountContracts.contains(contractName) && !isEnabled) {
                disableContractFor(contractName, originalAccountId, commandTime)
                return
            }
        }
    }

    @Synchronized
    private fun enableContractFor(
        contractName: String,
        accountId: String,
        commandTime: Long
    ) {
        logger.info("Got new enable request: $contractName for $accountId")
        val dappAccountKey = querySingleSignatory(accountId)
        contractJournalRetriever.getInfoForContract(contractName).forEach { contractAccountId ->
            increaseQuorumOf(contractAccountId, dappAccountKey, commandTime)
        }
        // enable execution
        enableContractByDetail(accountId, contractName, commandTime)
        enabledContracts[accountId]!!.add(contractName)
    }

    @Synchronized
    private fun disableContractFor(
        contractName: String,
        accountId: String,
        commandTime: Long
    ) {
        logger.info("Got new disable request: $contractName for $accountId")
        val dappAccountKey = querySingleSignatory(accountId)
        contractJournalRetriever.getInfoForContract(contractName).forEach { contractAccountId ->
            decreaseQuorumOf(contractAccountId, dappAccountKey, commandTime)
        }
        // disable execution
        disableContractByDetail(accountId, contractName, commandTime)
        enabledContracts[accountId]!!.remove(contractName)
    }

    private fun querySingleSignatory(accountId: String) =
    // We take [1] since [0] is default, [2] is from brvs
        parseHexPublicKey(queryAPI.getSignatories(accountId).keysList[1])

    private fun increaseQuorumOf(
        accountId: String,
        publicKey: PublicKey,
        createdTime: Long
    ) {
        val txResponse = irohaAPI.transaction(
            Transaction.builder(dappManagerAccountId, createdTime)
                .addSignatory(
                    accountId,
                    publicKey
                )
                .setAccountQuorum(
                    accountId,
                    queryAPI.getAccount(accountId).account.quorum + 1
                )
                .sign(dappManagerKeyPair)
                .build()
        ).blockingLast()

        checkResponse(txResponse, "Couldn't increase contract account quorum.")
        logger.info("Increased quorum of $accountId")
    }

    private fun decreaseQuorumOf(
        accountId: String,
        publicKey: PublicKey,
        createdTime: Long
    ) {
        val txResponse = irohaAPI.transaction(
            Transaction.builder(dappManagerAccountId, createdTime)
                .setAccountQuorum(
                    accountId,
                    queryAPI.getAccount(accountId).account.quorum - 1
                )
                .removeSignatory(
                    accountId,
                    publicKey
                )
                .sign(dappManagerKeyPair)
                .build()
        ).blockingLast()

        checkResponse(txResponse, "Couldn't decrease contract account quorum.")
        logger.info("Decreased quorum of $accountId")
    }

    private fun enableContractByDetail(
        accountId: String,
        contractName: String,
        commandTime: Long
    ) {
        val txResponse = setAccountDetail(accountId, commandTime, contractName, "true")
        checkResponse(txResponse, "Couldn't enable contract by setting detail for dapp account.")
        logger.info("Enabled contract $contractName for $accountId")
    }

    private fun disableContractByDetail(
        accountId: String,
        contractName: String,
        commandTime: Long
    ) {
        val txResponse = setAccountDetail(accountId, commandTime, contractName, "false")
        checkResponse(txResponse, "Couldn't disable contract by setting detail for dapp account.")
        logger.info("Disabled contract $contractName for $accountId")
    }

    private fun setAccountDetail(
        accountId: String,
        createdTime: Long,
        key: String,
        value: String
    ) =
        irohaAPI.transaction(
            Transaction.builder(dappManagerAccountId, createdTime)
                .setAccountDetail(
                    accountId,
                    key,
                    value
                )
                .sign(dappManagerKeyPair)
                .build()
        ).blockingLast()

    private fun monitorNewInstances(command: Commands.Command) {
        val createAccount = command.createAccount
        if (createAccount.domainId == DAPP_DOMAIN) {
            val accountId = "${createAccount.accountName}@$DAPP_DOMAIN"
            logger.info("Got new account to add: $accountId")
            if (createAccount.accountName.contains(requestAccountPostfix)) {
                requestsAccounts.add(accountId)
                logger.info("Added new request account")
            } else {
                enabledContracts[accountId] = mutableSetOf()
                logger.info("Added new dapp account")
            }
        }
    }

    private fun checkResponse(txResponse: Endpoint.ToriiResponse, msg: String) {
        if (txResponse.txStatus != Endpoint.TxStatus.COMMITTED) {
            logger.error("$msg Response: $txResponse")
            throw IllegalStateException("$msg Response: $txResponse")
        }
    }

    companion object : KLogging()
}
