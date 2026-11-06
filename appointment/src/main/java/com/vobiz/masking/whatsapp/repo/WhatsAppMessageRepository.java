package com.vobiz.masking.whatsapp.repo;

import com.vobiz.masking.whatsapp.model.WhatsAppMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WhatsAppMessageRepository extends JpaRepository<WhatsAppMessage, Long> {

    Optional<WhatsAppMessage> findByProviderMessageId(String providerMessageId);

    List<WhatsAppMessage> findByConversationIdOrderByIdDesc(Long conversationId, Pageable pageable);

    List<WhatsAppMessage> findByConversationIdAndIdLessThanOrderByIdDesc(Long conversationId, Long beforeId, Pageable pageable);

    Optional<WhatsAppMessage> findFirstByMediaId(String mediaId);
}
