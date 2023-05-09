package no.nav.syfo.dialogmelding.apprec

import io.ktor.server.testing.*
import io.mockk.*
import kotlinx.coroutines.runBlocking
import no.nav.syfo.dialogmelding.bestilling.DialogmeldingToBehandlerService
import no.nav.syfo.behandler.database.*
import no.nav.syfo.behandler.domain.Behandler
import no.nav.syfo.dialogmelding.bestilling.kafka.toDialogmeldingToBehandlerBestilling
import no.nav.syfo.dialogmelding.apprec.consumer.ApprecConsumer
import no.nav.syfo.dialogmelding.apprec.database.getApprec
import no.nav.syfo.dialogmelding.bestilling.database.createBehandlerDialogmeldingBestilling
import no.nav.syfo.domain.PartnerId
import no.nav.syfo.testhelper.*
import no.nav.syfo.testhelper.generator.generateBehandler
import no.nav.syfo.testhelper.generator.generateDialogmeldingToBehandlerBestillingDTO
import org.amshove.kluent.*
import org.spekframework.spek2.Spek
import org.spekframework.spek2.style.specification.describe
import java.util.Random
import java.util.UUID
import javax.jms.*

val externalMockEnvironment = ExternalMockEnvironment.instance
val database = externalMockEnvironment.database

class ApprecConsumerSpek : Spek({
    describe(ApprecConsumerSpek::class.java.simpleName) {
        with(TestApplicationEngine()) {
            start()
            val incomingMessage = mockk<TextMessage>(relaxed = true)

            val apprecService = ApprecService(database)
            val dialogmeldingToBehandlerService =
                DialogmeldingToBehandlerService(database = database, pdlClient = mockk())
            val apprecConsumer = ApprecConsumer(
                applicationState = externalMockEnvironment.applicationState,
                database = database,
                inputconsumer = mockk(),
                apprecService = apprecService,
                dialogmeldingToBehandlerService = dialogmeldingToBehandlerService
            )

            describe("Prosesserer innkommet melding") {

                beforeEachTest {
                    database.dropData()
                    clearAllMocks()
                }
                it("Prosesserer innkommet melding (melding ok)") {
                    val apprecId = UUID.randomUUID()
                    val dialogmeldingBestillingUuid = UUID.randomUUID()
                    val behandler = lagBehandler()
                    val bestillingId = lagDialogmeldingBestilling(dialogmeldingBestillingUuid, behandler)
                    val apprecXml =
                        getFileAsString("src/test/resources/apprecOK.xml")
                            .replace("FiktivTestdata0001", apprecId.toString())
                            .replace("b62016eb-6c2d-417a-8ecc-157b3c5ee2ca", dialogmeldingBestillingUuid.toString())
                    every { incomingMessage.text } returns (apprecXml)
                    runBlocking {
                        apprecConsumer.processApprecMessage(incomingMessage)
                    }

                    val pApprec = database.getApprec(apprecId)

                    pApprec shouldNotBe null
                    pApprec!!.uuid shouldBeEqualTo apprecId
                    pApprec.bestillingId shouldBeEqualTo bestillingId
                    pApprec.statusKode shouldBeEqualTo "1"
                    pApprec.statusTekst shouldBeEqualTo "OK"
                    pApprec.feilKode shouldBe null
                    pApprec.feilTekst shouldBe null
                }
                it("Prosesserer innkommet melding (melding ok, med duplikat)") {
                    val apprecId = UUID.randomUUID()
                    val dialogmeldingBestillingUuid = UUID.randomUUID()
                    lagDialogmeldingBestillingOgBehandler(dialogmeldingBestillingUuid)
                    val apprecXml =
                        getFileAsString("src/test/resources/apprecOK.xml")
                            .replace("FiktivTestdata0001", apprecId.toString())
                            .replace("b62016eb-6c2d-417a-8ecc-157b3c5ee2ca", dialogmeldingBestillingUuid.toString())
                    every { incomingMessage.text } returns (apprecXml)
                    runBlocking {
                        apprecConsumer.processApprecMessage(incomingMessage)
                    }

                    val pApprec = database.getApprec(apprecId)

                    pApprec shouldNotBe null
                    pApprec!!.uuid shouldBeEqualTo apprecId

                    runBlocking {
                        apprecConsumer.processApprecMessage(incomingMessage)
                    }
                }
                it("Prosesserer innkommet melding (melding ok, men ikke knyttet til kjent dialogmelding)") {
                    val apprecId = UUID.randomUUID()
                    val dialogmeldingBestillingUuid = UUID.randomUUID()
                    lagDialogmeldingBestillingOgBehandler(dialogmeldingBestillingUuid)
                    val ukjentDialogmeldingBestillingUuid = UUID.randomUUID()
                    val apprecXml =
                        getFileAsString("src/test/resources/apprecOK.xml")
                            .replace("FiktivTestdata0001", apprecId.toString())
                            .replace(
                                "b62016eb-6c2d-417a-8ecc-157b3c5ee2ca",
                                ukjentDialogmeldingBestillingUuid.toString()
                            )
                    every { incomingMessage.text } returns (apprecXml)
                    runBlocking {
                        apprecConsumer.processApprecMessage(incomingMessage)
                    }

                    val pApprec = database.getApprec(apprecId)

                    pApprec shouldBe null
                }
                it("Prosesserer innkommet melding (melding avvist)") {
                    val apprecId = UUID.randomUUID()
                    val dialogmeldingBestillingUuid = UUID.randomUUID()
                    val behandler = lagBehandler()
                    val bestillingId = lagDialogmeldingBestilling(dialogmeldingBestillingUuid, behandler)
                    val apprecXml =
                        getFileAsString("src/test/resources/apprecError.xml")
                            .replace("FiktivTestdata0001", apprecId.toString())
                            .replace("b62016eb-6c2d-417a-8ecc-157b3c5ee2ca", dialogmeldingBestillingUuid.toString())
                    every { incomingMessage.text } returns (apprecXml)
                    runBlocking {
                        apprecConsumer.processApprecMessage(incomingMessage)
                    }

                    val pApprec = database.getApprec(apprecId)

                    pApprec shouldNotBe null
                    pApprec!!.uuid shouldBeEqualTo apprecId
                    pApprec.bestillingId shouldBeEqualTo bestillingId
                    pApprec.statusKode shouldBeEqualTo "2"
                    pApprec.statusTekst shouldBeEqualTo "Avvist"
                    pApprec.feilKode shouldBeEqualTo "X99"
                    pApprec.feilTekst shouldBeEqualTo "Annen feil"

                    val oppdatertBehandler = database.getBehandlerByBehandlerRef(behandler.behandlerRef)!!
                    oppdatertBehandler.invalidated shouldBe null
                }
                it("Prosesserer innkommet melding (melding avvist, ukjent mottaker)") {
                    val apprecId = UUID.randomUUID()
                    val dialogmeldingBestillingUuid = UUID.randomUUID()
                    val behandler = lagBehandler()
                    val bestillingId = lagDialogmeldingBestilling(dialogmeldingBestillingUuid, behandler)
                    val apprecXml =
                        getFileAsString("src/test/resources/apprecErrorUkjentMottaker.xml")
                            .replace("FiktivTestdata0001", apprecId.toString())
                            .replace("b62016eb-6c2d-417a-8ecc-157b3c5ee2ca", dialogmeldingBestillingUuid.toString())
                    every { incomingMessage.text } returns (apprecXml)
                    runBlocking {
                        apprecConsumer.processApprecMessage(incomingMessage)
                    }

                    val pApprec = database.getApprec(apprecId)

                    pApprec shouldNotBe null
                    pApprec!!.uuid shouldBeEqualTo apprecId
                    pApprec.bestillingId shouldBeEqualTo bestillingId
                    pApprec.statusKode shouldBeEqualTo "2"
                    pApprec.statusTekst shouldBeEqualTo "Avvist"
                    pApprec.feilKode shouldBeEqualTo "E21"
                    pApprec.feilTekst shouldBeEqualTo "Mottaker finnes ikke"

                    val oppdatertBehandler = database.getBehandlerByBehandlerRef(behandler.behandlerRef)!!
                    oppdatertBehandler.invalidated shouldNotBe null
                }
                it("Prosesserer innkommet feilformattert melding") {
                    val apprecXml = "Ikke noen apprec"
                    every { incomingMessage.text } returns (apprecXml)
                    runBlocking {
                        apprecConsumer.processApprecMessage(incomingMessage)
                    }
                    // should not get an exception
                }
            }
        }
    }
})

fun lagDialogmeldingBestillingOgBehandler(dialogmeldingBestillingUuid: UUID): Int {
    val behandler = lagBehandler()
    return lagDialogmeldingBestilling(dialogmeldingBestillingUuid, behandler)
}

fun lagBehandler(): Behandler {
    val random = Random()
    val behandlerRef = UUID.randomUUID()
    val partnerId = PartnerId(random.nextInt())
    return generateBehandler(behandlerRef, partnerId).also { behandler ->
        database.connection.use { connection ->
            val kontorId = connection.createBehandlerKontor(behandler.kontor)
            connection.createBehandler(behandler, kontorId).id.also {
                connection.commit()
            }
        }
    }
}

fun lagDialogmeldingBestilling(dialogmeldingBestillingUuid: UUID, behandler: Behandler): Int {
    val dialogmeldingToBehandlerBestillingDTO = generateDialogmeldingToBehandlerBestillingDTO(
        uuid = dialogmeldingBestillingUuid,
        behandlerRef = behandler.behandlerRef,
    )
    val dialogmeldingToBehandlerBestilling =
        dialogmeldingToBehandlerBestillingDTO.toDialogmeldingToBehandlerBestilling(behandler)
    val behandlerId = database.getBehandlerByBehandlerRef(behandler.behandlerRef)!!.id
    val bestillingId = database.connection.use { connection ->
        connection.createBehandlerDialogmeldingBestilling(
            dialogmeldingToBehandlerBestilling = dialogmeldingToBehandlerBestilling,
            behandlerId = behandlerId,
        ).also {
            connection.commit()
        }
    }
    return bestillingId
}
