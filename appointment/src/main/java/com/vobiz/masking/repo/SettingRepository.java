package com.vobiz.masking.repo;

import com.vobiz.masking.model.Setting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Spring Data JPA repository for {@link Setting}; the id is the setting key itself. */
public interface SettingRepository extends JpaRepository<Setting, String> {

    List<Setting> findAllByOrderByKeyAsc();
}
