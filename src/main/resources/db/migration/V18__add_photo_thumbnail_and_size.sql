-- Every upload is now stored as a full-size WebP plus a thumbnail WebP
-- (decision 2): `thumbnail_path` is the thumbnail's file, relative to the
-- uploads root like `file_path`, and `width`/`height` are the full-size
-- image's pixel dimensions (what a lightbox needs before the image loads).
--
-- Nullable: photos stored before this migration have no thumbnail and no
-- known size until submission.LegacyPhotoBackfill converts them, and one it
-- can't convert keeps all three NULL. Plain ALTER statements that run
-- unchanged on both H2 (PostgreSQL mode) and PostgreSQL.
ALTER TABLE photo ADD COLUMN thumbnail_path VARCHAR;
ALTER TABLE photo ADD COLUMN width INT;
ALTER TABLE photo ADD COLUMN height INT;
