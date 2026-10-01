INSERT INTO users (id, display_name) VALUES (1, 'WagWag Developer');
INSERT INTO pets (id, owner_id, name, species, breed, gender, birthday, bio)
VALUES (1, 1, 'Mochi', 'Dog', 'Shiba Inu', 'UNKNOWN', '2022-05-14',
        'Hi! I am Mochi. I love long walks and new friends.');
ALTER TABLE users ALTER COLUMN id RESTART WITH 2;
ALTER TABLE pets ALTER COLUMN id RESTART WITH 2;
