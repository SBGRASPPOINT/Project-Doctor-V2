package com.vobiz.masking.appointment.repo;

import com.vobiz.masking.appointment.model.Patient;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PatientRepository extends JpaRepository<Patient, Long> {

    /** The IVR identity step: caller ID -> patient. */
    Optional<Patient> findByPhoneE164(String phoneE164);

    Optional<Patient> findByPatientRef(String patientRef);

    @Query("select p from Patient p where p.active = true and "
            + "(lower(p.name) like lower(concat('%', :q, '%')) or p.phoneE164 like concat('%', :q, '%')) "
            + "order by p.name asc")
    List<Patient> search(@Param("q") String q, Pageable pageable);
}
