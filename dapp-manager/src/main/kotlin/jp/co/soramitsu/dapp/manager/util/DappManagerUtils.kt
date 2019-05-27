/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package jp.co.soramitsu.dapp.manager.util

import jp.co.soramitsu.dapp.manager.config.DAPP_DOMAIN
import jp.co.soramitsu.iroha.java.detail.Const.accountIdDelimiter

const val requestAccountPostfix = "_requests"

object DappManagerUtils {
    fun getRequestAccountName(accountName: String) = accountName + requestAccountPostfix
    fun getRequestAccountId(accountId: String): String {
        val split = accountId.split(accountIdDelimiter)
        return getRequestAccountName(split[0]) + accountIdDelimiter + split[1]
    }

    fun getOriginalAccountId(requestAccountId: String) =
        requestAccountId.substring(
            0, requestAccountId.lastIndexOf(
                requestAccountPostfix
            )
        ) + accountIdDelimiter + DAPP_DOMAIN
}
