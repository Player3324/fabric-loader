package net.fabricmc.minecraft.test.extension;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ModInitializer;

@SuppressWarnings("unused")
public class TestExtensionModEntrypoint implements ModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("extension-test-mod");
	@Override
	public void onInitialize() {
		LOGGER.info("Hello again, Fabric world!");
	}
}
