/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package jp.co.soramitsu.dapp.manager.config

const val DAPP_MANAGER_NAME = "dapp-manager"

const val DAPP_DOMAIN = "dapp"

interface DappManagerConfig {

    val accountId: String

    val registratorAccountId: String

    val repository: String

    val journal: String

    val dappAccountsStorage: String

    val brvsAccountId: String

    val pubKey: String

    val privKey: String

    val registrationPort: Int

    val irohaUrl: String

    val queue: String
}
