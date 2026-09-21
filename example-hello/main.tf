
# These resources target the example's maintenance endpoints, which run in BEARER mode,
# so the provider must send a bearer token (one of the values of `example.security.tokens`)
# in the Authorization header, or the requests are rejected with 401. If the provider
# cannot send headers, use a different `bosk.web-api.maintenance.access` setting.

terraform {
	required_providers {
		bosk = {
			source = "vena/bosk"
			version = "0.0.1"
		}
	}
}

provider "bosk" {
}

resource "bosk_node" "targets" {
	url = "http://localhost:1740/bosk/state/targets"
	value_json = jsonencode([
		{ "somebody" = { "id" = "somebody" } },
		{ "anybody" = { "id" = "anybody" } }
	])
}

