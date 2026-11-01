package com.vobiz.masking.repo;

import com.vobiz.masking.model.CallRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Spring Data JPA repository for {@link CallRecord}. */
public interface CallRepository extends JpaRepository<CallRecord, Long> {

    Optional<CallRecord> findByCallUuid(String callUuid);
}
