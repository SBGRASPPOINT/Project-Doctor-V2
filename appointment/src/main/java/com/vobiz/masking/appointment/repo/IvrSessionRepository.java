package com.vobiz.masking.appointment.repo;

import com.vobiz.masking.appointment.model.IvrSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface IvrSessionRepository extends JpaRepository<IvrSession, String> {

    /** Cleanup: sessions not touched for 24 h. */
    List<IvrSession> findByUpdatedAtBefore(Instant cutoff);
}
