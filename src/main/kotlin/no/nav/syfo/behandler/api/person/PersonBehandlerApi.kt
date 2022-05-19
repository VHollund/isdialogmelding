package no.nav.syfo.behandler.api.person

import io.ktor.server.application.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import no.nav.syfo.application.api.authentication.personident
import no.nav.syfo.behandler.BehandlerService
import no.nav.syfo.behandler.api.person.access.PersonAPIConsumerAccessService
import no.nav.syfo.behandler.domain.*
import no.nav.syfo.util.getBearerHeader
import no.nav.syfo.util.getCallId
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime

private val log: Logger = LoggerFactory.getLogger("no.nav.syfo")

const val personApiBehandlerPath = "/api/person/v1/behandler"
const val personBehandlerSelfPath = "/self"

fun Route.registerPersonBehandlerApi(
    behandlerService: BehandlerService,
    personAPIConsumerAccessService: PersonAPIConsumerAccessService,
) {
    route(personApiBehandlerPath) {
        get(personBehandlerSelfPath) {
            val callId = getCallId()
            try {
                val token = getBearerHeader()
                    ?: throw IllegalArgumentException("No Authorization header supplied")
                val requestPersonident = call.personident()
                    ?: throw IllegalArgumentException("No Personident found in token")

                personAPIConsumerAccessService.validateConsumerApplicationClientId(
                    token = token,
                )

                val personBehandlerDTOList = mutableListOf<PersonBehandlerDTO>()
                val fastlege = behandlerService.getAktivFastlegeBehandler(
                    personident = requestPersonident,
                    systemRequest = true,
                    token = token,
                    callId = callId,
                )
                if (fastlege != null && fastlege.hasAnId()) {
                    val behandlerArbeidstakerRelasjon = BehandlerArbeidstakerRelasjon(
                        type = BehandlerArbeidstakerRelasjonstype.FASTLEGE,
                        arbeidstakerPersonident = requestPersonident,
                        mottatt = OffsetDateTime.now(),
                    )
                    val behandler = behandlerService.createOrUpdateBehandlerAndRelasjon(
                        behandler = fastlege,
                        behandlerArbeidstakerRelasjon = behandlerArbeidstakerRelasjon,
                    )
                    personBehandlerDTOList.add(
                        behandler.toPersonBehandlerDTO(
                            behandlerType = BehandlerArbeidstakerRelasjonstype.FASTLEGE,
                        )
                    )
                }
                call.respond(personBehandlerDTOList)
            } catch (e: IllegalArgumentException) {
                val illegalArgumentMessage = "Could not retrieve list of Behandler for Personident"
                log.warn("$illegalArgumentMessage: {}, {}", e.message, callId)
                call.respond(HttpStatusCode.BadRequest, e.message ?: illegalArgumentMessage)
            }
        }
    }
}
