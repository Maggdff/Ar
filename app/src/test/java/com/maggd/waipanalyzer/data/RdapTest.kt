package com.maggd.waipanalyzer.data

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RdapTest {
    @Test fun parsesRipeStyleResponse() {
        val json = """{"country":"DE","name":"EXAMPLE-NET","startAddress":"85.1.0.0","endAddress":"85.1.255.255",
            "entities":[{"roles":["registrant"],"vcardArray":["vcard",[["version",{},"text","4.0"],["fn",{},"text","Example Telecom GmbH"]]]}]}"""
        val g = RdapClient.parse(json)!!
        assertEquals("DE", g.countryCode)
        assertEquals("Example Telecom GmbH", g.org)
        assertEquals("EXAMPLE-NET", g.network)
        assertEquals("85.1.0.0 - 85.1.255.255", g.cidrOrRange)
        assertEquals("Germany (DE)", g.countryName(Locale.ENGLISH))
    }

    @Test fun fallsBackToAddressLabelForCountry() {
        val json = """{"name":"NET-1","entities":[{"roles":["registrant"],"vcardArray":["vcard",[["fn",{},"text","Meta Platforms, Inc."],
            ["adr",{"label":"1 Hacker Way\nMenlo Park\nCA\n94025\nUS"},"text",["","","","","","",""]]]]}]}"""
        val g = RdapClient.parse(json)!!
        assertEquals("US", g.countryCode)
        assertTrue(g.org!!.contains("Meta"))
    }

    @Test fun badInputIsHandled() {
        assertNull(RdapClient.parse("not json"))
        assertNull(RdapClient.parse("{}"))
        assertNotNull(RdapClient.parse("""{"name":"X"}"""))
    }

    @Test fun neverQueriesNonPublicOrInvalidAddresses() {
        assertNull(RdapClient.lookup("192.168.1.1"))
        assertNull(RdapClient.lookup("10.0.0.1"))
        assertNull(RdapClient.lookup("../../etc"))
    }
}
