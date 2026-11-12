package net.bearcott.passwordmod.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

class NotificationsTest {

    @Test
    void payloadNeutralizesMentionsButKeepsText() {
        String content = "User tried: @everyone and @here";
        String json = Notifications.buildWebhookPayload(content);

        // Valid JSON (would throw otherwise).
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();

        // allowed_mentions present with an empty parse array -> no pings.
        assertTrue(o.has("allowed_mentions"), "payload must carry allowed_mentions");
        JsonArray parse = o.getAsJsonObject("allowed_mentions").getAsJsonArray("parse");
        assertEquals(0, parse.size(), "parse array must be empty so nothing is pinged");

        // Text is neutralized, NOT stripped: we still log what was typed.
        assertEquals(content, o.get("content").getAsString());
        assertTrue(o.get("content").getAsString().contains("@everyone"));
    }

    @Test
    void payloadEscapesSpecialCharacters() {
        // Gson must escape quotes/backslashes so malformed input can't break the JSON.
        String content = "weird \" \\ \n input";
        String json = Notifications.buildWebhookPayload(content);
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(content, o.get("content").getAsString());
    }
}
