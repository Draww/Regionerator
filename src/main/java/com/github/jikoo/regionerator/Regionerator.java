package com.github.jikoo.regionerator;

import com.github.jikoo.regionerator.commands.RegioneratorExecutor;
import com.github.jikoo.regionerator.hooks.Hook;
import com.github.jikoo.regionerator.hooks.PluginHook;
import com.github.jikoo.regionerator.listeners.FlaggingListener;
import com.github.jikoo.regionerator.listeners.HookListener;
import com.github.jikoo.regionerator.util.Config;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Plugin for deleting unused region files gradually.
 *
 * @author Jikoo
 */
@SuppressWarnings({"WeakerAccess", "UnusedReturnValue", "unused"})
public class Regionerator extends JavaPlugin {

	private HashMap<String, DeletionRunnable> deletionRunnables;
	private ChunkFlagger chunkFlagger;
	private List<Hook> protectionHooks;
	private Config config;
	private WorldManager worldManager;
	private boolean paused = false;

	@Override
	public void onEnable() {

		saveDefaultConfig();
		config = new Config();
		config.reload(this);

		deletionRunnables = new HashMap<>();
		chunkFlagger = new ChunkFlagger(this);
		protectionHooks = new ArrayList<>();
		worldManager = new WorldManager(this);

		boolean hasHooks = false;
		Set<String> hookNames = Objects.requireNonNull(Objects.requireNonNull(getConfig().getDefaults()).getConfigurationSection("hooks")).getKeys(false);
		ConfigurationSection hookSection = getConfig().getConfigurationSection("hooks");
		if (hookSection != null) {
			hookNames.addAll(hookSection.getKeys(false));
		}
		for (String hookName : hookNames) {
			// Default true - hooks should likely be enabled unless explicitly disabled
			if (!getConfig().getBoolean("hooks." + hookName, true)) {
				continue;
			}
			try {
				Class<?> clazz = Class.forName("com.github.jikoo.regionerator.hooks." + hookName + "Hook");
				if (!Hook.class.isAssignableFrom(clazz)) {
					// What.
					continue;
				}
				Hook hook = (Hook) clazz.getDeclaredConstructor().newInstance();
				if (!hook.areDependenciesPresent()) {
					debug(DebugLevel.LOW, () -> String.format("Dependencies not found for %s hook, skipping.", hookName));
					continue;
				}
				if (!hook.isReadyOnEnable()) {
					debug(DebugLevel.LOW, () -> String.format("Protection hook for %s is available but not yet ready.", hookName));
					hook.readyLater(this);
					continue;
				}
				if (hook.isHookUsable()) {
					protectionHooks.add(hook);
					hasHooks = true;
					debug(DebugLevel.LOW, () -> "Enabled protection hook for " + hookName);
				} else {
					getLogger().info("Protection hook for " + hookName + " failed usability check! Deletion is paused.");
					paused = true;
				}
			} catch (ClassNotFoundException e) {
				getLogger().severe("No hook found for " + hookName + "! Please request compatibility!");
			} catch (ReflectiveOperationException e) {
				getLogger().severe("Unable to enable hook for " + hookName + "! Deletion is paused.");
				paused = true;
				e.printStackTrace();
			} catch (NoClassDefFoundError e) {
				debug(DebugLevel.LOW, () -> String.format("Dependencies not found for %s hook, skipping.", hookName));
				debug(DebugLevel.MEDIUM, (Runnable) e::printStackTrace);
			}
		}

		// Don't register listeners if there are no worlds configured
		if (config.getWorlds().isEmpty()) {
			getLogger().severe("No worlds are enabled. There's nothing to do!");
			return;
		}

		// Only enable hook listener if there are actually any hooks enabled
		if (hasHooks) {
			getServer().getPluginManager().registerEvents(new HookListener(this), this);
		}

		if (config.getFlagDuration() > 0) {
			// Flag duration is set, start flagging

			getServer().getPluginManager().registerEvents(new FlaggingListener(this), this);

			new FlaggingRunnable(this).runTaskTimer(this, 0, config.getFlaggingInterval());
		} else {
			// Flagging runnable is not scheduled, schedule a task to start deletion
			new BukkitRunnable() {
				@Override
				public void run() {
					attemptDeletionActivation();
				}
			}.runTaskTimer(this, 0L, 1200L);

			// Additionally, since flagging will not be editing values, flagging untouched chunks is not an option
			getConfig().set("delete-new-unvisited-chunks", true);
		}

		PluginCommand command = getCommand("regionerator");
		if (command == null) {
			return;
		}

		command.setExecutor(new RegioneratorExecutor(this, deletionRunnables));

		debug(DebugLevel.LOW, () -> onCommand(Bukkit.getConsoleSender(), Objects.requireNonNull(command), "regionerator", new String[0]));
	}

	@Override
	public void onDisable() {
		// Manually cancel deletion runnables - Bukkit does not do a good job of informing tasks they can't continue.
		deletionRunnables.values().forEach(BukkitRunnable::cancel);
		getServer().getScheduler().cancelTasks(this);

		if (chunkFlagger != null) {
			getLogger().info("Shutting down flagger - currently holds " + chunkFlagger.getCached() + " flags.");
			chunkFlagger.shutdown();
		}
	}

	public Config config() {
		return config;
	}

	public WorldManager getWorldManager() {
		return worldManager;
	}

	/**
	 * Attempts to activate {@link DeletionRunnable}s for any configured worlds.
	 */
	public void attemptDeletionActivation() {
		deletionRunnables.values().removeIf(value -> value.getNextRun() < System.currentTimeMillis());

		if (isPaused()) {
			return;
		}

		for (String worldName : config.getWorlds()) {
			if (getConfig().getLong("delete-this-to-reset-plugin." + worldName) > System.currentTimeMillis()) {
				// Not time yet.
				continue;
			}
			DeletionRunnable runnable = deletionRunnables.get(worldName);
			if (runnable != null) {
				// Deletion is ongoing for world.
				if (runnable.getNextRun() == Long.MAX_VALUE) {
					return;
				}
				// Deletion is complete for world.
				continue;
			}
			World world = Bukkit.getWorld(worldName);
			if (world == null) {
				// World is not loaded.
				continue;
			}
			try {
				runnable = new DeletionRunnable(this, world);
			} catch (RuntimeException e) {
				debug(DebugLevel.HIGH, e::getMessage);
				continue;
			}
			runnable.runTaskAsynchronously(this);
			deletionRunnables.put(worldName, runnable);
			debug(DebugLevel.LOW, () -> "Deletion run scheduled for " + world.getName());
			return;
		}
	}

	public List<Hook> getProtectionHooks() {
		return Collections.unmodifiableList(this.protectionHooks);
	}

	public void addHook(PluginHook hook) {
		if (hook == null) {
			throw new IllegalArgumentException("Hook cannot be null");
		}

		for (Hook enabledHook : this.protectionHooks) {
			if (enabledHook.getClass().equals(hook.getClass())) {
				throw new IllegalStateException(String.format("Hook %s is already enabled", hook.getProtectionName()));
			}
		}

		if (!hook.isHookUsable()) {
			throw new IllegalStateException(String.format("Hook %s is not usable", hook.getProtectionName()));
		}

		this.protectionHooks.add(hook);
	}

	public boolean removeHook(Class<? extends Hook> hook) {
		Iterator<Hook> hookIterator = this.protectionHooks.iterator();
		while (hookIterator.hasNext()) {
			if (hookIterator.next().getClass().equals(hook)) {
				hookIterator.remove();
				return true;
			}
		}
		return false;
	}

	public boolean removeHook(Hook hook) {
		return this.protectionHooks.remove(hook);
	}

	public ChunkFlagger getFlagger() {
		return chunkFlagger;
	}

	public boolean isPaused() {
		return paused;
	}

	public void setPaused(boolean paused) {
		this.paused = paused;
	}

	public boolean debug(DebugLevel level) {
		return config.getDebugLevel().ordinal() >= level.ordinal();
	}

	public void debug(DebugLevel level, Supplier<String> message) {
		if (Regionerator.this.debug(level)) {
			getLogger().info(message.get());
		}
	}

	public void debug(DebugLevel level, Runnable runnable) {
		if (debug(level)) {
			runnable.run();
		}
	}

}
