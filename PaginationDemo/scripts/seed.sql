-- Seeds the items table with 2,000,000 rows in one bulk INSERT, using
-- generate_series instead of one INSERT per row (or a Java loop through
-- Hibernate) — that would take minutes/hours at this volume instead of
-- seconds, since each individual INSERT is a separate round trip.
--
-- Run the app once first so Hibernate creates the "items" table
-- (spring.jpa.hibernate.ddl-auto=update), then:
--   psql -h localhost -U paginationdemo -d paginationdemo -f scripts/seed.sql

INSERT INTO items (name, created_at)
SELECT
    'Item ' || gs,
    -- Spread created_at over the past year, not all at once, so sorting by
    -- created_at is a real sort and not just the same order as id.
    NOW() - (random() * INTERVAL '365 days')
FROM generate_series(1, 2000000) AS gs;
