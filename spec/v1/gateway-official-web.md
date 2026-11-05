# Official DeepSeek Search and Fetch on Android and Harmony

For a selected model service on `https://api.deepseek.com` at port 443, the host may reuse
its saved model credential for the official Search and Fetch API. The supported model base
paths are `/`, `/v1`, `/anthropic` and `/anthropic/v1`, with an optional trailing slash.
A base URL with user information, a query or a fragment is ineligible. A missing or
unreadable key leaves the search capability disabled at startup.

The reuse grant covers only POST to the two canonical URLs
`https://api.deepseek.com/v1/tools/search` and `https://api.deepseek.com/v1/tools/fetch`.
It grants no other origin, path, method, URL alias or redirect. The host strips caller
credentials, then injects the saved model key as Bearer authentication regardless of
the model encoder. Each request resolves the key again. A missing or unreadable key
fails before contacting the upstream. Responses must not expose the key.
The [shared route cases](golden/gateway/official-deepseek-web/cases.json) exercise
the model base and request scope rules on both hosts. The existing gateway schema,
`/v1/http` envelope, failure vocabulary and general redirect rules are unchanged.
