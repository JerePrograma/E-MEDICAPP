-- Product photos are catalog assets, separate from private clinical documents.
-- Only normalized PNGs, at most 512 pixels per side, are stored by the service.
ALTER TABLE product ADD COLUMN image_png bytea;
ALTER TABLE product ADD CONSTRAINT product_image_size_check
 CHECK (image_png IS NULL OR octet_length(image_png) BETWEEN 1 AND 2097152);
