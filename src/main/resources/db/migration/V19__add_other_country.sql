-- "Other", for a submitter whose country isn't in the ISO 3166-1 list
-- (decision 1). `ZZ` is a user-assigned ISO 3166 code — never a real
-- country — and CLDR's code for "unknown region". It is listed first in the
-- submission form and the gallery filter, and is not counted as a country
-- in the dashboard's "Countries represented". A plain INSERT that runs
-- unchanged on both H2 (PostgreSQL mode) and PostgreSQL.
INSERT INTO country (code, name) VALUES ('ZZ', 'Other');
