package com.wagwag.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.wagwag.api.storage.AvatarStorage;
import java.net.URI;
import org.junit.jupiter.api.Test;

class AvatarStorageTest {
    @Test
    void signsUploadForThePetsOwnKey() {
        AvatarStorage storage = new AvatarStorage("avatars", "us-east-1", "http://localhost:9000",
            "https://cdn.example.test/avatars", "testaccess", "testsecret");

        var ticket = storage.prepare(42, "image/png");

        assertThat(ticket.key()).startsWith("pets/42/").endsWith(".png");
        assertThat(ticket.publicUrl()).isEqualTo("https://cdn.example.test/avatars/" + ticket.key());
        assertThat(URI.create(ticket.uploadUrl()).getQuery()).contains("X-Amz-Signature=");
        assertThat(ticket.uploadUrl()).contains("localhost:9000/avatars/pets/42/");
    }
}
