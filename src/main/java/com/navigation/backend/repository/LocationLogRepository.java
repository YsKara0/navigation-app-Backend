package com.navigation.backend.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.navigation.backend.model.LocationLog;

@Repository
public interface LocationLogRepository extends JpaRepository<LocationLog, Long> {
    // Belirli bir oturumun gecmisini getir
    List<LocationLog> findBySessionIdOrderByTimestampDesc(String sessionId);
}
