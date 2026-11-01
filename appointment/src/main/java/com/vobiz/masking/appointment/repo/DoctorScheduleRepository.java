package com.vobiz.masking.appointment.repo;

import com.vobiz.masking.appointment.model.DoctorSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DoctorScheduleRepository extends JpaRepository<DoctorSchedule, Long> {

    List<DoctorSchedule> findByDoctorIdAndActiveTrueOrderByDayOfWeekAscStartTimeAsc(Long doctorId);

    List<DoctorSchedule> findByDoctorIdAndDayOfWeekAndActiveTrue(Long doctorId, int dayOfWeek);
}
