CREATE TABLE testimonial_achievement (
    testimonial_id BIGINT NOT NULL REFERENCES testimonial (id) ON DELETE CASCADE,
    achievement_id BIGINT NOT NULL REFERENCES achievement (id),
    PRIMARY KEY (testimonial_id, achievement_id)
);
