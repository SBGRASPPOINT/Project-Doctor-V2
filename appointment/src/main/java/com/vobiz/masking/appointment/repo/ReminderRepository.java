package com.vobiz.masking.appointment.repo;

import com.vobiz.masking.appointment.model.Reminder;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ReminderRepository extends JpaRepository<Reminder, Long> {

    /** The scheduler's hot query. */
    List<Reminder> findByStatusAndScheduledAtLessThanEqualOrderByScheduledAtAsc(String status, Instant now, Pageable pageable);

    Optional<Reminder> findByAppointmentIdAndKind(Long appointmentId, String kind);

    List<Reminder> findByAppointmentIdOrderByScheduledAtAsc(Long appointmentId);

    List<Reminder> findByAppointmentIdAndStatusIn(Long appointmentId, List<String> statuses);

    Optional<Reminder> findByOutboundCallUuid(String outboundCallUuid);
}
