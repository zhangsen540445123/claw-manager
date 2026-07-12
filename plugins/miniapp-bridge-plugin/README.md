# Claw Manager Miniapp Bridge

Registers five sender-scoped, strongly typed OpenClaw tools for daily tasks, goals, subtasks, habit check-ins, and HTML content. The plugin maps typed operations to approved internal action keys. Claw Manager resolves the current sender's miniapp binding and injects `X-Open-Api-Openid` plus the bound `cm_user_...` credential.

The plugin never accepts identity headers, credentials, or arbitrary URLs from the model.
