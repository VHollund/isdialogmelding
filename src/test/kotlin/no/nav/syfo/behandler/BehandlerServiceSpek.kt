package no.nav.syfo.behandler

import io.ktor.server.testing.*
import io.mockk.*
import no.nav.syfo.behandler.database.*
import no.nav.syfo.behandler.database.domain.toBehandler
import no.nav.syfo.behandler.fastlege.FastlegeClient
import no.nav.syfo.behandler.domain.*
import no.nav.syfo.behandler.fastlege.toBehandler
import no.nav.syfo.behandler.partnerinfo.PartnerinfoClient
import no.nav.syfo.testhelper.ExternalMockEnvironment
import no.nav.syfo.testhelper.UserConstants
import no.nav.syfo.testhelper.createBehandlerForArbeidstaker
import no.nav.syfo.testhelper.dropData
import no.nav.syfo.testhelper.generator.generateFastlegeResponse
import org.amshove.kluent.*
import org.junit.Assert.assertThrows
import org.spekframework.spek2.Spek
import org.spekframework.spek2.style.specification.describe
import java.time.OffsetDateTime

class BehandlerServiceSpek : Spek({
    describe("BehandlerService") {
        with(TestApplicationEngine()) {
            start()

            val externalMockEnvironment = ExternalMockEnvironment.instance
            val database = externalMockEnvironment.database
            val fastlegeClientMock = mockk<FastlegeClient>()
            val partnerinfoClientMock = mockk<PartnerinfoClient>()
            val behandlerService = BehandlerService(
                fastlegeClient = fastlegeClientMock,
                partnerinfoClient = partnerinfoClientMock,
                database = database,
                externalMockEnvironment.environment.toggleSykmeldingbehandlere,
            )

            afterEachTest {
                database.dropData()
                clearMocks(
                    fastlegeClientMock,
                    partnerinfoClientMock,
                )
            }

            describe("createOrGetBehandler") {
                it("lagrer behandler for arbeidstaker") {
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
                    pBehandlerList[0].behandlerRef shouldBeEqualTo behandler.behandlerRef
                    val pBehandlerKontor = database.getBehandlerKontorById(pBehandlerList[0].kontorId)
                    pBehandlerKontor.dialogmeldingEnabled shouldNotBeEqualTo null
                }
                it("lagrer behandler for arbeidstaker og setter dialogmeldingEnabled senere") {
                    val behandler =
                        behandlerService.createOrUpdateBehandlerAndRelasjon(
                            generateFastlegeResponse().toBehandler(
                                partnerId = UserConstants.PARTNERID,
                                dialogmeldingEnabled = false,
                            ),
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
                    val pBehandler = pBehandlerList[0]
                    val behandlerFromDB = pBehandler.toBehandler(database.getBehandlerKontorById(pBehandler.kontorId))
                    behandlerFromDB.behandlerRef shouldBeEqualTo behandler.behandlerRef
                    behandlerFromDB.kontor.dialogmeldingEnabled shouldBeEqualTo false

                    database.updateBehandlerKontorDialogmeldingEnabled(behandlerFromDB.kontor.partnerId)

                    val behandlerFromDBUpdated = pBehandler.toBehandler(database.getBehandlerKontorById(pBehandler.kontorId))
                    behandlerFromDBUpdated.behandlerRef shouldBeEqualTo behandler.behandlerRef
                    behandlerFromDBUpdated.kontor.dialogmeldingEnabled shouldBeEqualTo true
                }
                it("lagrer behandler for arbeidstaker og setter system senere") {
                    val behandler =
                        behandlerService.createOrUpdateBehandlerAndRelasjon(
                            generateFastlegeResponse().toBehandler(
                                partnerId = UserConstants.PARTNERID,
                                dialogmeldingEnabled = false,
                            ),
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
                    val pBehandler = pBehandlerList[0]
                    val behandlerFromDB = pBehandler.toBehandler(database.getBehandlerKontorById(pBehandler.kontorId))
                    behandlerFromDB.behandlerRef shouldBeEqualTo behandler.behandlerRef
                    behandlerFromDB.kontor.system shouldBe null

                    database.connection.use {
                        it.updateBehandlerKontorSystem(
                            partnerId = behandlerFromDB.kontor.partnerId,
                            kontor = BehandlerKontor(
                                partnerId = behandlerFromDB.kontor.partnerId,
                                herId = behandlerFromDB.kontor.herId,
                                navn = behandlerFromDB.kontor.navn,
                                adresse = behandlerFromDB.kontor.adresse,
                                postnummer = behandlerFromDB.kontor.postnummer,
                                poststed = behandlerFromDB.kontor.poststed,
                                orgnummer = behandlerFromDB.kontor.orgnummer,
                                dialogmeldingEnabled = behandlerFromDB.kontor.dialogmeldingEnabled,
                                system = "EPJ-systemet",
                                mottatt = OffsetDateTime.now(),
                            ),
                        )
                        it.commit()
                    }

                    val behandlerFromDBUpdated = pBehandler.toBehandler(database.getBehandlerKontorById(pBehandler.kontorId))
                    behandlerFromDBUpdated.behandlerRef shouldBeEqualTo behandler.behandlerRef
                    behandlerFromDBUpdated.kontor.system shouldBeEqualTo "EPJ-systemet"
                }
                it("lagrer behandler for arbeidstaker én gang når kalt flere ganger for samme behandler og arbeidstaker") {
                    val behandler = generateFastlegeResponse().toBehandler(UserConstants.PARTNERID)
                    behandlerService.createOrUpdateBehandlerAndRelasjon(
                        behandler,
                        BehandlerArbeidstakerRelasjon(
                            type = BehandlerArbeidstakerRelasjonstype.FASTLEGE,
                            arbeidstakerPersonident = UserConstants.ARBEIDSTAKER_FNR,
                            mottatt = OffsetDateTime.now(),
                        )
                    )
                    behandlerService.createOrUpdateBehandlerAndRelasjon(
                        behandler,
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
                }
                it("lagrer én behandler koblet til begge arbeidstakere når kalt for to ulike arbeidstakere med samme behandler") {
                    val behandler = generateFastlegeResponse().toBehandler(UserConstants.PARTNERID)
                    behandlerService.createOrUpdateBehandlerAndRelasjon(
                        behandler,
                        BehandlerArbeidstakerRelasjon(
                            type = BehandlerArbeidstakerRelasjonstype.FASTLEGE,
                            arbeidstakerPersonident = UserConstants.ANNEN_ARBEIDSTAKER_FNR,
                            mottatt = OffsetDateTime.now(),
                        ),
                    )
                    behandlerService.createOrUpdateBehandlerAndRelasjon(
                        behandler,
                        BehandlerArbeidstakerRelasjon(
                            type = BehandlerArbeidstakerRelasjonstype.FASTLEGE,
                            arbeidstakerPersonident = UserConstants.ARBEIDSTAKER_FNR,
                            mottatt = OffsetDateTime.now(),
                        ),
                    )

                    val pBehandlerForArbeidstakerList = database.getBehandlerByArbeidstaker(
                        UserConstants.ARBEIDSTAKER_FNR,
                    )
                    val pBehandlerForAnnenArbeidstakerList = database.getBehandlerByArbeidstaker(
                        UserConstants.ANNEN_ARBEIDSTAKER_FNR,
                    )
                    pBehandlerForArbeidstakerList.size shouldBeEqualTo 1
                    pBehandlerForAnnenArbeidstakerList.size shouldBeEqualTo 1
                    pBehandlerForArbeidstakerList[0].behandlerRef shouldBeEqualTo pBehandlerForAnnenArbeidstakerList[0].behandlerRef
                }
                it("lagrer behandler uten fnr") {
                    val behandler =
                        generateFastlegeResponse(null, UserConstants.HERID, UserConstants.HPRID).toBehandler(UserConstants.PARTNERID)
                    behandlerService.createOrUpdateBehandlerAndRelasjon(
                        behandler,
                        BehandlerArbeidstakerRelasjon(
                            type = BehandlerArbeidstakerRelasjonstype.FASTLEGE,
                            arbeidstakerPersonident = UserConstants.ARBEIDSTAKER_FNR,
                            mottatt = OffsetDateTime.now(),
                        ),
                    )

                    database.getBehandlerByArbeidstaker(
                        UserConstants.ARBEIDSTAKER_FNR,
                    ).size shouldBeEqualTo 1
                }
                it("lagrer behandler uten herId") {
                    val behandler =
                        generateFastlegeResponse(
                            UserConstants.FASTLEGE_FNR,
                            null,
                            UserConstants.HPRID
                        ).toBehandler(UserConstants.PARTNERID)
                    behandlerService.createOrUpdateBehandlerAndRelasjon(
                        behandler,
                        BehandlerArbeidstakerRelasjon(
                            type = BehandlerArbeidstakerRelasjonstype.FASTLEGE,
                            arbeidstakerPersonident = UserConstants.ARBEIDSTAKER_FNR,
                            mottatt = OffsetDateTime.now(),
                        ),
                    )

                    database.getBehandlerByArbeidstaker(
                        UserConstants.ARBEIDSTAKER_FNR,
                    ).size shouldBeEqualTo 1
                }
                it("lagrer behandler uten hprId") {
                    val behandler =
                        generateFastlegeResponse(
                            UserConstants.FASTLEGE_FNR,
                            UserConstants.HERID,
                            null
                        ).toBehandler(UserConstants.PARTNERID)
                    behandlerService.createOrUpdateBehandlerAndRelasjon(
                        behandler,
                        BehandlerArbeidstakerRelasjon(
                            type = BehandlerArbeidstakerRelasjonstype.FASTLEGE,
                            arbeidstakerPersonident = UserConstants.ARBEIDSTAKER_FNR,
                            mottatt = OffsetDateTime.now(),
                        ),
                    )

                    database.getBehandlerByArbeidstaker(
                        UserConstants.ARBEIDSTAKER_FNR,
                    ).size shouldBeEqualTo 1
                }
                it("lagrer ikke behandler uten fnr, herId og hprId") {
                    val behandler =
                        generateFastlegeResponse(null, null, null).toBehandler(UserConstants.PARTNERID)

                    assertThrows(IllegalArgumentException::class.java) {
                        behandlerService.createOrUpdateBehandlerAndRelasjon(
                            behandler,
                            BehandlerArbeidstakerRelasjon(
                                type = BehandlerArbeidstakerRelasjonstype.FASTLEGE,
                                arbeidstakerPersonident = UserConstants.ARBEIDSTAKER_FNR,
                                mottatt = OffsetDateTime.now(),
                            ),
                        )
                    }
                    database.getBehandlerByArbeidstaker(
                        UserConstants.ARBEIDSTAKER_FNR,
                    ).size shouldBeEqualTo 0
                }
                it("lagrer ikke behandler for arbeidstaker når samme behandler er siste lagrede behandler for arbeidstaker") {
                    val behandler = generateFastlegeResponse().toBehandler(UserConstants.PARTNERID)
                    val existingBehandlerRef =
                        database.createBehandlerForArbeidstaker(
                            behandler = behandler,
                            arbeidstakerPersonident = UserConstants.ARBEIDSTAKER_FNR
                        )

                    behandlerService.createOrUpdateBehandlerAndRelasjon(
                        behandler,
                        BehandlerArbeidstakerRelasjon(
                            type = BehandlerArbeidstakerRelasjonstype.FASTLEGE,
                            arbeidstakerPersonident = UserConstants.ARBEIDSTAKER_FNR,
                            mottatt = OffsetDateTime.now(),
                        ),
                    )

                    val pBehandlerForArbeidstakerList = database.getBehandlerByArbeidstaker(
                        UserConstants.ARBEIDSTAKER_FNR,
                    )
                    pBehandlerForArbeidstakerList.size shouldBeEqualTo 1
                    pBehandlerForArbeidstakerList[0].behandlerRef shouldBeEqualTo existingBehandlerRef
                }
                it("lagrer behandler for arbeidstaker når samme behandler er lagret for annen arbeidstaker") {
                    val behandler = generateFastlegeResponse().toBehandler(UserConstants.PARTNERID)
                    val existingBehandlerRef =
                        database.createBehandlerForArbeidstaker(
                            behandler = behandler,
                            arbeidstakerPersonident = UserConstants.ANNEN_ARBEIDSTAKER_FNR
                        )
                    behandlerService.createOrUpdateBehandlerAndRelasjon(
                        behandler,
                        BehandlerArbeidstakerRelasjon(
                            type = BehandlerArbeidstakerRelasjonstype.FASTLEGE,
                            arbeidstakerPersonident = UserConstants.ARBEIDSTAKER_FNR,
                            mottatt = OffsetDateTime.now(),
                        ),
                    )

                    val pBehandlerForArbeidstakerList = database.getBehandlerByArbeidstaker(
                        UserConstants.ARBEIDSTAKER_FNR,
                    )
                    pBehandlerForArbeidstakerList.size shouldBeEqualTo 1
                    pBehandlerForArbeidstakerList[0].behandlerRef shouldBeEqualTo existingBehandlerRef
                }
                it("lagrer behandler for arbeidstaker når fastlege er annen enn siste lagrede fastlege for arbeidstaker") {
                    val behandler = generateFastlegeResponse(UserConstants.FASTLEGE_FNR).toBehandler(UserConstants.PARTNERID)
                    val annenBehandler =
                        generateFastlegeResponse(UserConstants.FASTLEGE_ANNEN_FNR).toBehandler(UserConstants.PARTNERID)
                    val existingBehandlerRef =
                        database.createBehandlerForArbeidstaker(
                            behandler = behandler,
                            arbeidstakerPersonident = UserConstants.ARBEIDSTAKER_FNR
                        )
                    behandlerService.createOrUpdateBehandlerAndRelasjon(
                        behandler = annenBehandler,
                        BehandlerArbeidstakerRelasjon(
                            type = BehandlerArbeidstakerRelasjonstype.FASTLEGE,
                            arbeidstakerPersonident = UserConstants.ARBEIDSTAKER_FNR,
                            mottatt = OffsetDateTime.now(),
                        ),
                    )

                    val pBehandlerForArbeidstakerList = database.getBehandlerByArbeidstaker(
                        UserConstants.ARBEIDSTAKER_FNR,
                    )
                    pBehandlerForArbeidstakerList.size shouldBeEqualTo 2
                    pBehandlerForArbeidstakerList[0].behandlerRef shouldNotBeEqualTo existingBehandlerRef
                    pBehandlerForArbeidstakerList[1].behandlerRef shouldBeEqualTo existingBehandlerRef
                }
                it("lagrer behandler for arbeidstaker når fastlege er lagret for arbeidstaker men annen behandler er siste lagrede fastlege for arbeidstaker") {
                    val behandler = generateFastlegeResponse(UserConstants.FASTLEGE_FNR).toBehandler(UserConstants.PARTNERID)
                    val annenBehandler =
                        generateFastlegeResponse(UserConstants.FASTLEGE_ANNEN_FNR).toBehandler(UserConstants.PARTNERID)
                    database.createBehandlerForArbeidstaker(
                        behandler = behandler,
                        arbeidstakerPersonident = UserConstants.ARBEIDSTAKER_FNR
                    )
                    database.createBehandlerForArbeidstaker(
                        behandler = annenBehandler,
                        arbeidstakerPersonident = UserConstants.ARBEIDSTAKER_FNR
                    )
                    behandlerService.createOrUpdateBehandlerAndRelasjon(
                        behandler,
                        BehandlerArbeidstakerRelasjon(
                            type = BehandlerArbeidstakerRelasjonstype.FASTLEGE,
                            arbeidstakerPersonident = UserConstants.ARBEIDSTAKER_FNR,
                            mottatt = OffsetDateTime.now(),
                        ),
                    )

                    val pBehandlerForArbeidstakerList = database.getBehandlerByArbeidstaker(
                        UserConstants.ARBEIDSTAKER_FNR,
                    )
                    pBehandlerForArbeidstakerList.size shouldBeEqualTo 3
                    pBehandlerForArbeidstakerList[0].behandlerRef shouldBeEqualTo behandler.behandlerRef
                    pBehandlerForArbeidstakerList[1].behandlerRef shouldBeEqualTo annenBehandler.behandlerRef
                    pBehandlerForArbeidstakerList[2].behandlerRef shouldBeEqualTo behandler.behandlerRef
                }
                it("lagrer behandler for arbeidstaker når behandler har samme partnerId som annen behandler for annen arbeidstaker") {
                    val behandler = generateFastlegeResponse(UserConstants.FASTLEGE_FNR).toBehandler(UserConstants.PARTNERID)
                    val annenBehandler =
                        generateFastlegeResponse(UserConstants.FASTLEGE_ANNEN_FNR).toBehandler(UserConstants.PARTNERID)
                    database.createBehandlerForArbeidstaker(
                        behandler = annenBehandler,
                        arbeidstakerPersonident = UserConstants.ANNEN_ARBEIDSTAKER_FNR
                    )
                    behandlerService.createOrUpdateBehandlerAndRelasjon(
                        behandler,
                        BehandlerArbeidstakerRelasjon(
                            type = BehandlerArbeidstakerRelasjonstype.FASTLEGE,
                            arbeidstakerPersonident = UserConstants.ARBEIDSTAKER_FNR,
                            mottatt = OffsetDateTime.now(),
                        ),
                    )

                    val pBehandlerForArbeidstakerList = database.getBehandlerByArbeidstaker(
                        UserConstants.ARBEIDSTAKER_FNR,
                    )
                    pBehandlerForArbeidstakerList.size shouldBeEqualTo 1
                    pBehandlerForArbeidstakerList[0].behandlerRef shouldNotBeEqualTo annenBehandler.behandlerRef
                }
                it("lagrer behandler for arbeidstaker når samme behandler er lagret for arbeidstaker, men med annen partnerId") {
                    val behandler = generateFastlegeResponse(UserConstants.FASTLEGE_FNR).toBehandler(UserConstants.PARTNERID)
                    val sammeBehandlerAnnenPartnerId =
                        generateFastlegeResponse(UserConstants.FASTLEGE_FNR).toBehandler(UserConstants.OTHER_PARTNERID)
                    val existingBehandlerRef =
                        database.createBehandlerForArbeidstaker(
                            behandler = behandler,
                            arbeidstakerPersonident = UserConstants.ARBEIDSTAKER_FNR
                        )

                    behandlerService.createOrUpdateBehandlerAndRelasjon(
                        behandler = sammeBehandlerAnnenPartnerId,
                        BehandlerArbeidstakerRelasjon(
                            type = BehandlerArbeidstakerRelasjonstype.FASTLEGE,
                            arbeidstakerPersonident = UserConstants.ARBEIDSTAKER_FNR,
                            mottatt = OffsetDateTime.now(),
                        ),
                    )

                    val pBehandlerForArbeidstakerList = database.getBehandlerByArbeidstaker(
                        UserConstants.ARBEIDSTAKER_FNR,
                    )
                    pBehandlerForArbeidstakerList.size shouldBeEqualTo 2
                    pBehandlerForArbeidstakerList[0].behandlerRef shouldNotBeEqualTo existingBehandlerRef
                    pBehandlerForArbeidstakerList[1].behandlerRef shouldBeEqualTo existingBehandlerRef
                }
            }
        }
    }
})
