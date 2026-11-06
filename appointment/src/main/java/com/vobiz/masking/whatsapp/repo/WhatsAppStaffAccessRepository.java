package com.vobiz.masking.whatsapp.repo;

import com.vobiz.masking.whatsapp.model.WhatsAppStaffAccess;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WhatsAppStaffAccessRepository extends JpaRepository<WhatsAppStaffAccess, Long> {

    Optional<WhatsAppStaffAccess> findByTokenHashAndActiveTrue(String tokenHash);

    List<WhatsAppStaffAccess> findAllByOrderByCreatedAtDescIdDesc();
}
