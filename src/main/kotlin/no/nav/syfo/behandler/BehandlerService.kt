package no.nav.syfo.behandler

import no.nav.syfo.application.database.DatabaseInterface
import no.nav.syfo.behandler.database.*
import no.nav.syfo.behandler.database.domain.*
import no.nav.syfo.behandler.domain.*
import no.nav.syfo.behandler.fastlege.FastlegeClient
import no.nav.syfo.behandler.fastlege.toBehandler
import no.nav.syfo.behandler.partnerinfo.PartnerinfoClient
import no.nav.syfo.domain.PartnerId
import no.nav.syfo.domain.Personident
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.OffsetDateTime

private val log = LoggerFactory.getLogger("no.nav.syfo.behandler")

class BehandlerService(
    private val fastlegeClient: FastlegeClient,
    private val partnerinfoClient: PartnerinfoClient,
    private val database: DatabaseInterface,
    private val toggleSykmeldingbehandlere: Boolean,
) {
    suspend fun getBehandlere(
        personident: Personident,
        token: String,
        callId: String,
    ): List<Pair<Behandler, BehandlerArbeidstakerRelasjonstype>> {
        val behandlere = mutableListOf<Pair<Behandler, BehandlerArbeidstakerRelasjonstype>>()

        val fastlegeBehandler = getFastlegeBehandler(
            personident = personident,
            token = token,
            callId = callId,
        )
        fastlegeBehandler?.let { behandlere.add(Pair(it, BehandlerArbeidstakerRelasjonstype.FASTLEGE)) }

        if (toggleSykmeldingbehandlere) {
            database.getSykmeldereExtended(personident)
                .forEach { (pBehandler, pBehandlerKontor) ->
                    behandlere.add(
                        Pair(
                            pBehandler.toBehandler(pBehandlerKontor),
                            BehandlerArbeidstakerRelasjonstype.SYKMELDER,
                        )
                    )
                }
        }
        return behandlere.removeDuplicates()
    }

    private suspend fun getFastlegeBehandler(
        personident: Personident,
        token: String,
        callId: String,
    ): Behandler? {
        val fastlege = getAktivFastlegeBehandler(
            personident = personident,
            token = token,
            callId = callId,
        )
        if (fastlege != null && fastlege.hasAnId()) {
            val behandlerArbeidstakerRelasjon = BehandlerArbeidstakerRelasjon(
                type = BehandlerArbeidstakerRelasjonstype.FASTLEGE,
                arbeidstakerPersonident = personident,
                mottatt = OffsetDateTime.now(),
            )
            return createOrUpdateBehandlerAndRelasjon(
                behandler = fastlege,
                behandlerArbeidstakerRelasjon = behandlerArbeidstakerRelasjon,
            )
        }
        return null
    }

    suspend fun getAktivFastlegeBehandler(
        personident: Personident,
        token: String,
        callId: String,
        systemRequest: Boolean = false,
    ): Behandler? {
        val fastlegeResponse = fastlegeClient.fastlege(
            personident = personident,
            systemRequest = systemRequest,
            token = token,
            callId = callId,
        ) ?: return null

        if (fastlegeResponse.foreldreEnhetHerId == null) {
            log.warn("Aktiv fastlege missing foreldreEnhetHerId so cannot request partnerinfo")
            return null
        }

        val partnerinfoResponse = partnerinfoClient.partnerinfo(
            herId = fastlegeResponse.foreldreEnhetHerId.toString(),
            systemRequest = systemRequest,
            token = token,
            callId = callId,
        )

        return if (partnerinfoResponse != null) {
            fastlegeResponse.toBehandler(
                partnerId = PartnerId(partnerinfoResponse.partnerId),
            )
        } else null
    }

    fun createOrUpdateBehandlerAndRelasjon(
        behandler: Behandler,
        behandlerArbeidstakerRelasjon: BehandlerArbeidstakerRelasjon,
    ): Behandler {
        val pBehandler = getBehandler(behandler = behandler)
            ?: return createBehandlerAndKontorAndRelasjon(
                behandler = behandler,
                behandlerArbeidstakerRelasjon = behandlerArbeidstakerRelasjon,
            )

        updateBehandler(
            behandler = behandler
        )

        createOrUpdateBehandlerArbeidstakerRelasjon(
            pBehandler = pBehandler,
            behandlerArbeidstakerRelasjon = behandlerArbeidstakerRelasjon,
        )

        return pBehandler.toBehandler(
            kontor = database.getBehandlerKontorById(pBehandler.kontorId)
        )
    }

    private fun createOrUpdateBehandlerArbeidstakerRelasjon(
        behandlerArbeidstakerRelasjon: BehandlerArbeidstakerRelasjon,
        pBehandler: PBehandler,
    ) {

        val isBytteAvFastlegeOrNewRelasjon = isBytteAvFastlegeOrNewRelasjon(
            behandlerArbeidstakerRelasjon = behandlerArbeidstakerRelasjon,
            pBehandler = pBehandler,
        )

        if (isBytteAvFastlegeOrNewRelasjon) {
            addBehandlerToArbeidstaker(
                behandlerArbeidstakerRelasjon = behandlerArbeidstakerRelasjon,
                behandlerId = pBehandler.id,
            )
        } else if (behandlerArbeidstakerRelasjon.type == BehandlerArbeidstakerRelasjonstype.SYKMELDER) {
            database.updateBehandlerArbeidstakerRelasjon(
                behandlerArbeidstakerRelasjon = behandlerArbeidstakerRelasjon,
                behandlerId = pBehandler.id,
            )
        }
    }

    private fun isBytteAvFastlegeOrNewRelasjon(
        behandlerArbeidstakerRelasjon: BehandlerArbeidstakerRelasjon,
        pBehandler: PBehandler,
    ): Boolean {
        val pBehandlereForArbeidstakerList =
            database.getBehandlerAndRelasjonstypeList(
                arbeidstakerIdent = behandlerArbeidstakerRelasjon.arbeidstakerPersonident,
            )

        val isBytteAvFastlege = behandlerArbeidstakerRelasjon.type == BehandlerArbeidstakerRelasjonstype.FASTLEGE && pBehandlereForArbeidstakerList
            .filter { (_, behandlerType) -> behandlerType == BehandlerArbeidstakerRelasjonstype.FASTLEGE }
            .map { (pBehandler, _) -> pBehandler.id }.firstOrNull() != pBehandler.id

        val behandlerIkkeKnyttetTilArbeidstaker = !pBehandlereForArbeidstakerList
            .filter { (_, behandlerType) -> behandlerType == behandlerArbeidstakerRelasjon.type }
            .map { (pBehandler, _) -> pBehandler.id }.contains(pBehandler.id)

        return isBytteAvFastlege || behandlerIkkeKnyttetTilArbeidstaker
    }

    private fun getBehandler(behandler: Behandler): PBehandler? {
        return when {
            behandler.personident != null -> database.getBehandlerByBehandlerPersonidentAndPartnerId(
                behandlerPersonident = behandler.personident,
                partnerId = behandler.kontor.partnerId,
            )
            behandler.hprId != null -> database.getBehandlerByHprIdAndPartnerId(
                hprId = behandler.hprId,
                partnerId = behandler.kontor.partnerId,
            )
            behandler.herId != null -> database.getBehandlerByHerIdAndPartnerId(
                herId = behandler.herId,
                partnerId = behandler.kontor.partnerId,
            )
            else -> throw IllegalArgumentException("Behandler missing personident, hprId and herId")
        }
    }

    private fun createBehandlerAndKontorAndRelasjon(
        behandlerArbeidstakerRelasjon: BehandlerArbeidstakerRelasjon,
        behandler: Behandler,
    ): Behandler {
        database.connection.use { connection ->
            val kontorId = connection.createOrUpdateKontor(behandler)

            val pBehandler = connection.createBehandler(
                behandler = behandler,
                kontorId = kontorId,
            )
            connection.createBehandlerArbeidstakerRelasjon(
                behandlerArbeidstakerRelasjon = behandlerArbeidstakerRelasjon,
                behandlerId = pBehandler.id,
            )
            connection.commit()

            return pBehandler.toBehandler(
                database.getBehandlerKontorById(pBehandler.kontorId)
            )
        }
    }

    private fun Connection.createOrUpdateKontor(
        behandler: Behandler,
    ): Int {
        val pBehandlerKontor = this.getBehandlerKontor(behandler.kontor.partnerId)

        return if (pBehandlerKontor != null) {
            updateBehandlerKontor(
                behandler = behandler,
                existingBehandlerKontor = pBehandlerKontor,
            )
            pBehandlerKontor.id
        } else {
            createBehandlerKontor(behandler.kontor)
        }
    }

    private fun updateBehandler(
        behandler: Behandler,
    ) {
        database.connection.use { connection ->
            val existingBehandlerKontor = connection.getBehandlerKontor(behandler.kontor.partnerId)!!
            connection.updateBehandlerKontor(
                behandler = behandler,
                existingBehandlerKontor = existingBehandlerKontor,
            )
            connection.commit()
        }
    }

    private fun Connection.updateBehandlerKontor(
        behandler: Behandler,
        existingBehandlerKontor: PBehandlerKontor,
    ) {
        if (shouldUpdateKontorSystem(behandler.kontor, existingBehandlerKontor)) {
            updateBehandlerKontorSystem(behandler.kontor.partnerId, behandler.kontor)
        }
        if (shouldUpdateKontorAdresse(behandler.kontor, existingBehandlerKontor)) {
            updateBehandlerKontorAddress(behandler.kontor.partnerId, behandler.kontor)
        }
    }

    private fun shouldUpdateKontorSystem(
        behandlerKontor: BehandlerKontor,
        existingBehandlerKontor: PBehandlerKontor,
    ): Boolean =
        !behandlerKontor.system.isNullOrBlank() && (
            existingBehandlerKontor.system.isNullOrBlank() ||
                behandlerKontor.mottatt.isAfter(existingBehandlerKontor.mottatt)
            )

    private fun shouldUpdateKontorAdresse(
        behandlerKontor: BehandlerKontor,
        existingBehandlerKontor: PBehandlerKontor,
    ): Boolean =
        behandlerKontor.harKomplettAdresse() && behandlerKontor.mottatt.isAfter(existingBehandlerKontor.mottatt)

    private fun addBehandlerToArbeidstaker(
        behandlerArbeidstakerRelasjon: BehandlerArbeidstakerRelasjon,
        behandlerId: Int,
    ) {
        database.connection.use { connection ->
            connection.createBehandlerArbeidstakerRelasjon(
                behandlerArbeidstakerRelasjon = behandlerArbeidstakerRelasjon,
                behandlerId = behandlerId,
            )
            connection.commit()
        }
    }
}
