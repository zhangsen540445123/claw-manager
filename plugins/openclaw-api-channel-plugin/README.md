# Claw Manager API Channel

OpenClaw channel plugin used by Claw Manager to deliver external API messages into the normal OpenClaw agent runtime.

Claw Manager writes request files into the instance queue under `.openclaw/claw-manager-api/requests`.
When the Gateway starts the `claw-manager-api` channel, this plugin runs a queue monitor, converts each request into an inbound direct-message context, writes an OpenViking sender handoff for the current API user, dispatches the turn through OpenClaw, and writes the response under `.openclaw/claw-manager-api/responses`.
