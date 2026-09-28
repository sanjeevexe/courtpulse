rootProject.name = "courtpulse"

include(
    "modules:domain",
    "modules:providers",
    "modules:persistence",
    "modules:messaging",
    "modules:query",
    "modules:testkit",
    "modules:observability",
    "apps:replay-cli",
    "apps:durable-replay-cli",
    "apps:queue-replay-cli",
    "apps:api",
)
