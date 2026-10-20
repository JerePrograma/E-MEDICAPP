-- Existing requirements remain the source of prescription eligibility. Categorization
-- changes neither that flag nor accepted purchases, quotations or stock prices.
-- Only this explicit cosmetic product from the documented DEMO scenario is classified.
UPDATE product
SET metadata=jsonb_set(metadata,'{catalogCategory}','"SKIN_CARE"'::jsonb),
    version=version+1,updated_at=now()
WHERE code='EMC-O-2026' AND name='Protector solar Dermavia FPS 50+'
    AND requires_prescription=false AND metadata->>'demo'='true'
    AND metadata->>'source'='CATALOGO_ESCENARIO_MENDOZA_2026'
    AND NOT metadata ? 'catalogCategory';
