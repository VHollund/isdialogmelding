package no.nav.syfo.behandler.api

import io.ktor.application.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*
import no.nav.syfo.behandler.BehandlerService
import no.nav.syfo.behandler.domain.toBehandlerDialogmeldingDTO
import no.nav.syfo.domain.PersonIdentNumber
import no.nav.syfo.util.getBearerHeader
import no.nav.syfo.util.getCallId
import no.nav.syfo.util.getPersonIdentHeader
import org.slf4j.Logger
import org.slf4j.LoggerFactory

private val log: Logger = LoggerFactory.getLogger("no.nav.syfo")

const val behandlerPath = "/api/v1/behandler"
const val behandlerPersonident = "/personident"

fun Route.registerBehandlerApi(
    behandlerService: BehandlerService,
) {
    route(behandlerPath) {
        get(behandlerPersonident) {
            val callId = getCallId()
            try {
                val token = getBearerHeader()
                    ?: throw IllegalArgumentException("No Authorization header supplied")
                val personIdentNumber = getPersonIdentHeader()?.let { personIdent ->
                    PersonIdentNumber(personIdent)
                } ?: throw IllegalArgumentException("No PersonIdent supplied")

                val fastlege = behandlerService.getFastlegeMedPartnerinfo(
                    personIdentNumber = personIdentNumber,
                    token = token,
                    callId = callId,
                )
                val behandlerDialogmeldingDTOList =
                    fastlege?.let { listOf(fastlege.toBehandlerDialogmeldingDTO()) } ?: emptyList()

                call.respond(behandlerDialogmeldingDTOList)
            } catch (e: IllegalArgumentException) {
                val illegalArgumentMessage = "Could not retrieve list of BehandlerDialogmelding for PersonIdent"
                log.warn("$illegalArgumentMessage: {}, {}", e.message, callId)
                call.respond(HttpStatusCode.BadRequest, e.message ?: illegalArgumentMessage)
            }
        }
    }
}
