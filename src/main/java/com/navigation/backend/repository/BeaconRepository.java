package com.navigation.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.navigation.backend.model.Beacon;

@Repository
public interface BeaconRepository extends JpaRepository<Beacon, String> {
    // Ozel sorgular gerekirse buraya yazilabilir
}
