package works.bosk.exceptions;

import works.bosk.BoskContext.Tenant.TenantId;

/**
 * Handy exception to use in driver implementations to deal with cases where a tenant doesn't exist.
 * <p>
 * This is expected to be an unusual situation that doesn't happen often,
 * so since driver implementations can often detect this incidentally while working on the common case,
 * the easiest way to deal with it is to unwind and redo the update as a tenant-management operation.
 */
public class NoSuchTenantException extends Exception {
	public final TenantId tenant;

	public NoSuchTenantException(TenantId tenant) {
		super("No such tenant: " + tenant);
		this.tenant = tenant;
	}

	public NoSuchTenantException(TenantId tenant, String message) {
		super(message);
		this.tenant = tenant;
	}

	public NoSuchTenantException(TenantId tenant, String message, Throwable cause) {
		super(message, cause);
		this.tenant = tenant;
	}

	public NoSuchTenantException(TenantId tenant, Throwable cause) {
		super("No such tenant: " + tenant, cause);
		this.tenant = tenant;
	}
}
