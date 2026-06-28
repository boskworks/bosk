package works.bosk.exceptions;

import works.bosk.BoskContext.Tenant.TenantId;

public class NoSuchTenantException extends RuntimeException {
	public final TenantId tenant;

	public NoSuchTenantException(TenantId tenant, String message) {
		super(message);
		this.tenant = tenant;
	}

	public NoSuchTenantException(TenantId tenant, String message, Throwable cause) {
		super(message, cause);
		this.tenant = tenant;
	}

	public NoSuchTenantException(TenantId tenant, Throwable cause) {
		super(cause);
		this.tenant = tenant;
	}
}
