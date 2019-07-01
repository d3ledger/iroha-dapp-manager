/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package jp.co.soramitsu.dapp.manager.controller

import com.d3.commons.registration.RegistrationServiceEndpoint
import jp.co.soramitsu.dapp.manager.registration.DappRegistrationStrategy
import mu.KLogging
import org.springframework.stereotype.Component

@Component
class RegistrationInitialization(
    registrationPort: Int,
    dappRegistrationStrategy: DappRegistrationStrategy
) {
    init {
        logger.info { "Init dApp registration service" }
        RegistrationServiceEndpoint(
            registrationPort,
            dappRegistrationStrategy
        )
    }

    companion object : KLogging()
}
