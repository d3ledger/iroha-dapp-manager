/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package jp.co.soramitsu.dapp.manager.registration

import com.d3.commons.notary.IrohaCommand
import com.d3.commons.notary.IrohaOrderedBatch
import com.d3.commons.notary.IrohaTransaction
import com.d3.commons.registration.RegistrationStrategy
import com.d3.commons.sidechain.iroha.consumer.IrohaConsumerImpl
import com.d3.commons.sidechain.iroha.consumer.IrohaConverter
import com.d3.commons.sidechain.iroha.util.ModelUtil
import com.github.kittinunf.result.Result
import com.github.kittinunf.result.flatMap
import com.github.kittinunf.result.map
import iroha.protocol.Primitive
import iroha.protocol.TransactionOuterClass
import jp.co.soramitsu.dapp.manager.util.DappManagerUtils
import jp.co.soramitsu.iroha.java.Utils
import mu.KLogging
import org.springframework.stereotype.Component
import java.security.KeyPair

@Component
class DappRegistrationStrategy(
    private val irohaConsumer: IrohaConsumerImpl,
    private val keyPair: KeyPair,
    private val dappAccountsStorage: String,
    private val brvsAccountId: String,
    private val domain: String
) : RegistrationStrategy {

    override fun getFreeAddressNumber(): Result<Int, Exception> {
        return Result.of { throw Exception("not supported") }
    }

    /**
     * Register a new D3 client in Iroha
     * @param accountName - unique user name
     * @param domainId - client domain
     * @param publicKey - client public key
     * @return hash of tx in Iroha
     */
    override fun register(
        accountName: String,
        domainId: String,
        publicKey: String
    ): Result<String, Exception> {
        logger.info { "dApp registration of client $accountName with pubkey $publicKey" }
        return createRegistrationBatch(accountName, domain, publicKey)
            .flatMap { batch ->
                irohaConsumer.send(batch).map { passedHashes ->
                    if (passedHashes.size != batch.size) {
                        throw IllegalStateException("dApp registration failed since tx batch was not fully successful")
                    }
                    "$accountName@$domain"
                }
            }
    }

    /**
     * Creates the registration batch allowing BRVS receive needed power relatively to the dapp instance user
     */
    private fun createRegistrationBatch(
        name: String,
        domain: String,
        pubkey: String
    ): Result<List<TransactionOuterClass.Transaction>, Exception> {
        val newUserAccountId = "$name@$domain"
        val requestAccountName = DappManagerUtils.getRequestAccountName(name)
        val requestAccountId = DappManagerUtils.getRequestAccountId(newUserAccountId)

        val irohaBatch = IrohaOrderedBatch(
            listOf(
                // First step is to create user account but with our own key, not user's one
                IrohaTransaction(
                    irohaConsumer.creator,
                    ModelUtil.getCurrentTime(),
                    listOf(
                        IrohaCommand.CommandCreateAccount(
                            name,
                            domain,
                            Utils.toHex(keyPair.public.encoded)
                        ),
                        IrohaCommand.CommandCreateAccount(
                            requestAccountName,
                            domain,
                            Utils.toHex(keyPair.public.encoded)
                        ),
                        IrohaCommand.CommandSetAccountDetail(
                            dappAccountsStorage,
                            "$name$domain",
                            domain
                        )
                    )
                ),
                // Second step is to give permissions from the user to brvs and dapp registration service
                // Here we need our own key to sign this stuff
                IrohaTransaction(
                    newUserAccountId,
                    ModelUtil.getCurrentTime(),
                    listOf(
                        IrohaCommand.CommandGrantPermission(
                            brvsAccountId,
                            Primitive.GrantablePermission.can_set_my_quorum_VALUE
                        ),
                        IrohaCommand.CommandGrantPermission(
                            brvsAccountId,
                            Primitive.GrantablePermission.can_add_my_signatory_VALUE
                        ),
                        IrohaCommand.CommandGrantPermission(
                            brvsAccountId,
                            Primitive.GrantablePermission.can_remove_my_signatory_VALUE
                        ),
                        IrohaCommand.CommandGrantPermission(
                            irohaConsumer.creator,
                            Primitive.GrantablePermission.can_set_my_quorum_VALUE
                        ),
                        IrohaCommand.CommandGrantPermission(
                            irohaConsumer.creator,
                            Primitive.GrantablePermission.can_add_my_signatory_VALUE
                        ),
                        IrohaCommand.CommandGrantPermission(
                            irohaConsumer.creator,
                            Primitive.GrantablePermission.can_remove_my_signatory_VALUE
                        )
                    )
                ),
                // Also we need to give permissions from requests account perspective
                IrohaTransaction(
                    requestAccountId,
                    ModelUtil.getCurrentTime(),
                    listOf(
                        IrohaCommand.CommandGrantPermission(
                            newUserAccountId,
                            Primitive.GrantablePermission.can_set_my_account_detail_VALUE
                        )
                    )
                ),
                // Finally we need to add user's original pub key to the signatories list
                // But we need to increase user's quorum to prohibit transactions using only user's key
                // Several moments later BRVS react on the createAccountTransaction and
                // user's quorum will be set as 1+2/3 of BRVS instances for now
                IrohaTransaction(
                    irohaConsumer.creator,
                    ModelUtil.getCurrentTime(),
                    listOf(
                        IrohaCommand.CommandAddSignatory(
                            newUserAccountId,
                            pubkey
                        ),
                        IrohaCommand.CommandSetAccountQuorum(
                            newUserAccountId,
                            2
                        )
                    )
                )
            )
        )

        // since just 'convert' returns ordered batch, we need atomic one
        return Result.of {
            IrohaConverter.convertToUnsignedBatch(irohaBatch).map { tx ->
                tx.sign(keyPair).build()
            }
        }
    }

    companion object : KLogging()
}
