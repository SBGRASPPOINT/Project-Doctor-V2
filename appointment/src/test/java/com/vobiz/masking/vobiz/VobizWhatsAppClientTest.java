package com.vobiz.masking.vobiz;

import com.vobiz.masking.config.VobizProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VobizWhatsAppClientTest {

    @Test
    void mockModeIsExplicitAndDoesNotClaimRealDelivery() {
        VobizProperties properties = new VobizProperties();
        properties.setMock(true);
        VobizWhatsAppClient client = new VobizWhatsAppClient(properties);

        assertTrue(client.isConfigured());
        assertTrue(client.isMock());
        assertTrue(client.sendText("+919876543210", "Hello").startsWith("vobiz.mock."));
    }

    @Test
    void liveMessagingRequiresVobizCredentialsAndChannelIdentity() {
        VobizProperties properties = new VobizProperties();
        properties.setMock(false);
        properties.setAuthId("MA_test");
        properties.setAuthToken("test-token");

        VobizWhatsAppClient clientWithoutChannel = new VobizWhatsAppClient(properties);
        assertFalse(clientWithoutChannel.isMock());
        assertFalse(clientWithoutChannel.isConfigured());

        properties.setWhatsappChannelId("channel-123");
        properties.setWhatsappWabaId("waba-456");
        assertTrue(new VobizWhatsAppClient(properties).isConfigured());
    }
}
