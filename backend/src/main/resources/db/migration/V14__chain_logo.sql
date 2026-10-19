-- Chain identity images are separate from private purchase and capability documents.
-- Only normalized PNGs, at most 512 pixels per side, are stored by the catalog service.
ALTER TABLE chain ADD COLUMN logo_png bytea;
ALTER TABLE chain ADD CONSTRAINT chain_logo_size_check
 CHECK (logo_png IS NULL OR octet_length(logo_png) BETWEEN 1 AND 2097152);
