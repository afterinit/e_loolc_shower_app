package com.loolc.shower_app;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CodeRequestTest {
    @Test public void openingResumingAndRefreshingUseOnlyTheBathSession() {
        assertFalse(MainActivity.CodeRequest.RESTORE.allowsCampusLogin());
        assertFalse(MainActivity.CodeRequest.READ.allowsCampusLogin());
        assertFalse(MainActivity.CodeRequest.REFRESH.allowsCampusLogin());
    }

    @Test public void onlyExplicitSignInAndReauthorizationCanLogInToCampus() {
        assertTrue(MainActivity.CodeRequest.SIGN_IN.allowsCampusLogin());
        assertTrue(MainActivity.CodeRequest.REAUTHORIZE.allowsCampusLogin());
    }
}
