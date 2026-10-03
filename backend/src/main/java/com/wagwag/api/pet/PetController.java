package com.wagwag.api.pet;

import com.wagwag.api.storage.AvatarStorage.UploadTicket;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/pets")
public class PetController {
    private final PetService service;

    public PetController(PetService service) { this.service = service; }

    @GetMapping("/{id}")
    public PetResponse get(@PathVariable long id) { return service.get(id); }

    @PostMapping
    public ResponseEntity<PetResponse> create(@Valid @RequestBody PetInput input) {
        PetResponse pet = service.create(input);
        return ResponseEntity.created(URI.create("/api/pets/" + pet.id())).body(pet);
    }

    @PutMapping("/{id}")
    public PetResponse update(@PathVariable long id, @Valid @RequestBody PetInput input) {
        return service.update(id, input);
    }

    @PutMapping("/{id}/privacy")
    public PetResponse privacy(@PathVariable long id, @Valid @RequestBody PrivacyInput input) {
        return service.privacy(id, input.privateProfile());
    }

    @PostMapping("/{id}/avatar-uploads")
    public UploadTicket prepareAvatar(@PathVariable long id,
                                      @Valid @RequestBody AvatarUploadInput input) {
        return service.prepareAvatar(id, input.contentType());
    }

    @PutMapping("/{id}/avatar")
    public PetResponse saveAvatar(@PathVariable long id,
                                  @Valid @RequestBody AvatarKeyInput input) {
        return service.saveAvatar(id, input.key());
    }

    public record AvatarUploadInput(@NotBlank String contentType) {}
    public record AvatarKeyInput(@NotBlank String key) {}
    public record PrivacyInput(@NotNull Boolean privateProfile) {}
}
