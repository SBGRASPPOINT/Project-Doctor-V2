package com.vobiz.masking.appointment.repo;

import com.vobiz.masking.appointment.model.Appointment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AppointmentRepository extends JpaRepository<Appointment, Long> {

    Optional<Appointment> findByReferenceCode(String referenceCode);

    Optional<Appointment> findBySlotKey(String slotKey);

    List<Appointment> findByPatientIdAndStartsAtAfterAndStatusInOrderByStartsAtAsc(Long patientId, Instant after, List<String> statuses);

    List<Appointment> findByDoctorIdAndStartsAtBetweenOrderByStartsAtAsc(Long doctorId, Instant from, Instant to);

    List<Appointment> findByStatusInAndStartsAtBetweenOrderByStartsAtAsc(List<String> statuses, Instant from, Instant to);

    List<Appointment> findByNeedsRebookingTrueAndStatusIn(List<String> statuses);
}
