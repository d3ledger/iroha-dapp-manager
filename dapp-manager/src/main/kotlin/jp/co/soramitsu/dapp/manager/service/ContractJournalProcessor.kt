/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package jp.co.soramitsu.dapp.manager.service

import com.google.gson.JsonParser
import iroha.protocol.Endpoint
import jp.co.soramitsu.iroha.java.QueryAPI
import jp.co.soramitsu.iroha.java.Transaction
import mu.KLogging
import org.springframework.stereotype.Component
import java.security.KeyPair
import java.util.regex.Pattern

interface JournalWriter {
    fun writeInfo(contractName: String, info: String)
}

interface JournalRetriever {
    // Assuming there is a list of accounts
    fun getInfoForContract(contractName: String): Iterable<String>
}

@Component
class ContractJournalProcessor(
    private val journalAccountId: String,
    private val journalSetterAccountId: String,
    private val keyPair: KeyPair,
    private val queryAPI: QueryAPI,
    private val jsonParser: JsonParser
) : JournalWriter, JournalRetriever {

    private val listRegex = Pattern.compile("[,][ ]*")

    override fun writeInfo(contractName: String, info: String) {
        logger.info("Writing $info for contract $contractName")
        val response = queryAPI.api.transaction(
            Transaction.builder(journalSetterAccountId)
                .setAccountDetail(
                    journalAccountId,
                    contractName,
                    info
                )
                .sign(keyPair)
                .build()
        ).blockingLast()
        if (response.txStatus != Endpoint.TxStatus.COMMITTED) {
            logger.error("Couldn't upload contract $contractName info to the journal. Iroha response: $response")
            throw IllegalStateException("Couldn't upload contract $contractName info to the journal. Iroha response: $response")
        }
        logger.info("Successfully wrote $info for $contractName")
    }

    override fun getInfoForContract(contractName: String): Iterable<String> {
        logger.info("Reading contract $contractName info")
        val result = jsonParser.parse(
            queryAPI.getAccountDetails(
                journalAccountId,
                journalSetterAccountId,
                contractName
            )
        ).asJsonObject.get(journalSetterAccountId).asJsonObject.get(contractName).asString
        logger.info("Retrieved $result for $contractName")
        return result.split(listRegex)
    }

    companion object : KLogging()
}
