package meteor.plugins.api.example.deathevent;

import com.google.inject.Provides;
import meteor.config.ConfigManager;
import meteor.eventbus.Subscribe;
import meteor.plugins.Plugin;
import meteor.plugins.PluginDescriptor;
import meteor.plugins.api.entities.NPCs;
import meteor.plugins.api.entities.Players;
import meteor.plugins.api.entities.TileObjects;
import meteor.plugins.api.game.Game;
import meteor.plugins.api.game.GameThread;
import meteor.plugins.api.movement.Movement;
import meteor.plugins.api.widgets.Dialog;
import net.runelite.api.NPC;
import net.runelite.api.TileObject;
import net.runelite.api.events.GameTick;
import net.runelite.api.widgets.Widget;

import java.util.List;
import java.util.stream.Collectors;

@PluginDescriptor(
				name = "Death Event",
				description = "Completes the Death random event for first deaths",
				enabledByDefault = false
)
public class DeathEventPlugin extends Plugin {
	@SuppressWarnings("unused")
	@Subscribe
	public void onGameTick(GameTick e) {
		if (!Game.getClient().isInInstancedRegion()) {
			return;
		}

		NPC death = NPCs.getNearest("Death");
		if (death == null) {
			return;
		}

		if (Players.getLocal().isMoving()) {
			return;
		}

		if (!Dialog.isOpen()) {
			death.interact("Talk-to");
			return;
		}

		if (Dialog.canContinue()) {
			Dialog.continueSpace();
			return;
		}

		if (Dialog.isViewingOptions()) {
			List<Widget> completedDialogs = Dialog.getOptions().stream()
							.filter(x -> x.getText() != null && x.getText().contains("<str>"))
							.collect(Collectors.toList());
			if (completedDialogs.size() >= 4) {
				TileObject portal = TileObjects.getNearest("Portal");
				if (portal != null) {
					portal.interact("Use");
					return;
				}
			}

			Widget incompleteDialog = Dialog.getOptions().stream()
							.filter(x -> !completedDialogs.contains(x))
							.findFirst()
							.orElse(null);
			if (incompleteDialog != null && !GameThread.invokeLater(incompleteDialog::isHidden)) {
				Dialog.chooseOption(Dialog.getOptions().indexOf(incompleteDialog) + 1);
			}
		}
	}

	@Provides
	public DeathEventConfig getConfig(ConfigManager configManager) {
		return configManager.getConfig(DeathEventConfig.class);
	}
}
