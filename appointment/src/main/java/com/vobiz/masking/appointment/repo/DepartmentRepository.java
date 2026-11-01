package com.vobiz.masking.appointment.repo;

import com.vobiz.masking.appointment.model.Department;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DepartmentRepository extends JpaRepository<Department, Long> {

    List<Department> findByActiveTrueOrderByPriorityAscNameAsc();

    Optional<Department> findByNameIgnoreCase(String name);
}
