package works.bosk.jackson;

import tools.jackson.core.Version;
import tools.jackson.databind.JacksonModule;

public abstract class BoskJacksonModule extends JacksonModule {

	@Override
	public String getModuleName() {
		return getClass().getSimpleName();
	}

	@Override
	public Version version() {
		return Version.unknownVersion();
	}

}
