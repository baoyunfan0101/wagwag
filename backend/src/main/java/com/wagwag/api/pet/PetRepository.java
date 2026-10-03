package com.wagwag.api.pet;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PetRepository extends JpaRepository<Pet, Long> {
    @Query("select p from Pet p where p.id <> :petId and p.id not in :hidden order by p.id asc")
    Page<Pet> discover(long petId, Collection<Long> hidden, Pageable page);
}
