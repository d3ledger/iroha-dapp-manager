/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package jp.co.soramitsu.dapp.manager.controller

import jp.co.soramitsu.dapp.manager.service.JournalWriter
import jp.co.soramitsu.dapp.manager.service.RepositoryWriter
import mu.KLogging
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping

@Controller
@RequestMapping("/cache")
class ContractUploadController(
    private val contractRepositoryWriter: RepositoryWriter,
    private val contractJournalWriter: JournalWriter
) {

    @PostMapping("/upload/contract", headers = ["Content-Type=application/json"])
    fun uploadContract(
        @RequestBody body: Contract
    ): ResponseEntity<Conflictable> {
        return try {
            contractRepositoryWriter.writeContract(body.contractName, body.script)
            logger.info("Saved ${body.contractName}")
            ResponseEntity.ok().build<Conflictable>()
        } catch (e: Exception) {
            logger.error("Error during writing contract: ${e.message}")
            ResponseEntity.badRequest().body(Conflictable(e.javaClass.simpleName, e.message))
        }
    }

    @PostMapping("/upload/info", headers = ["Content-Type=application/json"])
    fun uploadContractInfo(
        @RequestBody body: ContractInfo
    ): ResponseEntity<Conflictable> {
        return try {
            contractJournalWriter.writeInfo(body.contractName, body.info)
            logger.info("Saved ${body.contractName} info")
            ResponseEntity.ok().build<Conflictable>()
        } catch (e: Exception) {
            logger.error("Error during writing contract info: ${e.message}")
            ResponseEntity.badRequest().body(Conflictable(e.javaClass.simpleName, e.message))
        }
    }

    companion object : KLogging()
}

data class Contract(val contractName: String, val script: String)

data class ContractInfo(val contractName: String, val info: String)

open class Conflictable(val errorCode: String? = null, val message: String? = null)
