-- Display name for each contact type (decision 5): `label` holds the input
-- placeholder text ("phone number, or a wa.me link"), so the form had no
-- way to say which network a row is for. Added nullable, filled in, then
-- made NOT NULL; plain ALTER/UPDATE statements that run unchanged on both
-- H2 (PostgreSQL mode) and PostgreSQL.
ALTER TABLE contact_type ADD COLUMN name VARCHAR;

UPDATE contact_type SET name = 'Email' WHERE slug = 'email';
UPDATE contact_type SET name = 'WhatsApp' WHERE slug = 'whatsapp';
UPDATE contact_type SET name = 'Telegram' WHERE slug = 'telegram';
UPDATE contact_type SET name = 'Instagram' WHERE slug = 'instagram';
UPDATE contact_type SET name = 'X (Twitter)' WHERE slug = 'twitter';

-- New contact types are added by hand directly in the database (decision
-- 5), so a live database may hold rows this migration doesn't know about.
-- Fall back to the slug for those rather than failing the NOT NULL below.
UPDATE contact_type SET name = slug WHERE name IS NULL;

ALTER TABLE contact_type ALTER COLUMN name SET NOT NULL;
