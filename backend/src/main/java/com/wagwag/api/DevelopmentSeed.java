package com.wagwag.api;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("dev")
public class DevelopmentSeed implements ApplicationRunner {
    private final JdbcTemplate jdbc;

    public DevelopmentSeed(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        jdbc.update("INSERT INTO users (id, display_name) "
            + "VALUES (1, 'WagWag Developer') ON CONFLICT (id) DO NOTHING");
        jdbc.update("INSERT INTO users (id, display_name) "
            + "VALUES (1000, 'WagWag Neighbor') ON CONFLICT (id) DO NOTHING");

        jdbc.update("INSERT INTO pets (id, owner_id, name, species, breed, gender, birthday, bio) "
            + "VALUES (1, 1, 'Mochi', 'Dog', 'Shiba Inu', 'UNKNOWN', DATE '2022-05-14', "
            + "'Hi! I am Mochi. I love long walks and new friends.') ON CONFLICT (id) DO NOTHING");
        jdbc.update("INSERT INTO pets (id, owner_id, name, species, breed, gender, bio) "
            + "VALUES (1000, 1000, 'Biscuit', 'Dog', 'Corgi', 'UNKNOWN', "
            + "'A friendly neighbor ready to make new pet friends.') ON CONFLICT (id) DO NOTHING");

        int taskInserted = jdbc.update("INSERT INTO tasks (id, creator_pet_id, title, description, category, latitude, longitude) "
            + "VALUES (1000, 1000, 'Walk Biscuit', 'A short neighborhood walk for Biscuit.', "
            + "'DOG_WALKING', 29.7604, -95.3698) ON CONFLICT (id) DO NOTHING");
        if (taskInserted > 0) {
            jdbc.update("INSERT INTO task_events (task_id, actor_pet_id, status) VALUES (1000, 1000, 'OPEN')");
        }

        jdbc.update("INSERT INTO listings (id, seller_pet_id, title, description, price_cents, latitude, longitude) "
            + "VALUES (1000, 1000, 'Adjustable dog leash', "
            + "'Gently used leash for neighborhood walks.', 1200, 29.76, -95.37) ON CONFLICT (id) DO NOTHING");

        jdbc.queryForObject("SELECT setval('users_id_seq', GREATEST((SELECT MAX(id) FROM users), "
            + "(SELECT last_value FROM users_id_seq)), true)", Long.class);
        jdbc.queryForObject("SELECT setval('pets_id_seq', GREATEST((SELECT MAX(id) FROM pets), "
            + "(SELECT last_value FROM pets_id_seq)), true)", Long.class);
        jdbc.queryForObject("SELECT setval('tasks_id_seq', GREATEST((SELECT MAX(id) FROM tasks), "
            + "(SELECT last_value FROM tasks_id_seq)), true)", Long.class);
        jdbc.queryForObject("SELECT setval('listings_id_seq', GREATEST((SELECT MAX(id) FROM listings), "
            + "(SELECT last_value FROM listings_id_seq)), true)", Long.class);
    }
}
