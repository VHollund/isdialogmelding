package no.nav.syfo.behandler.kafka.sykmelding

import no.nav.syfo.application.ApplicationEnvironmentKafka
import no.nav.syfo.application.ApplicationState
import no.nav.syfo.behandler.BehandlerService
import no.nav.syfo.behandler.domain.*
import no.nav.syfo.behandler.domain.Behandler
import no.nav.syfo.behandler.kafka.kafkaSykmeldingConsumerConfig
import no.nav.syfo.domain.*
import org.apache.kafka.clients.consumer.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.*
import java.util.UUID

const val SYKMELDING_TOPIC = "teamsykmelding.ok-sykmelding"

val PROCESS_SYKMELDING_INCOMING_AFTER = LocalDateTime.of(LocalDate.of(2021, Month.OCTOBER, 1), LocalTime.of(0, 0))

private val log: Logger = LoggerFactory.getLogger("no.nav.syfo.behandler.kafka")

fun blockingApplicationLogicSykmelding(
    applicationState: ApplicationState,
    applicationEnvironmentKafka: ApplicationEnvironmentKafka,
    behandlerService: BehandlerService,
) {
    val consumerProperties = kafkaSykmeldingConsumerConfig(applicationEnvironmentKafka)

    val kafkaConsumerSykmelding = KafkaConsumer<String, ReceivedSykmeldingDTO>(consumerProperties)

    kafkaConsumerSykmelding.subscribe(
        listOf(SYKMELDING_TOPIC)
    )
    while (applicationState.ready) {
        pollAndProcessSykmelding(
            kafkaConsumerSykmelding = kafkaConsumerSykmelding,
            behandlerService = behandlerService,
        )
    }
}

fun pollAndProcessSykmelding(
    kafkaConsumerSykmelding: KafkaConsumer<String, ReceivedSykmeldingDTO>,
    behandlerService: BehandlerService,
) {
    val records = kafkaConsumerSykmelding.poll(Duration.ofMillis(1000))
    if (records.count() > 0) {
        processSykmelding(
            behandlerService = behandlerService,
            consumerRecords = records,
        )
        kafkaConsumerSykmelding.commitSync()
    }
}

fun processSykmelding(
    behandlerService: BehandlerService,
    consumerRecords: ConsumerRecords<String, ReceivedSykmeldingDTO>,
) {
    consumerRecords.forEach {
        it.value()?.let { receivedSykmeldingDTO ->
            if (receivedSykmeldingDTO.mottattDato.isAfter(PROCESS_SYKMELDING_INCOMING_AFTER)) {
                COUNT_MOTTATT_SYKMELDING.increment()
                if (validateReceivedSykmelding(it)) {
                    createAndStoreBehandlerFromSykmelding(
                        receivedSykmeldingDTO = receivedSykmeldingDTO,
                        behandlerService = behandlerService,
                    )
                    COUNT_MOTTATT_SYKMELDING_SUCCESS.increment()
                }
            }
        }
    }
}

private fun validateReceivedSykmelding(
    consumerRecord: ConsumerRecord<String, ReceivedSykmeldingDTO>,
): Boolean {
    val receivedSykmeldingDTO = consumerRecord.value()
    if (receivedSykmeldingDTO.sykmelding.behandler.fnr != receivedSykmeldingDTO.personNrLege) {
        COUNT_MOTTATT_SYKMELDING_IGNORED_MISMATCHED.increment()
        return false
    }
    if (receivedSykmeldingDTO.partnerreferanse?.toIntOrNull() == null) {
        COUNT_MOTTATT_SYKMELDING_IGNORED_PARTNERID.increment()
        return false
    }
    if (BehandlerKategori.fromKategoriKode(receivedSykmeldingDTO.legeHelsepersonellkategori) == null) {
        COUNT_MOTTATT_SYKMELDING_IGNORED_BEHANDLERKATEGORI.increment()
        return false
    }
    return true
}

private fun createAndStoreBehandlerFromSykmelding(
    receivedSykmeldingDTO: ReceivedSykmeldingDTO,
    behandlerService: BehandlerService,
) {
    val partnerId = receivedSykmeldingDTO.partnerreferanse!!
    val behandlerKategori = BehandlerKategori.fromKategoriKode(receivedSykmeldingDTO.legeHelsepersonellkategori)!!
    val arbeidstakerPersonident = Personident(receivedSykmeldingDTO.personNrPasient)
    val sykmeldingBehandler = receivedSykmeldingDTO.sykmelding.behandler
    val sykmelder = Behandler(
        behandlerRef = UUID.randomUUID(),
        personident = Personident(sykmeldingBehandler.fnr),
        fornavn = sykmeldingBehandler.fornavn,
        mellomnavn = sykmeldingBehandler.mellomnavn,
        etternavn = sykmeldingBehandler.etternavn,
        herId = sykmeldingBehandler.her?.toIntOrNull(),
        hprId = sykmeldingBehandler.hpr?.toIntOrNull(),
        telefon = sykmeldingBehandler.tlf?.removePrefix("tel:")?.removePrefix("Tel:"),
        kontor = BehandlerKontor(
            partnerId = PartnerId(partnerId.toInt()),
            herId = receivedSykmeldingDTO.legekontorHerId?.toIntOrNull(),
            navn = receivedSykmeldingDTO.legekontorOrgName,
            adresse = sykmeldingBehandler.adresse.gate,
            postnummer = sykmeldingBehandler.adresse.postnummer?.toString(),
            poststed = null,
            orgnummer = receivedSykmeldingDTO.legekontorOrgNr?.let { Virksomhetsnummer(it) },
            dialogmeldingEnabled = false,
            system = receivedSykmeldingDTO.sykmelding.avsenderSystem.navn,
            mottatt = receivedSykmeldingDTO.mottattDato.toOffsetDateTime(),
        ),
        kategori = behandlerKategori,
        mottatt = receivedSykmeldingDTO.mottattDato.toOffsetDateTime(),
    )

    val behandlerArbeidstakerRelasjon = BehandlerArbeidstakerRelasjon(
        type = BehandlerArbeidstakerRelasjonstype.SYKMELDER,
        arbeidstakerPersonident = arbeidstakerPersonident,
        mottatt = receivedSykmeldingDTO.mottattDato.toOffsetDateTime(),
    )
    behandlerService.createOrUpdateBehandlerAndRelasjon(
        behandler = sykmelder,
        behandlerArbeidstakerRelasjon = behandlerArbeidstakerRelasjon,
    )
}

private fun LocalDateTime.toOffsetDateTime() = ZonedDateTime.of(this, ZoneId.of("Europe/Oslo")).toOffsetDateTime()
