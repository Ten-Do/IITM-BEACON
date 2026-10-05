-- Validation pattern for each contact type (decision 5): a regular
-- expression, stored WITHOUT ^/$ anchors, that a trimmed contact value must
-- match in full. The same text is checked server-side (Java) and rendered as
-- the contact input's HTML `pattern` attribute (compiled by browsers with the
-- JS `v` flag), so it must be valid in both syntaxes — hence `-`, `(`, `)`
-- are escaped inside character classes. The accepted shapes are the ones in
-- docs/use-cases.md's "Contact method catalog" table; the phone-number
-- alternative (optional +, 7-15 digits, up to two of space/-/()/ between
-- digits) is spelled out in full wherever a type accepts one.
--
-- Nullable: a type added by hand without a pattern only requires a
-- non-blank value, so rows this migration doesn't know about stay NULL.
-- Plain ALTER/UPDATE statements that run unchanged on both H2 (PostgreSQL
-- mode) and PostgreSQL (standard_conforming_strings: backslashes in '...'
-- literals are kept as-is).
ALTER TABLE contact_type ADD COLUMN value_pattern VARCHAR;

UPDATE contact_type
SET value_pattern = '[^@\s]+@[^@\s]+\.[^@\s]+'
WHERE slug = 'email';

UPDATE contact_type
SET value_pattern = '\+?\(?[0-9](?:[ \-\(\)]{0,2}[0-9]){6,14}|(?:https?://)?wa\.me/\+?[0-9]{7,15}'
WHERE slug = 'whatsapp';

UPDATE contact_type
SET value_pattern = '\+?\(?[0-9](?:[ \-\(\)]{0,2}[0-9]){6,14}|@?[A-Za-z0-9_]{5,32}|(?:https?://)?t\.me/[A-Za-z0-9_]{5,32}'
WHERE slug = 'telegram';

UPDATE contact_type
SET value_pattern = '@?[A-Za-z0-9._]{1,30}|(?:https?://)?(?:www\.)?instagram\.com/[A-Za-z0-9._]{1,30}/?'
WHERE slug = 'instagram';

UPDATE contact_type
SET value_pattern = '@?[A-Za-z0-9_]{1,15}|(?:https?://)?(?:www\.)?(?:x|twitter)\.com/[A-Za-z0-9_]{1,15}/?'
WHERE slug = 'twitter';
