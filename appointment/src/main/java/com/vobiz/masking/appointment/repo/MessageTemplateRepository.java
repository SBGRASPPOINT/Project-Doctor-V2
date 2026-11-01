package com.vobiz.masking.appointment.repo;

import com.vobiz.masking.appointment.model.MessageTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MessageTemplateRepository extends JpaRepository<MessageTemplate, Long> {

    Optional<MessageTemplate> findByKeyAndLanguageAndActiveTrue(String key, String language);

    List<MessageTemplate> findByActiveTrueOrderByKeyAscLanguageAsc();
}
