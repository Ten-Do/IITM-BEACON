-- Contact method catalog seed data, transcribed verbatim from
-- docs/use-cases.md's "Contact method catalog" table (decision 5). label =
-- that table's "Placeholder / accepted input" column text for each slug.
INSERT INTO contact_type (slug, label, display_order) VALUES
    ('email', 'email address', 1),
    ('whatsapp', 'phone number, or a wa.me link', 2),
    ('telegram', 'phone number, @username, or a t.me link', 3),
    ('instagram', '@username or a profile link', 4),
    ('twitter', '@username or a profile link', 5);
