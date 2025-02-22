package meteor.plugins.api.externals;

import meteor.config.*;

@ConfigGroup("externalmanager")
public interface ExternalManagerConfig extends Config {
	@ConfigItem(
					keyName = "startup",
					name = "Load on startup",
					description = "Load externals on startup"
	)
	default boolean startup() {
		return false;
	}

	@Icon
	@ConfigItem(
					keyName = "reload",
					name = "(Re-)Load Externals",
					description = "Reload external plugins from externals folder",
					position = 150
	)
	default Button reloadButton() {
		return new Button();
	}
}
