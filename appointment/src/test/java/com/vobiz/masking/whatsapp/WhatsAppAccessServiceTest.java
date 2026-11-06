package com.vobiz.masking.whatsapp;

import com.vobiz.masking.security.WhatsAppPrincipal;
import com.vobiz.masking.util.ApiException;
import com.vobiz.masking.whatsapp.model.WhatsAppStaffAccess;
import com.vobiz.masking.whatsapp.repo.WhatsAppStaffAccessRepository;
import com.vobiz.masking.whatsapp.security.WhatsAppAccessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WhatsAppAccessServiceTest {

    @Mock
    private WhatsAppStaffAccessRepository repository;

    @InjectMocks
    private WhatsAppAccessService accessService;

    @Test
    void createsHighEntropyTokenAndStoresOnlyItsHash() {
        when(repository.save(any(WhatsAppStaffAccess.class))).thenAnswer(invocation -> {
            WhatsAppStaffAccess access = invocation.getArgument(0);
            access.setId(12L);
            return access;
        });

        Map<String, Object> created = accessService.create(Map.of(
                "name", "Rina", "role", "compounder", "can_read", true, "can_send", false));

        String token = (String) created.get("access_token");
        assertNotNull(token);
        assertTrue(token.startsWith("wa_"));
        assertEquals("compounder", created.get("role"));
        assertFalse((Boolean) created.get("can_send"));
        assertFalse(created.containsKey("token_hash"));

        ArgumentCaptor<WhatsAppStaffAccess> captor = ArgumentCaptor.forClass(WhatsAppStaffAccess.class);
        verify(repository).save(captor.capture());
        WhatsAppStaffAccess saved = captor.getValue();
        assertNotEquals(token, saved.getTokenHash());
        assertEquals(64, saved.getTokenHash().length());

        when(repository.findByTokenHashAndActiveTrue(any(String.class))).thenReturn(Optional.of(saved));
        WhatsAppPrincipal principal = accessService.authenticate("Bearer " + token);
        assertNotNull(principal);
        assertEquals("Rina", principal.name());
        assertEquals("compounder", principal.role());
        assertTrue(principal.canRead());
        assertFalse(principal.canSend());
        assertFalse(principal.admin());
    }

    @Test
    void sendPermissionRequiresInboxReadPermission() {
        assertThrows(ApiException.class, () -> accessService.create(Map.of(
                "name", "Rina", "role", "agent", "can_read", false, "can_send", true)));
    }

    @Test
    void rejectsMalformedOrUnknownTokens() {
        when(repository.findByTokenHashAndActiveTrue(any(String.class))).thenReturn(Optional.empty());
        assertNull(accessService.authenticate(null));
        assertNull(accessService.authenticate("Bearer short"));
        assertNull(accessService.authenticate("Bearer wa_" + "a".repeat(43)));
    }
}
