package com.loolc.shower_app;

import org.junit.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class BathCodeTest {
    private static final ZoneId CAMPUS = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.ROOT);

    @Test public void openingWithValidCodePreservesLeadingZerosAndDoesNotToggle() throws Exception {
        FakeGateway gateway = new FakeGateway(true, true, campusTime(-2));

        assertEquals("00123456", BathCode.obtain(gateway, false).value);
        assertEquals(0, gateway.toggles);
    }

    @Test public void openingWithExpiredCodeObtainsNewCode() throws Exception {
        FakeGateway gateway = new FakeGateway(true, true, campusTime(-90));

        assertEquals("00987654", BathCode.obtain(gateway, false).value);
        assertEquals(2, gateway.toggles);
    }

    @Test public void openingWithDisabledAccessTogglesOnce() throws Exception {
        FakeGateway gateway = new FakeGateway(false, true, campusTime(-2));

        assertEquals("00987654", BathCode.obtain(gateway, false).value);
        assertEquals(1, gateway.toggles);
    }

    @Test public void manualRefreshDoesNotPresentUnchangedCode() {
        FakeGateway gateway = new FakeGateway(true, false, campusTime(-2));

        BathCode.Failure failure = assertThrows(BathCode.Failure.class, () -> BathCode.obtain(gateway, true));

        assertTrue(failure.getMessage().contains("新码"));
        assertEquals(2, gateway.toggles);
    }

    @Test public void expiryUsesCampusTimeAndHandlesMidnight() {
        BathCode code = new BathCode("00123456", "2026-11-22 23:45:00", true, "5");

        assertEquals(Instant.parse("2026-11-22T16:45:00Z"), code.expiresAt);
    }

    @Test public void masksFirstFiveDigitsAndKeepsTheRest() {
        assertEquals("*****456", new BathCode("00123456", "", true, "").maskedValue());
        assertEquals("*** **6", new BathCode("001 236", "", true, "").maskedValue());
        assertEquals("***", new BathCode("012", "", true, "").maskedValue());
    }

    @Test public void countdownRetainsLastSecondUntilExpiry() {
        assertEquals("01:00:00", BathCode.formatCountdown(3_600_000));
        assertEquals("00:01:01", BathCode.formatCountdown(60_001));
        assertEquals("00:01:00", BathCode.formatCountdown(60_000));
        assertEquals("00:00:01", BathCode.formatCountdown(1));
        assertEquals("00:00:00", BathCode.formatCountdown(0));
    }

    private static String campusTime(int minutesFromNow) {
        return FORMAT.format(LocalDateTime.now(CAMPUS).plusMinutes(minutesFromNow));
    }

    private static final class FakeGateway implements BathCode.Gateway {
        final boolean rotate;
        String value = "00123456";
        String generatedAt;
        boolean enabled;
        int toggles;

        FakeGateway(boolean enabled, boolean rotate, String generatedAt) {
            this.enabled = enabled;
            this.rotate = rotate;
            this.generatedAt = generatedAt;
        }

        @Override public BathCode read() {
            return new BathCode(value, generatedAt, enabled, "5");
        }

        @Override public void toggle(String minimumBalance) {
            enabled = !enabled;
            toggles++;
            if (enabled && rotate) {
                value = "00987654";
                generatedAt = campusTime(0);
            }
        }
    }
}
