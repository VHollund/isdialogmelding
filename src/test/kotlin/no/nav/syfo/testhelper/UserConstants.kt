package no.nav.syfo.testhelper

import no.nav.syfo.domain.PersonIdentNumber

object UserConstants {
    val ARBEIDSTAKER_FNR = PersonIdentNumber("12345678912")
    val ARBEIDSTAKER_VEILEDER_NO_ACCESS = PersonIdentNumber(ARBEIDSTAKER_FNR.value.replace("2", "1"))
    val ARBEIDSTAKER_UTEN_FASTLEGE_FNR = PersonIdentNumber(ARBEIDSTAKER_FNR.value.replace("2", "4"))
    val ARBEIDSTAKER_FASTLEGE_UTEN_FORELDREENHET_FNR = PersonIdentNumber(ARBEIDSTAKER_FNR.value.replace("2", "3"))
    val ARBEIDSTAKER_FASTLEGE_UTEN_PARTNERINFO_FNR = PersonIdentNumber(ARBEIDSTAKER_FNR.value.replace("2", "6"))

    const val HERID = "404"
    const val HERID_UTEN_PARTNERINFO = "504"
    const val VEILEDER_IDENT = "Z999999"
}