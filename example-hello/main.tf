
# These resources target the example's maintenance endpoints. The example authenticates them
# with a bearer token (one of the values of `example.security.tokens`) granting `bosk:state`,
# so the provider must send that token in the Authorization header, or the requests are
# rejected with 401.

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

