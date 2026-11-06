package fr.oeildelynx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveUpnpTest {
    @Test fun ssdpLocationIsRead() {
        val response = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLocation: http://192.168.1.254:49152/rootDesc.xml\r\nST: upnp:rootdevice\r\n\r\n"
        assertEquals("http://192.168.1.254:49152/rootDesc.xml", LiveUpnp.parseLocation(response))
    }

    @Test fun wanIpServiceIsResolvedAgainstLocation() {
        val xml = """
            <root><device><serviceList>
              <service><serviceType>urn:schemas-upnp-org:service:Layer3Forwarding:1</serviceType><controlURL>/l3f</controlURL></service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:WANIPConnection:2</serviceType>
                <controlURL>/upnp/control/WANIPConn1</controlURL>
              </service>
            </serviceList></device></root>
        """.trimIndent()
        val gateway = LiveUpnp.parseGateway(xml, "http://192.168.1.1:5000/rootDesc.xml")!!
        assertEquals("http://192.168.1.1:5000/upnp/control/WANIPConn1", gateway.controlUrl)
        assertEquals("urn:schemas-upnp-org:service:WANIPConnection:2", gateway.serviceType)
    }

    @Test fun pppServiceAndUrlBaseAreSupported() {
        val xml = "<root><URLBase>http://10.0.0.1:80/</URLBase><service><serviceType>urn:schemas-upnp-org:service:WANPPPConnection:1</serviceType>" +
            "<controlURL>ctl/ppp</controlURL></service></root>"
        assertEquals("http://10.0.0.1:80/ctl/ppp", LiveUpnp.parseGateway(xml, "http://10.0.0.1:1900/desc.xml")!!.controlUrl)
        assertNull(LiveUpnp.parseGateway("<root></root>", "http://10.0.0.1/"))
    }

    @Test fun soapValuesAndChunkedResponsesAreParsed() {
        val body = "<s:Envelope><s:Body><u:GetExternalIPAddressResponse><NewExternalIPAddress>82.64.1.2</NewExternalIPAddress>" +
            "</u:GetExternalIPAddressResponse></s:Body></s:Envelope>"
        val chunked = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" +
            Integer.toHexString(20) + "\r\n" + body.substring(0, 20) + "\r\n" +
            Integer.toHexString(body.length - 20) + "\r\n" + body.substring(20) + "\r\n0\r\n\r\n"
        val response = LiveUpnp.parseHttpResponse(chunked.toByteArray())
        assertEquals(200, response.status)
        assertEquals("82.64.1.2", LiveUpnp.xmlValue(response.body, "NewExternalIPAddress"))
        val error = "<s:Fault><detail><UPnPError><errorCode>718</errorCode></UPnPError></detail></s:Fault>"
        assertEquals("718", LiveUpnp.xmlValue(error, "errorCode"))
    }

    @Test fun privateAndCarrierGradeAddressesAreDetected() {
        assertTrue(LiveUpnp.isPrivateIpv4("192.168.1.10"))
        assertTrue(LiveUpnp.isPrivateIpv4("100.70.3.4"))
        assertTrue(LiveUpnp.isPrivateIpv4("172.20.0.1"))
        assertFalse(LiveUpnp.isPrivateIpv4("82.64.1.2"))
        assertFalse(LiveUpnp.isPrivateIpv4("maison.example"))
    }
}
