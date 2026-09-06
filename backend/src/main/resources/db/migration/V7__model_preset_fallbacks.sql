ALTER TABLE model_presets
  ADD COLUMN fallback_preset_ids JSON NULL
  AFTER extra;
