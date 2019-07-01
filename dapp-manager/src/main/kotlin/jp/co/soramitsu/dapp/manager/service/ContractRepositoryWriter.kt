/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package jp.co.soramitsu.dapp.manager.service

import iroha.protocol.Endpoint
import jp.co.soramitsu.dapp.parser.ContractParser.Companion.parse
import jp.co.soramitsu.iroha.java.IrohaAPI
import jp.co.soramitsu.iroha.java.Transaction
import jp.co.soramitsu.iroha.java.Utils
import mu.KLogging
import org.springframework.stereotype.Component
import java.security.KeyPair

interface RepositoryWriter {
    fun writeContract(name: String, script: String)
}

@Component
class ContractRepositoryWriter(
    private val repositoryAccountId: String,
    private val repositorySetterAccountId: String,
    private val keyPair: KeyPair,
    private val irohaAPI: IrohaAPI
) : RepositoryWriter {

    override fun writeContract(name: String, script: String) {
        logger.info("Uploading $name contract")
        // throw exception if script is bad
        try {
            parse(script)
        } catch (e: Exception) {
            logger.error("Couldn't parse $name script", e)
            throw e
        }
        logger.info("Successfully checked the script of $name")
        val response = irohaAPI.transaction(
            Transaction.builder(repositorySetterAccountId)
                .setAccountDetail(
                    repositoryAccountId,
                    name,
                    Utils.irohaEscape(script)
                )
                .sign(keyPair)
                .build()
        ).blockingLast()
        if (response.txStatus != Endpoint.TxStatus.COMMITTED) {
            logger.error("Couldn't upload contract $name to the repository. Iroha response: $response")
            throw IllegalStateException("Couldn't upload contract $name to the repository. Iroha response: $response")
        }
        logger.info("Successfully uploaded the script of $name")
    }

    companion object : KLogging()
}
