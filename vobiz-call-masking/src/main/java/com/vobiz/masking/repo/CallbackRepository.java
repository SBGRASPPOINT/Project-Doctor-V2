package com.vobiz.masking.repo;

import com.vobiz.masking.model.CallbackRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Spring Data JPA repository for {@link CallbackRequest}. */
public interface CallbackRepository extends JpaRepository<CallbackRequest, Long> {

    List<CallbackRequest> findByCallIdOrderByCreatedAtDesc(Long callId);
}
