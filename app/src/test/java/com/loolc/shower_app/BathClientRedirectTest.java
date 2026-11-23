package com.loolc.shower_app;

import org.junit.Test;

import java.net.URI;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class BathClientRedirectTest {
    private static final URI CAS_SSO = URI.create("https://cas.hzxwwl.com/auth/cas/sso");

    @Test public void redirectQuotesUnescapedCharactersAndPreservesEncodedParameters() throws Exception {
        URI origin = URI.create("http://mcp.hbmzu.edu.cn:8090/mobileapi_ydxy/api/lapp/springboard");

        URI target = BathClient.redirectUri(origin,
                "/thirdpart_ydxy/cas/index?note={浴室 A}&lightUrl=https%3A%2F%2Fcas.hzxwwl.com%2Fauth%2Fcas%2Fsso",
                "打开浴室服务");

        assertEquals("mcp.hbmzu.edu.cn", target.getHost());
        assertEquals(8090, target.getPort());
        assertEquals("note=%7B%E6%B5%B4%E5%AE%A4%20A%7D&lightUrl=https%3A%2F%2Fcas.hzxwwl.com%2Fauth%2Fcas%2Fsso",
                target.getRawQuery());
    }

    @Test public void bathPathReadsDirectAndFragmentLoginQueries() throws Exception {
        assertEquals("one", BathClient.bathPath(CAS_SSO, "https://wx.hzxwwl.com/login?path=one"));
        assertEquals("two+three", BathClient.bathPath(CAS_SSO,
                "https://wx.hzxwwl.com/prod/bath/#/login?path=two%2Bthree"));
        assertEquals("four", BathClient.bathPath(CAS_SSO,
                "https://wx.hzxwwl.com/prod/bath/#/bath/login?path=four"));
    }

    @Test public void bathPathAcceptsRawRouteAndIgnoresLaterAnchor() throws Exception {
        assertEquals("dynamic+path", BathClient.bathPath(CAS_SSO, "//wx.hzxwwl.com/[浴室]/prod/"
                + "?path=outer#/bath/login?name=校园 浴室&path=dynamic%2Bpath#detail"));
    }

    @Test public void bathPathRejectsOtherOrigins() {
        assertThrows(BathCode.Failure.class, () -> BathClient.bathPath(CAS_SSO,
                "https://wx.hzxwwl.com.example.org/prod/#/login?path=foreign"));
        assertThrows(BathCode.Failure.class, () -> BathClient.bathPath(CAS_SSO,
                "https://wx.hzxwwl.com:8090/prod/#/login?path=wrong-port"));
    }
}
