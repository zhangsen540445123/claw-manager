UPDATE instances
SET plugins_entries = JSON_SET(
    plugins_entries,
    '$.openviking.hooks',
    JSON_MERGE_PATCH(
      COALESCE(JSON_EXTRACT(plugins_entries, '$.openviking.hooks'), JSON_OBJECT()),
      JSON_OBJECT('allowConversationAccess', TRUE)
    )
  ),
  updated_at = DATE_FORMAT(UTC_TIMESTAMP(6), '%Y-%m-%dT%H:%i:%s.%fZ')
WHERE plugins_entries IS NOT NULL
  AND JSON_CONTAINS_PATH(plugins_entries, 'one', '$.openviking');
