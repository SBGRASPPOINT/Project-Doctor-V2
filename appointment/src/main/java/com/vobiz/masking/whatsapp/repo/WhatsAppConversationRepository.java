package com.vobiz.masking.whatsapp.repo;

import com.vobiz.masking.whatsapp.model.WhatsAppConversation;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WhatsAppConversationRepository extends JpaRepository<WhatsAppConversation, Long> {

    Optional<WhatsAppConversation> findByWaId(String waId);

    Optional<WhatsAppConversation> findByPhoneE164(String phoneE164);

    @Query("select c from WhatsAppConversation c "
            + "where (:query = '' or lower(coalesce(c.contactName, '')) like lower(concat('%', :query, '%')) "
            + "or c.phoneE164 like concat('%', :query, '%')) "
            + "order by c.lastMessageAt desc, c.id desc")
    List<WhatsAppConversation> search(@Param("query") String query, Pageable pageable);
}
