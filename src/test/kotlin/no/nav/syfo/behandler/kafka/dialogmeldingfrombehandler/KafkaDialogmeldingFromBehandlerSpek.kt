package no.nav.syfo.behandler.kafka.dialogmeldingfrombehandler

import io.ktor.server.testing.*
import io.mockk.*
import kotlinx.coroutines.runBlocking
import no.nav.syfo.behandler.BehandlerService
import no.nav.syfo.behandler.database.getBehandlerKontor
import no.nav.syfo.behandler.domain.BehandlerArbeidstakerRelasjon
import no.nav.syfo.behandler.domain.BehandlerArbeidstakerRelasjonstype
import no.nav.syfo.behandler.fastlege.toBehandler
import no.nav.syfo.testhelper.*
import no.nav.syfo.testhelper.generator.*
import org.amshove.kluent.`should be`
import org.amshove.kluent.shouldNotBe
import org.apache.kafka.clients.consumer.*
import org.apache.kafka.common.TopicPartition
import org.spekframework.spek2.Spek
import org.spekframework.spek2.style.specification.describe
import java.time.Duration
import java.time.OffsetDateTime
import java.util.*

class KafkaDialogmeldingFromBehandlerSpek : Spek({

    with(TestApplicationEngine()) {
        start()
        val database = ExternalMockEnvironment.instance.database
        val behandlerService = BehandlerService(
            fastlegeClient = mockk(),
            partnerinfoClient = mockk(),
            database = database,
            ExternalMockEnvironment.instance.environment.toggleSykmeldingbehandlere,
        )

        afterEachTest {
            database.dropData()
        }

        describe("Read dialogmelding sent from behandler to NAV from Kafka Topic") {

            describe("Receive dialogmelding from behandler") {
                describe("Happy path") {
                    it("mark kontor as ready to receive dialogmeldinger") {
                        addBehandlerAndKontorToDatabase(behandlerService)
                        val dialogmelding = generateDialogmeldingFromBehandlerDTO(UUID.randomUUID())
                        val mockConsumer = mockKafkaConsumerWithDialogmelding(dialogmelding)

                        runBlocking {
                            pollAndProcessDialogmeldingFromBehandler(
                                kafkaConsumerDialogmeldingFromBehandler = mockConsumer,
                                database = database,
                            )
                        }

                        verify(exactly = 1) { mockConsumer.commitSync() }
                        val kontor = database.connection.getBehandlerKontor(UserConstants.PARTNERID)
                        kontor?.dialogmeldingEnabled shouldNotBe null
                    }
                }

                describe("Unhappy path") {
                    it("don't mark kontor as ready to receive dialogmeldinger if kontor isn't found in database") {
                        val dialogmelding = generateDialogmeldingFromBehandlerDTO(UUID.randomUUID())
                        val mockConsumer = mockKafkaConsumerWithDialogmelding(dialogmelding)

                        runBlocking {
                            pollAndProcessDialogmeldingFromBehandler(
                                kafkaConsumerDialogmeldingFromBehandler = mockConsumer,
                                database = database,
                            )
                        }

                        verify(exactly = 1) { mockConsumer.commitSync() }
                        val kontor = database.connection.getBehandlerKontor(UserConstants.PARTNERID)
                        kontor `should be` null
                    }

                    it("don't mark kontor as ready to receive dialogmeldinger if no partnerId is found") {
                        addBehandlerAndKontorToDatabase(behandlerService)
                        val dialogmelding = generateDialogmeldingFromBehandlerDTOWithInvalidXml(UUID.randomUUID())
                        val mockConsumer = mockKafkaConsumerWithDialogmelding(dialogmelding)

                        runBlocking {
                            pollAndProcessDialogmeldingFromBehandler(
                                kafkaConsumerDialogmeldingFromBehandler = mockConsumer,
                                database = database,
                            )
                        }

                        verify(exactly = 1) { mockConsumer.commitSync() }
                        val kontor = database.connection.getBehandlerKontor(UserConstants.PARTNERID)
                        kontor shouldNotBe null
                        kontor!!.dialogmeldingEnabled `should be` null
                    }
                }
            }
        }
    }
})

fun addBehandlerAndKontorToDatabase(behandlerService: BehandlerService) {
    val behandler = generateFastlegeResponse().toBehandler(
        partnerId = UserConstants.PARTNERID,
        dialogmeldingEnabled = false,
    )

    val behandlerArbeidstakerRelasjon = BehandlerArbeidstakerRelasjon(
        type = BehandlerArbeidstakerRelasjonstype.FASTLEGE,
        arbeidstakerPersonident = UserConstants.ARBEIDSTAKER_FNR,
        mottatt = OffsetDateTime.now(),
    )

    behandlerService.createOrUpdateBehandlerAndRelasjon(behandler, behandlerArbeidstakerRelasjon)
}

fun mockKafkaConsumerWithDialogmelding(dialogmelding: KafkaDialogmeldingFromBehandlerDTO): KafkaConsumer<String, KafkaDialogmeldingFromBehandlerDTO> {
    val partition = 0
    val dialogmeldingTopicPartition = TopicPartition(
        DIALOGMELDING_FROM_BEHANDLER_TOPIC,
        partition,
    )

    val dialogmeldingRecord = ConsumerRecord(
        DIALOGMELDING_FROM_BEHANDLER_TOPIC,
        partition,
        1,
        dialogmelding.msgId,
        dialogmelding,
    )

    val mockConsumer = mockk<KafkaConsumer<String, KafkaDialogmeldingFromBehandlerDTO>>()
    every { mockConsumer.poll(any<Duration>()) } returns ConsumerRecords(
        mapOf(
            dialogmeldingTopicPartition to listOf(
                dialogmeldingRecord,
            )
        )
    )
    every { mockConsumer.commitSync() } returns Unit

    return mockConsumer
}
