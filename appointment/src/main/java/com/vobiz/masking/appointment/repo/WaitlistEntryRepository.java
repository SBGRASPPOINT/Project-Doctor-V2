package com.vobiz.masking.appointment.repo;

import com.vobiz.masking.appointment.model.WaitlistEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WaitlistEntryRepository extends JpaRepository<WaitlistEntry, Long> {

    List<WaitlistEntry> findByStatusAndDoctorIdOrderByCreatedAtAsc(String status, Long doctorId);

    List<WaitlistEntry> findByPatientIdOrderByCreatedAtDesc(Long patientId);
}
