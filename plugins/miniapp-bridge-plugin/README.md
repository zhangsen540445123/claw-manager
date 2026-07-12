# Claw Manager Miniapp Bridge

Registers the sender-scoped `miniapp_api_call` OpenClaw tool. The plugin forwards only an approved action key and business parameters to Claw Manager. Claw Manager resolves the current sender's miniapp binding and injects `X-Open-Api-Openid` plus the bound `cm_user_...` credential.

The plugin never accepts identity headers, credentials, or arbitrary URLs from the model.
