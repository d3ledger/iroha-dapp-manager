/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package jp.co.soramitsu.dapp.manager.controller

import com.d3.commons.registration.RegistrationServiceEndpoint
import com.d3.commons.registration.Response
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.CORS
import io.ktor.features.ContentNegotiation
import io.ktor.gson.gson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.request.receive
import io.ktor.response.respondText
import io.ktor.routing.post
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import jp.co.soramitsu.dapp.manager.service.JournalWriter
import jp.co.soramitsu.dapp.manager.service.RepositoryWriter
import mu.KLogging
import org.springframework.stereotype.Component

@Component
class ContractUploaderInitialization(
    uploaderPort: Int,
    private val contractRepositoryWriter: RepositoryWriter,
    private val contractJournalWriter: JournalWriter
) {

    init {
        RegistrationServiceEndpoint.logger.info { "Start uploader server on port $uploaderPort" }

        val server = embeddedServer(Netty, port = uploaderPort) {
            install(CORS)
            {
                anyHost()
                allowCredentials = true
            }
            install(ContentNegotiation) {
                gson()
            }
            routing {
                post("/contract") {
                    val contract = call.receive(Contract::class)
                    val response = uploadContract(contract)
                    call.respondText(
                        response.message,
                        status = response.code,
                        contentType = ContentType.Application.Json
                    )
                }

                post("/info") {
                    val contract = call.receive(ContractInfo::class)
                    val response = uploadContractInfo(contract)
                    call.respondText(
                        response.message,
                        status = response.code,
                        contentType = ContentType.Application.Json
                    )
                }
            }
        }
        server.start(wait = false)
    }

    private fun uploadContract(body: Contract): Response {
        return try {
            contractRepositoryWriter.writeContract(body.contractName, body.script)
            logger.info("Saved ${body.contractName}")
            Response(HttpStatusCode.NoContent, "")
        } catch (e: Exception) {
            logger.error("Error during writing contract: ${e.message}")
            Response(HttpStatusCode.BadRequest, e.message!!)
        }
    }

    private fun uploadContractInfo(body: ContractInfo): Response {
        return try {
            contractJournalWriter.writeInfo(body.contractName, body.info)
            logger.info("Saved ${body.contractName} info")
            Response(HttpStatusCode.NoContent, "")
        } catch (e: Exception) {
            logger.error("Error during writing contract info: ${e.message}")
            Response(HttpStatusCode.BadRequest, e.message!!)
        }
    }

    companion object : KLogging()
}

data class Contract(val contractName: String, val script: String)

data class ContractInfo(val contractName: String, val info: String)
