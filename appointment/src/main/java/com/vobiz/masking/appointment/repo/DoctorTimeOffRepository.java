package com.vobiz.masking.appointment.repo;

import com.vobiz.masking.appointment.model.DoctorTimeOff;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface DoctorTimeOffRepository extends JpaRepository<DoctorTimeOff, Long> {

    @Query("select t from DoctorTimeOff t where t.doctorId = :doctorId and t.toTs > :from and t.fromTs < :to order by t.fromTs asc")
    List<DoctorTimeOff> findOverlapping(@Param("doctorId") Long doctorId, @Param("from") Instant from, @Param("to") Instant to);
}
