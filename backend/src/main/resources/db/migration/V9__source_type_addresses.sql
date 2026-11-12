-- Before the per-type address rules, an administrator could create a Hacker News, Hugging Face, GitHub releases
-- or arXiv source with any public address. Those types were never fetched then, but they are now, so any such
-- source whose address is not the one its type may use is switched off (not deleted: it can be reviewed).
-- These patterns are the same as the ones SourceUrlValidator applies to new sources.
UPDATE sources
SET enabled = false
WHERE enabled
  AND type <> 'RSS'
  AND NOT (
       (type = 'HN' AND url = 'https://hn.algolia.com/api/v1/search?tags=front_page&hitsPerPage=30')
    OR (type = 'HUGGINGFACE' AND url = 'https://huggingface.co/api/daily_papers')
    OR (type = 'GITHUB_RELEASES' AND url ~ '^https://github\.com/[A-Za-z0-9_.-]{1,39}/[A-Za-z0-9_.-]{1,100}/releases\.atom$'
        AND url !~ '(/|^)\.+(/|$)')
    OR (type = 'ARXIV' AND url ~ '^https://rss\.arxiv\.org/(rss|atom)/[A-Za-z]{1,12}(-[A-Za-z]{2,10})?(\.[A-Za-z-]{1,15})?$')
  );
