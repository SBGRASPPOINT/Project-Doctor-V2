package com.vobiz.masking.appointment.repo;

import com.vobiz.masking.appointment.model.Doctor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DoctorRepository extends JpaRepository<Doctor, Long> {

    List<Doctor> findByActiveTrueOrderByNameAsc();

    List<Doctor> findByActiveTrueAndDepartmentIdOrderByNameAsc(Long departmentId);

    Optional<Doctor> findByAgentId(Long agentId);
}
