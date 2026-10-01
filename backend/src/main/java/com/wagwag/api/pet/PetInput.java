package com.wagwag.api.pet;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record PetInput(
    @NotBlank @Size(max = 80) String name,
    @NotBlank @Size(max = 80) String species,
    @Size(max = 80) String breed,
    @NotNull PetGender gender,
    @PastOrPresent LocalDate birthday,
    @Size(max = 500) String bio
) {}
