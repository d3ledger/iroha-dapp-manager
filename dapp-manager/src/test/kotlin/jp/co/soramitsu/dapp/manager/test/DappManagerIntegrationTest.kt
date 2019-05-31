/*
 * Copyright Soramitsu Co., Ltd. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package jp.co.soramitsu.dapp.manager.test

import jp.co.soramitsu.dapp.manager.test.environment.DappManagerTestEnvironment
import jp.co.soramitsu.dapp.manager.test.environment.dappContractAccountId
import jp.co.soramitsu.dapp.manager.test.environment.dappManagerAccountId
import org.junit.Assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DappManagerIntegrationTest {

    private val environment = DappManagerTestEnvironment()

    @BeforeEach
    internal fun setUp() {
        environment.init()
    }

    @AfterEach
    internal fun tearDown() {
        environment.close()
    }

    /**
     * @given dApp manager instance running with all the infrastructure
     * @when new dApp instance account requested execution of a contract
     * @then contract accounts and instance account are changed properly
     * @when new dApp instance account requested execution disabling of a contract
     * @then contract accounts and instance account are changed back properly
     */
    @Test
    internal fun test() {
        val accountId = environment.register("instance").jsonObject["clientId"] as String

        environment.requestExecutionOfTestContract(accountId)

        Thread.sleep(5000)

        var signatoriesResponse = environment.queryAPI.getSignatories(dappContractAccountId)

        Assert.assertEquals(
            2,
            signatoriesResponse.keysCount
        )
        Assert.assertTrue(signatoriesResponse.keysList.contains(environment.contractHexPubKey))
        Assert.assertTrue(signatoriesResponse.keysList.contains(environment.instanceHexPubKey))

        var detailResponse =
            environment.queryAPI.getAccountDetails(accountId, dappManagerAccountId, "testcontract")

        Assert.assertEquals(
            "{\"$dappManagerAccountId\" : {\"testcontract\" : \"true\"}}",
            detailResponse
        )

        environment.denyExecutionOfTestContract(accountId)

        Thread.sleep(5000)

        signatoriesResponse = environment.queryAPI.getSignatories(dappContractAccountId)

        Assert.assertEquals(
            1,
            signatoriesResponse.keysCount
        )
        Assert.assertTrue(signatoriesResponse.keysList.contains(environment.contractHexPubKey))
        Assert.assertFalse(signatoriesResponse.keysList.contains(environment.instanceHexPubKey))

        detailResponse =
                environment.queryAPI.getAccountDetails(accountId, dappManagerAccountId, "testcontract")

        Assert.assertEquals(
            "{\"$dappManagerAccountId\" : {\"testcontract\" : \"false\"}}",
            detailResponse
        )
    }
}
