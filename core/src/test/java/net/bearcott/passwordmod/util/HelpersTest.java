package net.bearcott.passwordmod.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HelpersTest {

    @Test
    void parsesCityAndCountryFromIpwhoisShape() {
        String json = "{\"ip\":\"1.2.3.4\",\"success\":true,\"city\":\"San Jose\",\"country\":\"United States\"}";
        Helpers.Location loc = Helpers.parseLocationJson(json);
        assertEquals("San Jose", loc.city());
        assertEquals("United States", loc.country());
    }

    @Test
    void successFalseYieldsUnknown() {
        String json = "{\"success\":false,\"message\":\"Invalid IP address\"}";
        assertEquals(Helpers.Location.unknown(), Helpers.parseLocationJson(json));
    }

    @Test
    void malformedYieldsUnknown() {
        assertEquals(Helpers.Location.unknown(), Helpers.parseLocationJson("not json"));
    }

    @Test
    void emptyYieldsUnknown() {
        assertEquals(Helpers.Location.unknown(), Helpers.parseLocationJson(""));
        assertEquals(Helpers.Location.unknown(), Helpers.parseLocationJson("{}"));
    }

    @Test
    void stripToHostAddressHandlesIpv4AndIpv6() {
        // IPv4, with/without port, with Java's host/ip prefix
        assertEquals("1.2.3.4", Helpers.stripToHostAddress("1.2.3.4"));
        assertEquals("1.2.3.4", Helpers.stripToHostAddress("1.2.3.4:25565"));
        assertEquals("1.2.3.4", Helpers.stripToHostAddress("/1.2.3.4"));
        assertEquals("1.2.3.4", Helpers.stripToHostAddress("host.example/1.2.3.4:25565"));
        // IPv6 must NOT be truncated at the first colon (the old split(":")[0] bug)
        assertEquals("2001:db8::1", Helpers.stripToHostAddress("2001:db8::1"));
        assertEquals("2001:db8::1", Helpers.stripToHostAddress("[2001:db8::1]:25565"));
    }
}
