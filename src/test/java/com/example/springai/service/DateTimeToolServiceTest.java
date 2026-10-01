package com.example.springai.service;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DateTimeToolServiceTest {

    private final DateTimeToolService service = new DateTimeToolService();

    @Test
    void returnsTheRequestedTimezone() {
        Map<String, String> result = service.getCurrentDateTime()
                .apply(new DateTimeToolService.Request("Europe/London"));

        assertEquals("Europe/London", result.get("timezone"));
        assertFalse(result.get("iso").isBlank());
        assertFalse(result.get("readable").isBlank());
    }

    @Test
    void blankTimezoneFallsBackToTheServerZone() {
        Map<String, String> result = service.getCurrentDateTime()
                .apply(new DateTimeToolService.Request("  "));

        assertEquals(ZoneId.systemDefault().getId(), result.get("timezone"));
    }

    @Test
    void unknownTimezoneIsReportedNotThrown() {
        Map<String, String> result = service.getCurrentDateTime()
                .apply(new DateTimeToolService.Request("Mars/Olympus_Mons"));

        assertTrue(result.get("error").contains("unknown timezone"));
    }
}
