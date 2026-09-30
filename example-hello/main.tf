
# This example works only if the application runs its maintenance endpoints with
# `bosk.web.maintenance.access=UNSECURED` (which requires Spring Security to be absent): the Bosk
# Terraform provider supports only `UNSECURED` or HTTP Basic, not bearer tokens.

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

