package com.navigation.backend.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

@Entity
@Table(name = "beacons")
public class Beacon {

    @Id
    private String uuid; // Primary Key

    private double x;
    private double y;

    // JPA icin bos constructor sart
    public Beacon() {
    }

    public Beacon(String uuid, double x, double y) {
        this.uuid = uuid;
        this.x = x;
        this.y = y;
    }

    // Eski kodlarla uyumluluk icin
    public Beacon(String uuid, Point location) {
        this.uuid = uuid;
        this.x = location.getX();
        this.y = location.getY();
    }

    public String getUuid() {
        return uuid;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    @Transient
    public Point getLocation() {
        return new Point(x, y);
    }
}
