-- Achievement checklist seed data. Labels transcribed verbatim from
-- docs/use-cases.md's "Achievement checklist" section (decision 13); the doc
-- gives no slugs, so each slug below is a short snake_case identifier
-- derived from its label's meaning.
INSERT INTO achievement (slug, label, display_order) VALUES
    ('made_new_friends', 'Made new friends here', 1),
    ('found_love_or_relationship', 'Found love / started a relationship', 2),
    ('built_professional_network', 'Built professional connections or a network', 3),
    ('got_job_or_internship', 'Got a job/internship opportunity through the exchange', 4),
    ('traveled_nearby_countries', 'Traveled to nearby countries', 5),
    ('traveled_within_india', 'Traveled within India', 6),
    ('enjoyed_spicy_indian_food', 'Tried and enjoyed spicy Indian food', 7),
    ('learned_local_dish', 'Learned to cook a local dish', 8),
    ('learned_local_language', 'Picked up words/phrases of a local language', 9),
    ('joined_club_or_sports_team', 'Joined a campus club or sports team', 10),
    ('attended_campus_event', 'Attended a campus festival or major event', 11),
    ('did_community_work', 'Volunteered or did community work', 12),
    ('improved_english_skills', 'Improved my communication/English skills', 13),
    ('became_more_independent', 'Became more independent / self-reliant', 14),
    ('adjusted_to_heat', 'Adjusted well to the heat/climate', 15),
    ('missed_home', 'Missed home a lot at some point', 16),
    ('keeping_in_touch', 'Would keep in touch with people I met here', 17),
    ('changed_career_path', 'Changed my mind about my career path', 18),
    ('left_with_useful_contacts', 'Left with contact info I plan to use', 19),
    ('faced_culture_shock', 'Faced a real culture-shock moment', 20),
    ('learned_to_budget', 'Learned to manage a tight budget', 21),
    ('explored_the_city', 'Explored the city beyond campus', 22),
    ('academic_project_beyond_coursework', 'Took part in an academic project/research beyond coursework', 23),
    ('overcame_health_challenge', 'Overcame a health challenge while here', 24);
