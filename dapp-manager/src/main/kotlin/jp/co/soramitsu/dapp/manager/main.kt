/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */
@file:JvmName("DappManagerMain")

package jp.co.soramitsu.dapp.manager

import com.github.kittinunf.result.Result
import com.github.kittinunf.result.failure
import mu.KLogging
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.ComponentScan

private val logger = KLogging().logger

@ComponentScan("jp.co.soramitsu.dapp.manager")
class DappManagerApplication

fun main(args: Array<String>) {
    Result.of {
        AnnotationConfigApplicationContext(DappManagerApplication::class.java)
        logger.info("Started dApp Manager")
    }.failure { ex ->
        logger.error("Dapp manager exited with an exception", ex)
        System.exit(1)
    }
}
