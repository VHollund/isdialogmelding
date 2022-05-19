package no.nav.syfo.behandler.kafka.sykmelding

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.http.*
import io.ktor.server.testing.*
import io.mockk.*
import kotlinx.coroutines.runBlocking
import no.nav.syfo.behandler.BehandlerService
import no.nav.syfo.behandler.api.*
import no.nav.syfo.behandler.database.*
import no.nav.syfo.behandler.domain.*
import no.nav.syfo.behandler.fastlege.FastlegeClient
import no.nav.syfo.behandler.fastlege.toBehandler
import no.nav.syfo.behandler.partnerinfo.PartnerinfoClient
import no.nav.syfo.client.azuread.AzureAdClient
import no.nav.syfo.domain.PartnerId
import no.nav.syfo.domain.Personident
import no.nav.syfo.testhelper.*
import no.nav.syfo.testhelper.generator.*
import no.nav.syfo.util.*
import org.amshove.kluent.*
import org.apache.kafka.clients.consumer.*
import org.apache.kafka.common.TopicPartition
import org.spekframework.spek2.Spek
import org.spekframework.spek2.style.specification.describe
import java.time.*
import java.util.UUID

class KafkaSykmeldingSpek : Spek({

    val objectMapper: ObjectMapper = configuredJacksonMapper()

    with(TestApplicationEngine()) {
        start()
        val externalMockEnvironment = ExternalMockEnvironment.instance
        val database = externalMockEnvironment.database
        application.testApiModule(externalMockEnvironment = externalMockEnvironment)
        val azureAdClient = AzureAdClient(
            azureAppClientId = externalMockEnvironment.environment.aadAppClient,
            azureAppClientSecret = externalMockEnvironment.environment.azureAppClientSecret,
            azureOpenidConfigTokenEndpoint = externalMockEnvironment.environment.azureOpenidConfigTokenEndpoint,
        )
        val behandlerService = BehandlerService(
            fastlegeClient = FastlegeClient(
                azureAdClient = azureAdClient,
                fastlegeRestClientId = externalMockEnvironment.environment.fastlegeRestClientId,
                fastlegeRestUrl = externalMockEnvironment.environment.fastlegeRestUrl,
            ),
            partnerinfoClient = PartnerinfoClient(
                azureAdClient = azureAdClient,
                syfoPartnerinfoClientId = externalMockEnvironment.environment.syfoPartnerinfoClientId,
                syfoPartnerinfoUrl = externalMockEnvironment.environment.syfoPartnerinfoUrl,
            ),
            database = database,
            toggleSykmeldingbehandlere = externalMockEnvironment.environment.toggleSykmeldingbehandlere,
        )

        val mockConsumer = mockk<KafkaConsumer<String, ReceivedSykmeldingDTO>>()

        beforeEachTest {
            clearMocks(mockConsumer)
            every { mockConsumer.commitSync() } returns Unit
        }

        afterEachTest {
            database.dropData()
        }

        describe(KafkaSykmeldingSpek::class.java.simpleName) {
            describe("Motta sykmelding") {
                val kafkaPartition = 0
                val sykmeldingTopicPartition = TopicPartition(
                    SYKMELDING_TOPIC,
                    kafkaPartition,
                )

                describe("Happy path") {
                    it("should persist behandler from incoming sykmelding") {
                        val sykmelding = generateSykmeldingDTO(
                            uuid = UUID.randomUUID(),
                        )
                        every { mockConsumer.poll(any<Duration>()) } returns
                            consumerRecords(sykmeldingTopicPartition, kafkaPartition, sykmelding)

                        val kontorBefore = database.connection.getBehandlerKontor(PartnerId(sykmelding.partnerreferanse!!.toInt()))
                        val behandlereBefore = database.getBehandlerByArbeidstaker(Personident(sykmelding.personNrPasient))
                        kontorBefore shouldBe null
                        behandlereBefore.size shouldBeEqualTo 0

                        runBlocking {
                            pollAndProcessSykmelding(
                                kafkaConsumerSykmelding = mockConsumer,
                                behandlerService = behandlerService,
                            )
                        }
                        verify(exactly = 1) { mockConsumer.commitSync() }
                        val kontorAfter = database.connection.getBehandlerKontor(PartnerId(sykmelding.partnerreferanse!!.toInt()))
                        kontorAfter!!.partnerId shouldBeEqualTo sykmelding.partnerreferanse
                        kontorAfter.herId shouldBeEqualTo sykmelding.legekontorHerId

                        val behandlereAfter = database.getBehandlerByArbeidstaker(Personident(sykmelding.personNrPasient))
                        behandlereAfter.size shouldBeEqualTo 1
                        behandlereAfter[0].personident shouldBeEqualTo sykmelding.personNrLege
                        behandlereAfter[0].kategori shouldBeEqualTo BehandlerKategori.LEGE.name
                        behandlereAfter[0].herId shouldBeEqualTo sykmelding.sykmelding.behandler.her
                        behandlereAfter[0].fornavn shouldBeEqualTo sykmelding.sykmelding.behandler.fornavn
                        behandlereAfter[0].etternavn shouldBeEqualTo sykmelding.sykmelding.behandler.etternavn
                        behandlereAfter[0].telefon shouldBeEqualTo sykmelding.sykmelding.behandler.tlf

                        val behandlerRelasjonerAfter = database.getBehandlerArbeidstakerRelasjon(
                            personident = Personident(sykmelding.personNrPasient),
                            behandlerRef = behandlereAfter[0].behandlerRef,
                        )
                        behandlerRelasjonerAfter.size shouldBeEqualTo 1
                        behandlerRelasjonerAfter[0].type shouldBeEqualTo BehandlerArbeidstakerRelasjonstype.SYKMELDER.name
                    }
                    it("should persist behandler from incoming sykmelding even if herid is blank") {
                        val sykmelding = generateSykmeldingDTO(
                            uuid = UUID.randomUUID(),
                            herId = " ",
                        )
                        every { mockConsumer.poll(any<Duration>()) } returns
                            consumerRecords(sykmeldingTopicPartition, kafkaPartition, sykmelding)

                        val kontorBefore = database.connection.getBehandlerKontor(PartnerId(sykmelding.partnerreferanse!!.toInt()))
                        val behandlereBefore = database.getBehandlerByArbeidstaker(Personident(sykmelding.personNrPasient))
                        kontorBefore shouldBe null
                        behandlereBefore.size shouldBeEqualTo 0

                        runBlocking {
                            pollAndProcessSykmelding(
                                kafkaConsumerSykmelding = mockConsumer,
                                behandlerService = behandlerService,
                            )
                        }
                        verify(exactly = 1) { mockConsumer.commitSync() }
                        val kontorAfter = database.connection.getBehandlerKontor(PartnerId(sykmelding.partnerreferanse!!.toInt()))
                        kontorAfter!!.partnerId shouldBeEqualTo sykmelding.partnerreferanse
                        kontorAfter.herId shouldBeEqualTo sykmelding.legekontorHerId

                        val behandlereAfter = database.getBehandlerByArbeidstaker(Personident(sykmelding.personNrPasient))
                        behandlereAfter.size shouldBeEqualTo 1
                        behandlereAfter[0].personident shouldBeEqualTo sykmelding.personNrLege
                        behandlereAfter[0].kategori shouldBeEqualTo BehandlerKategori.LEGE.name
                        behandlereAfter[0].herId shouldBe null
                        behandlereAfter[0].fornavn shouldBeEqualTo sykmelding.sykmelding.behandler.fornavn
                        behandlereAfter[0].etternavn shouldBeEqualTo sykmelding.sykmelding.behandler.etternavn
                        behandlereAfter[0].telefon shouldBeEqualTo sykmelding.sykmelding.behandler.tlf

                        val behandlerRelasjonerAfter = database.getBehandlerArbeidstakerRelasjon(
                            personident = Personident(sykmelding.personNrPasient),
                            behandlerRef = behandlereAfter[0].behandlerRef,
                        )
                        behandlerRelasjonerAfter.size shouldBeEqualTo 1
                        behandlerRelasjonerAfter[0].type shouldBeEqualTo BehandlerArbeidstakerRelasjonstype.SYKMELDER.name
                    }
                    it("should remove telephone-prefix") {
                        val sykmelding = generateSykmeldingDTO(
                            uuid = UUID.randomUUID(),
                            fornavnLege = "ANNE",
                            etternavnLege = "LEGE",
                            telefonLege = "tel:99999999"
                        )
                        every { mockConsumer.poll(any<Duration>()) } returns
                            consumerRecords(sykmeldingTopicPartition, kafkaPartition, sykmelding)

                        runBlocking {
                            pollAndProcessSykmelding(
                                kafkaConsumerSykmelding = mockConsumer,
                                behandlerService = behandlerService,
                            )
                        }
                        verify(exactly = 1) { mockConsumer.commitSync() }
                        val behandlerList = database.getBehandlerByArbeidstaker(Personident(sykmelding.personNrPasient))
                        behandlerList.size shouldBeEqualTo 1
                        behandlerList[0].personident shouldBeEqualTo sykmelding.personNrLege
                        behandlerList[0].fornavn shouldBeEqualTo sykmelding.sykmelding.behandler.fornavn
                        behandlerList[0].etternavn shouldBeEqualTo sykmelding.sykmelding.behandler.etternavn
                        behandlerList[0].telefon shouldBeEqualTo "99999999"
                    }
                    it("should add second behandler from incoming sykmelding") {
                        val sykmelding = generateSykmeldingDTO(
                            uuid = UUID.randomUUID(),
                        )
                        every { mockConsumer.poll(any<Duration>()) } returns
                            consumerRecords(sykmeldingTopicPartition, kafkaPartition, sykmelding)

                        runBlocking {
                            pollAndProcessSykmelding(
                                kafkaConsumerSykmelding = mockConsumer,
                                behandlerService = behandlerService,
                            )
                        }
                        verify(exactly = 1) { mockConsumer.commitSync() }
                        val kontor = database.connection.getBehandlerKontor(PartnerId(sykmelding.partnerreferanse!!.toInt()))
                        kontor!!.partnerId shouldBeEqualTo sykmelding.partnerreferanse

                        val behandler = database.getBehandlerByArbeidstaker(Personident(sykmelding.personNrPasient))
                        behandler.size shouldBeEqualTo 1

                        val newSykmelding = generateSykmeldingDTO(
                            uuid = UUID.randomUUID(),
                            personNrLege = "02020212346",
                            behandlerFnr = "02020212346",
                            herId = "1234",
                            hprId = "4321",
                        )
                        every { mockConsumer.poll(any<Duration>()) } returns
                            consumerRecords(sykmeldingTopicPartition, kafkaPartition, newSykmelding, 2)

                        runBlocking {
                            pollAndProcessSykmelding(
                                kafkaConsumerSykmelding = mockConsumer,
                                behandlerService = behandlerService,
                            )
                        }
                        verify(exactly = 2) { mockConsumer.commitSync() }

                        val behandlerAfterSecondSykmelding = database.getBehandlerByArbeidstaker(Personident(sykmelding.personNrPasient))
                        behandlerAfterSecondSykmelding.size shouldBeEqualTo 2
                    }
                    it("sykmelding from existing fastlege should add new sykmelder-relation") {
                        val behandler =
                            behandlerService.createOrUpdateBehandlerAndRelasjon(
                                generateFastlegeResponse().toBehandler(UserConstants.PARTNERID),
                                BehandlerArbeidstakerRelasjon(
                                    type = BehandlerArbeidstakerRelasjonstype.FASTLEGE,
                                    arbeidstakerPersonident = UserConstants.ARBEIDSTAKER_FNR,
                                    mottatt = OffsetDateTime.now(),
                                )
                            )

                        val pBehandlerList = database.getBehandlerByArbeidstaker(
                            UserConstants.ARBEIDSTAKER_FNR,
                        )
                        pBehandlerList.size shouldBeEqualTo 1

                        val sykmelding = generateSykmeldingDTO(
                            uuid = UUID.randomUUID(),
                            personNrPasient = UserConstants.ARBEIDSTAKER_FNR.value,
                            personNrLege = behandler.personident!!.value,
                            behandlerFnr = behandler.personident!!.value,
                            herId = behandler.herId.toString(),
                            hprId = behandler.hprId.toString(),
                            partnerreferanse = UserConstants.PARTNERID.toString(),
                            kontorHerId = UserConstants.HERID.toString(),
                        )
                        every { mockConsumer.poll(any<Duration>()) } returns
                            consumerRecords(sykmeldingTopicPartition, kafkaPartition, sykmelding)

                        runBlocking {
                            pollAndProcessSykmelding(
                                kafkaConsumerSykmelding = mockConsumer,
                                behandlerService = behandlerService,
                            )
                        }
                        verify(exactly = 1) { mockConsumer.commitSync() }
                        val kontorAfter = database.connection.getBehandlerKontor(PartnerId(sykmelding.partnerreferanse!!.toInt()))
                        kontorAfter!!.partnerId shouldBeEqualTo sykmelding.partnerreferanse
                        kontorAfter.herId shouldBeEqualTo sykmelding.legekontorHerId

                        val pBehandlerListAfter = database.getBehandlerAndRelasjonstypeList(
                            UserConstants.ARBEIDSTAKER_FNR,
                        )
                        pBehandlerListAfter.size shouldBeEqualTo 2
                        pBehandlerListAfter[0].first.personident shouldBeEqualTo sykmelding.personNrLege
                        pBehandlerListAfter[0].first.kategori shouldBeEqualTo BehandlerKategori.LEGE.name
                        pBehandlerListAfter[0].second shouldBeEqualTo BehandlerArbeidstakerRelasjonstype.SYKMELDER
                        pBehandlerListAfter[1].first.personident shouldBeEqualTo sykmelding.personNrLege
                        pBehandlerListAfter[1].first.kategori shouldBeEqualTo BehandlerKategori.LEGE.name
                        pBehandlerListAfter[1].second shouldBeEqualTo BehandlerArbeidstakerRelasjonstype.FASTLEGE

                        val behandlerRelasjonAfter = database.getBehandlerArbeidstakerRelasjon(
                            personident = UserConstants.ARBEIDSTAKER_FNR,
                            behandlerRef = behandler.behandlerRef,
                        )
                        behandlerRelasjonAfter.size shouldBeEqualTo 2
                        behandlerRelasjonAfter[0].type shouldBeEqualTo BehandlerArbeidstakerRelasjonstype.SYKMELDER.name
                        behandlerRelasjonAfter[1].type shouldBeEqualTo BehandlerArbeidstakerRelasjonstype.FASTLEGE.name
                    }
                    it("should not create duplicate when same sykmelder twice, but updates timestamp") {
                        val sykmelding = generateSykmeldingDTO(
                            uuid = UUID.randomUUID(),
                        )
                        every { mockConsumer.poll(any<Duration>()) } returns
                            consumerRecords(sykmeldingTopicPartition, kafkaPartition, sykmelding)

                        runBlocking {
                            pollAndProcessSykmelding(
                                kafkaConsumerSykmelding = mockConsumer,
                                behandlerService = behandlerService,
                            )
                        }
                        verify(exactly = 1) { mockConsumer.commitSync() }
                        val kontor = database.connection.getBehandlerKontor(PartnerId(sykmelding.partnerreferanse!!.toInt()))
                        kontor!!.partnerId shouldBeEqualTo sykmelding.partnerreferanse

                        val behandler = database.getBehandlerByArbeidstaker(Personident(sykmelding.personNrPasient))
                        behandler.size shouldBeEqualTo 1

                        val behandlerRelasjon = database.getBehandlerArbeidstakerRelasjon(
                            personident = Personident(sykmelding.personNrPasient),
                            behandlerRef = behandler[0].behandlerRef,
                        )
                        behandlerRelasjon.size shouldBeEqualTo 1

                        val newSykmelding = generateSykmeldingDTO(
                            uuid = UUID.randomUUID(),
                        )
                        every { mockConsumer.poll(any<Duration>()) } returns
                            consumerRecords(sykmeldingTopicPartition, kafkaPartition, newSykmelding, 2)

                        runBlocking {
                            pollAndProcessSykmelding(
                                kafkaConsumerSykmelding = mockConsumer,
                                behandlerService = behandlerService,
                            )
                        }
                        verify(exactly = 2) { mockConsumer.commitSync() }

                        val behandlerAfterSecondSykmelding = database.getBehandlerByArbeidstaker(Personident(sykmelding.personNrPasient))
                        behandlerAfterSecondSykmelding.size shouldBeEqualTo 1

                        val behandlerRelasjonAfterSecondSykmelding = database.getBehandlerArbeidstakerRelasjon(
                            personident = Personident(sykmelding.personNrPasient),
                            behandlerRef = behandler[0].behandlerRef,
                        )
                        behandlerRelasjonAfterSecondSykmelding.size shouldBeEqualTo 1
                        behandlerRelasjonAfterSecondSykmelding[0].updatedAt shouldBeGreaterThan behandlerRelasjon[0].updatedAt
                        behandlerRelasjonAfterSecondSykmelding[0].mottatt shouldBeGreaterThan behandlerRelasjon[0].mottatt
                    }
                    it("should not create duplicate when same sykmelder twice and should not update timestamps if second sykmelding is older") {
                        val sykmelding = generateSykmeldingDTO(
                            uuid = UUID.randomUUID(),
                            mottattTidspunkt = LocalDateTime.now().minusDays(1),
                            behandletTidspunkt = LocalDateTime.now().minusDays(1),
                        )
                        every { mockConsumer.poll(any<Duration>()) } returns
                            consumerRecords(sykmeldingTopicPartition, kafkaPartition, sykmelding)

                        runBlocking {
                            pollAndProcessSykmelding(
                                kafkaConsumerSykmelding = mockConsumer,
                                behandlerService = behandlerService,
                            )
                        }
                        verify(exactly = 1) { mockConsumer.commitSync() }
                        val kontor = database.connection.getBehandlerKontor(PartnerId(sykmelding.partnerreferanse!!.toInt()))
                        kontor!!.partnerId shouldBeEqualTo sykmelding.partnerreferanse

                        val behandler = database.getBehandlerByArbeidstaker(Personident(sykmelding.personNrPasient))
                        behandler.size shouldBeEqualTo 1

                        val behandlerRelasjon = database.getBehandlerArbeidstakerRelasjon(
                            personident = Personident(sykmelding.personNrPasient),
                            behandlerRef = behandler[0].behandlerRef,
                        )
                        behandlerRelasjon.size shouldBeEqualTo 1

                        val newSykmelding = generateSykmeldingDTO(
                            uuid = UUID.randomUUID(),
                            mottattTidspunkt = LocalDateTime.now().minusDays(2),
                            behandletTidspunkt = LocalDateTime.now().minusDays(2),
                        )
                        every { mockConsumer.poll(any<Duration>()) } returns
                            consumerRecords(sykmeldingTopicPartition, kafkaPartition, newSykmelding, 2)

                        runBlocking {
                            pollAndProcessSykmelding(
                                kafkaConsumerSykmelding = mockConsumer,
                                behandlerService = behandlerService,
                            )
                        }
                        verify(exactly = 2) { mockConsumer.commitSync() }

                        val behandlerAfterSecondSykmelding = database.getBehandlerByArbeidstaker(Personident(sykmelding.personNrPasient))
                        behandlerAfterSecondSykmelding.size shouldBeEqualTo 1

                        val behandlerRelasjonAfterSecondSykmelding = database.getBehandlerArbeidstakerRelasjon(
                            personident = Personident(sykmelding.personNrPasient),
                            behandlerRef = behandler[0].behandlerRef,
                        )
                        behandlerRelasjonAfterSecondSykmelding.size shouldBeEqualTo 1
                        behandlerRelasjonAfterSecondSykmelding[0].updatedAt shouldBeEqualTo behandlerRelasjon[0].updatedAt
                        behandlerRelasjonAfterSecondSykmelding[0].mottatt shouldBeEqualTo behandlerRelasjon[0].mottatt
                    }
                    it("behandler from sykmelding should not shadow existing fastlege") {
                        val url = "$behandlerPath$behandlerPersonident"
                        val validToken = generateJWT(
                            externalMockEnvironment.environment.aadAppClient,
                            externalMockEnvironment.wellKnownInternalAzureAD.issuer,
                            UserConstants.VEILEDER_IDENT,
                        )
                        with(
                            handleRequest(HttpMethod.Get, url) {
                                addHeader(HttpHeaders.Authorization, bearerHeader(validToken))
                                addHeader(NAV_PERSONIDENT_HEADER, UserConstants.ARBEIDSTAKER_FNR.value)
                            }
                        ) {
                            response.status() shouldBeEqualTo HttpStatusCode.OK

                            val behandlerList =
                                objectMapper.readValue<List<BehandlerDTO>>(response.content!!)
                            behandlerList.size shouldBeEqualTo 1
                        }

                        val sykmelding = generateSykmeldingDTO(
                            uuid = UUID.randomUUID(),
                            personNrPasient = UserConstants.ARBEIDSTAKER_FNR.value,
                        )
                        every { mockConsumer.poll(any<Duration>()) } returns
                            consumerRecords(sykmeldingTopicPartition, kafkaPartition, sykmelding)

                        runBlocking {
                            pollAndProcessSykmelding(
                                kafkaConsumerSykmelding = mockConsumer,
                                behandlerService = behandlerService,
                            )
                        }
                        verify(exactly = 1) { mockConsumer.commitSync() }

                        val behandler = database.getBehandlerByArbeidstaker(Personident(sykmelding.personNrPasient))
                        behandler.size shouldBeEqualTo 2
                        val fastlegeBehandlerRef = behandler[1].behandlerRef

                        with(
                            handleRequest(HttpMethod.Get, url) {
                                addHeader(HttpHeaders.Authorization, bearerHeader(validToken))
                                addHeader(NAV_PERSONIDENT_HEADER, UserConstants.ARBEIDSTAKER_FNR.value)
                            }
                        ) {
                            response.status() shouldBeEqualTo HttpStatusCode.OK
                            val behandlerList = objectMapper.readValue<List<BehandlerDTO>>(response.content!!)
                            behandlerList.size shouldBeEqualTo 1
                            behandlerList[0].behandlerRef shouldBeEqualTo fastlegeBehandlerRef.toString()
                        }

                        val behandlerAfterAnotherGet = database.getBehandlerByArbeidstaker(Personident(sykmelding.personNrPasient))
                        behandlerAfterAnotherGet.size shouldBeEqualTo 2
                    }

                    it("should update system for kontor") {
                        val sykmelding = generateSykmeldingDTO(
                            uuid = UUID.randomUUID(),
                        )
                        every { mockConsumer.poll(any<Duration>()) } returns
                            consumerRecords(sykmeldingTopicPartition, kafkaPartition, sykmelding)

                        runBlocking {
                            pollAndProcessSykmelding(
                                kafkaConsumerSykmelding = mockConsumer,
                                behandlerService = behandlerService,
                            )
                        }
                        verify(exactly = 1) { mockConsumer.commitSync() }
                        val kontor = database.connection.getBehandlerKontor(PartnerId(sykmelding.partnerreferanse!!.toInt()))
                        kontor!!.partnerId shouldBeEqualTo sykmelding.partnerreferanse

                        val behandler = database.getBehandlerByArbeidstaker(Personident(sykmelding.personNrPasient))
                        behandler.size shouldBeEqualTo 1

                        val newSykmelding = generateSykmeldingDTO(
                            uuid = UUID.randomUUID(),
                            avsenderSystemNavn = "Nytt systemnavn",
                        )
                        every { mockConsumer.poll(any<Duration>()) } returns
                            consumerRecords(sykmeldingTopicPartition, kafkaPartition, newSykmelding, 2)

                        runBlocking {
                            pollAndProcessSykmelding(
                                kafkaConsumerSykmelding = mockConsumer,
                                behandlerService = behandlerService,
                            )
                        }
                        verify(exactly = 2) { mockConsumer.commitSync() }

                        val kontorAfterSecondMessage = database.connection.getBehandlerKontor(PartnerId(sykmelding.partnerreferanse!!.toInt()))
                        kontorAfterSecondMessage!!.partnerId shouldBeEqualTo sykmelding.partnerreferanse
                        kontorAfterSecondMessage.system shouldBeEqualTo newSykmelding.sykmelding.avsenderSystem.navn
                    }
                    it("should not update system for kontor if mottatt older than the one already stored") {
                        val sykmelding = generateSykmeldingDTO(
                            uuid = UUID.randomUUID(),
                            avsenderSystemNavn = "Systemnavnet"
                        )
                        every { mockConsumer.poll(any<Duration>()) } returns
                            consumerRecords(sykmeldingTopicPartition, kafkaPartition, sykmelding)

                        runBlocking {
                            pollAndProcessSykmelding(
                                kafkaConsumerSykmelding = mockConsumer,
                                behandlerService = behandlerService,
                            )
                        }
                        verify(exactly = 1) { mockConsumer.commitSync() }
                        val kontor = database.connection.getBehandlerKontor(PartnerId(sykmelding.partnerreferanse!!.toInt()))
                        kontor!!.partnerId shouldBeEqualTo sykmelding.partnerreferanse

                        val behandler = database.getBehandlerByArbeidstaker(Personident(sykmelding.personNrPasient))
                        behandler.size shouldBeEqualTo 1

                        val newSykmelding = generateSykmeldingDTO(
                            uuid = UUID.randomUUID(),
                            avsenderSystemNavn = "Systemnavnet tidligere",
                            mottattTidspunkt = LocalDateTime.now().minusDays(1),
                            behandletTidspunkt = LocalDateTime.now().minusDays(1),
                        )
                        every { mockConsumer.poll(any<Duration>()) } returns
                            consumerRecords(sykmeldingTopicPartition, kafkaPartition, newSykmelding, 2)

                        runBlocking {
                            pollAndProcessSykmelding(
                                kafkaConsumerSykmelding = mockConsumer,
                                behandlerService = behandlerService,
                            )
                        }
                        verify(exactly = 2) { mockConsumer.commitSync() }

                        val kontorAfterSecondMessage = database.connection.getBehandlerKontor(PartnerId(sykmelding.partnerreferanse!!.toInt()))
                        kontorAfterSecondMessage!!.partnerId shouldBeEqualTo sykmelding.partnerreferanse
                        kontorAfterSecondMessage.system shouldBeEqualTo sykmelding.sykmelding.avsenderSystem.navn
                    }
                    it("fastlege-oppslag should update adresse for kontor") {
                        val fastlegeBehandler = generateFastlegeResponse().toBehandler(UserConstants.PARTNERID)
                        val sykmelding = generateSykmeldingDTO(
                            uuid = UUID.randomUUID(),
                            personNrPasient = UserConstants.ARBEIDSTAKER_FNR.value,
                            personNrLege = fastlegeBehandler.personident!!.value,
                            behandlerFnr = fastlegeBehandler.personident!!.value,
                            herId = fastlegeBehandler.herId.toString(),
                            hprId = fastlegeBehandler.hprId.toString(),
                            partnerreferanse = UserConstants.PARTNERID.toString(),
                            kontorHerId = UserConstants.HERID.toString(),
                            mottattTidspunkt = LocalDateTime.now().minusMinutes(1),
                            behandletTidspunkt = LocalDateTime.now().minusMinutes(1),
                        )
                        every { mockConsumer.poll(any<Duration>()) } returns
                            consumerRecords(sykmeldingTopicPartition, kafkaPartition, sykmelding)

                        runBlocking {
                            pollAndProcessSykmelding(
                                kafkaConsumerSykmelding = mockConsumer,
                                behandlerService = behandlerService,
                            )
                        }
                        verify(exactly = 1) { mockConsumer.commitSync() }

                        val kontor = database.connection.getBehandlerKontor(PartnerId(sykmelding.partnerreferanse!!.toInt()))
                        kontor!!.partnerId shouldBeEqualTo sykmelding.partnerreferanse
                        kontor.poststed shouldBe null

                        behandlerService.createOrUpdateBehandlerAndRelasjon(
                            fastlegeBehandler,
                            BehandlerArbeidstakerRelasjon(
                                type = BehandlerArbeidstakerRelasjonstype.FASTLEGE,
                                arbeidstakerPersonident = UserConstants.ARBEIDSTAKER_FNR,
                                mottatt = OffsetDateTime.now(),
                            )
                        )

                        val kontorAfterFastlegeOppslag = database.connection.getBehandlerKontor(PartnerId(sykmelding.partnerreferanse!!.toInt()))
                        kontorAfterFastlegeOppslag!!.partnerId shouldBeEqualTo sykmelding.partnerreferanse
                        kontorAfterFastlegeOppslag.adresse shouldBeEqualTo fastlegeBehandler.kontor.adresse
                        kontorAfterFastlegeOppslag.postnummer shouldBeEqualTo fastlegeBehandler.kontor.postnummer
                        kontorAfterFastlegeOppslag.poststed shouldBeEqualTo fastlegeBehandler.kontor.poststed
                    }
                }
                describe("Invalid sykmelding") {
                    it("should ignore when mismatched behandler fnr") {
                        val sykmelding = generateSykmeldingDTO(
                            uuid = UUID.randomUUID(),
                            personNrLege = "01010112345",
                            behandlerFnr = "01010112346",
                        )
                        every { mockConsumer.poll(any<Duration>()) } returns
                            consumerRecords(sykmeldingTopicPartition, kafkaPartition, sykmelding)

                        runBlocking {
                            pollAndProcessSykmelding(
                                kafkaConsumerSykmelding = mockConsumer,
                                behandlerService = behandlerService,
                            )
                        }
                        verify(exactly = 1) { mockConsumer.commitSync() }
                        val kontor =
                            database.connection.getBehandlerKontor(PartnerId(sykmelding.partnerreferanse!!.toInt()))
                        val behandler =
                            database.getBehandlerByArbeidstaker(Personident(sykmelding.personNrPasient))
                        kontor shouldBe null
                        behandler.size shouldBeEqualTo 0
                    }
                    it("should ignore when invalid behandler kategori") {
                        val sykmelding = generateSykmeldingDTO(
                            uuid = UUID.randomUUID(),
                            legeHelsepersonellkategori = "XX",
                        )
                        every { mockConsumer.poll(any<Duration>()) } returns
                            consumerRecords(sykmeldingTopicPartition, kafkaPartition, sykmelding)

                        runBlocking {
                            pollAndProcessSykmelding(
                                kafkaConsumerSykmelding = mockConsumer,
                                behandlerService = behandlerService,
                            )
                        }
                        verify(exactly = 1) { mockConsumer.commitSync() }
                        val kontor =
                            database.connection.getBehandlerKontor(PartnerId(sykmelding.partnerreferanse!!.toInt()))
                        val behandler =
                            database.getBehandlerByArbeidstaker(Personident(sykmelding.personNrPasient))
                        kontor shouldBe null
                        behandler.size shouldBeEqualTo 0
                    }
                    it("should ignore when missing partnerId") {
                        val sykmelding = generateSykmeldingDTO(
                            uuid = UUID.randomUUID(),
                            partnerreferanse = "",
                        )
                        every { mockConsumer.poll(any<Duration>()) } returns
                            consumerRecords(sykmeldingTopicPartition, kafkaPartition, sykmelding)

                        runBlocking {
                            pollAndProcessSykmelding(
                                kafkaConsumerSykmelding = mockConsumer,
                                behandlerService = behandlerService,
                            )
                        }
                        verify(exactly = 1) { mockConsumer.commitSync() }
                        val behandler =
                            database.getBehandlerByArbeidstaker(Personident(sykmelding.personNrPasient))
                        behandler.size shouldBeEqualTo 0
                    }
                    it("should ignore when partnerId is not numeric") {
                        val sykmelding = generateSykmeldingDTO(
                            uuid = UUID.randomUUID(),
                            partnerreferanse = "x",
                        )
                        every { mockConsumer.poll(any<Duration>()) } returns
                            consumerRecords(sykmeldingTopicPartition, kafkaPartition, sykmelding)

                        runBlocking {
                            pollAndProcessSykmelding(
                                kafkaConsumerSykmelding = mockConsumer,
                                behandlerService = behandlerService,
                            )
                        }
                        verify(exactly = 1) { mockConsumer.commitSync() }
                        val behandler =
                            database.getBehandlerByArbeidstaker(Personident(sykmelding.personNrPasient))
                        behandler.size shouldBeEqualTo 0
                    }
                    it("should ignore when mottatt before cutoff") {
                        val sykmelding = generateSykmeldingDTO(
                            uuid = UUID.randomUUID(),
                            mottattTidspunkt = LocalDateTime.of(LocalDate.of(2021, Month.SEPTEMBER, 1), LocalTime.of(0, 0))
                        )
                        every { mockConsumer.poll(any<Duration>()) } returns
                            consumerRecords(sykmeldingTopicPartition, kafkaPartition, sykmelding)

                        runBlocking {
                            pollAndProcessSykmelding(
                                kafkaConsumerSykmelding = mockConsumer,
                                behandlerService = behandlerService,
                            )
                        }
                        verify(exactly = 1) { mockConsumer.commitSync() }
                        val kontor =
                            database.connection.getBehandlerKontor(PartnerId(sykmelding.partnerreferanse!!.toInt()))
                        val behandler =
                            database.getBehandlerByArbeidstaker(Personident(sykmelding.personNrPasient))
                        kontor shouldBe null
                        behandler.size shouldBeEqualTo 0
                    }
                }
            }
        }
    }
})

private fun consumerRecords(
    sykmeldingTopicPartition: TopicPartition,
    kafkaPartition: Int,
    sykmelding: ReceivedSykmeldingDTO,
    offset: Long = 1,
) = ConsumerRecords(
    mapOf(
        sykmeldingTopicPartition to listOf(
            consumerRecord(kafkaPartition, sykmelding, offset),
        )
    )
)

private fun consumerRecord(
    kafkaPartition: Int,
    sykmelding: ReceivedSykmeldingDTO,
    offset: Long = 1,
) = ConsumerRecord(
    SYKMELDING_TOPIC,
    kafkaPartition,
    offset,
    sykmelding.msgId,
    sykmelding,
)
