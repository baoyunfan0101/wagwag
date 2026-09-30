package com.wagwag.api.pet;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class User {
    @Id
    private Long id;

    protected User() {}

    public Long getId() { return id; }
}
